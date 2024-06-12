package io.github.ricnorr.numa_locks;

import jdk.internal.vm.annotation.Contended;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

@Contended
public class RTLMcs implements VthreadNumaLock<RTLMcs.UnlockInfo> {

    private static final VarHandle GLOBAL_LOCK;
    private static final VarHandle WAITERS_COUNTER;
    private static final VarHandle TRAIN_DATA;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            GLOBAL_LOCK = l.findVarHandle(RTLMcs.class, "globalLock", Boolean.TYPE);
            WAITERS_COUNTER = l.findVarHandle(RTLMcs.class, "waitersCounter", Integer.TYPE);
            TRAIN_DATA = l.findVarHandle(RTLMcs.class, "trainData", TrainNode.class);
        } catch (ReflectiveOperationException var1) {
            throw new ExceptionInInitializerError(var1);
        }
    }


    private static final int MAX_QUEUE_CNT = LockUtils.NUMA_NODES_CNT;

    private static final int maxFastLocks = 4;
    private static final int cores = Runtime.getRuntime().availableProcessors();
    private static final int numaSize = cores / LockUtils.NUMA_NODES_CNT;

    ThreadLocal<Integer> acqToStrategyReload = ThreadLocal.withInitial(() -> 0);
    ThreadLocal<LockStrategy> cachedLockStrategy = ThreadLocal.withInitial(() -> LockStrategy.NUMA_MCS);
    ThreadLocal<Integer> numaNodeThreadLocal = ThreadLocal.withInitial(LockUtils::getNumaNodeId);
    ThreadLocal<Integer> lockAcquiresThreadLocal = ThreadLocal.withInitial(() -> 0);
    volatile TrainNode trainData = new TrainNode(0, 1, 300);


    volatile boolean globalLock = false;
    volatile int waitersCounter = 0;
    final AtomicReference<Node>[] queues;


    public RTLMcs() {
        @SuppressWarnings("unchecked")
        final AtomicReference<Node>[] tmp = (AtomicReference<Node>[]) Array.newInstance(AtomicReference.class, MAX_QUEUE_CNT);
        for (int i = 0; i < MAX_QUEUE_CNT; i++) {
            tmp[i] = new AtomicReference<>();
        }
        this.queues = tmp;
    }


    @Override
    public UnlockInfo lock() {
        var waiters = incWaiters();
        if (acquireGlobalLock()) {
            return new UnlockInfo().withNowTime();
        }

        int acqToStrategyReloadVal = acqToStrategyReload.get();
        if (--acqToStrategyReloadVal <= 0) {
            acqToStrategyReload.set(25);
            var tdCopy = getTrainDataRelaxed();
            long avgCsSize = tdCopy.csTicksMed;
            if (avgCsSize > 5000 || ((tdCopy.maxWaiters < numaSize) && inNumaWork(tdCopy) && (avgCsSize > 1000))) {
                cachedLockStrategy.set(LockStrategy.MCS);

            } else if ((tdCopy.csCount > 1000) && ( // fast spin is not allowed for small training dataset
                waiters < maxFastLocks || avgCsSize < 200 || (avgCsSize < 1000 && tdCopy.maxWaiters > cores / 10 * 9))) {

                cachedLockStrategy.set(LockStrategy.SPIN);
            } else {
                cachedLockStrategy.set(LockStrategy.NUMA_MCS);
            }
        } else {
            acqToStrategyReload.set(acqToStrategyReloadVal - 1);
        }

        UnlockInfo unlockInfo = switch (cachedLockStrategy.get()) {
            case SPIN -> spinOnGlobal();

            case MCS -> lockNumaMcs(0);

            case NUMA_MCS -> lockNumaMcs(getCachedNumaId());
        };

        return unlockInfo.withNowTime();
    }

    @Override
    public void unlock(UnlockInfo unlockInfo) {
        long csEndTime = System.nanoTime();
        releaseGlobalLock();
        int waiters = decWaiters();

        long csTimeSize = csEndTime - unlockInfo.beginCsTime;

        if (!unlockInfo.notInQueue()) {
            unlockNumaMcs(unlockInfo.queueId, unlockInfo.node);
        }

        // Potentially we may lose data, but it's not matter for approximation
        if (csTimeSize > 0) {
            int numa = LockUtils.getNumaNodeId();
            TrainNode curTrainNode = TrainNode.copyOf(getTrainDataRelaxed());

            curTrainNode.csTicksMed += medianSignDiff(csTimeSize, curTrainNode.csTicksMed);
            ++curTrainNode.csCount;
            ++curTrainNode.numaCount[numa];
            curTrainNode.maxWaiters = Math.max(curTrainNode.maxWaiters, waiters);
            setTrainDataRelaxed(curTrainNode);
        }
    }

    private UnlockInfo spinOnGlobal() {
        while (globalLock || !acquireGlobalLock()) {
            Thread.onSpinWait();
        }
        return new UnlockInfo();
    }

    private UnlockInfo lockNumaMcs(int queueId) {
        AtomicReference<Node> tail = queues[queueId];
        Node node = new Node();
        Node predecessor = tail.getAndSet(node);

        if (predecessor != null) {
            predecessor.next = node;

            int steps = 1024;
            while (node.locked) {
                if (--steps == 0) {
                    LockSupport.park();
                }
            }
        }

        Node next = null;
        while (globalLock || !acquireGlobalLock()) {
            if (next == null) {
                next = node.next;
                if (next != null) {
                    LockSupport.unpark(next.thread);
                }
            }
            Thread.onSpinWait();
        }
        return new UnlockInfo(queueId, node);
    }

    private void unlockNumaMcs(int queueId, Node node) {
        AtomicReference<Node> tail = queues[queueId];

        Node next = node.next;
        if (next == null) {

            if (tail.compareAndSet(node, null)) {
                return;
            }

            do {
                Thread.onSpinWait();
                next = node.next;
            } while (next == null);
        }

        node.next = null;
        next.locked = false;
        LockSupport.unpark(next.thread);
    }

    private boolean inNumaWork(TrainNode trainNode) {
        for (int cnt : trainNode.numaCount) {
            if ((double) cnt / trainNode.csCount > 0.9) {
                return true;
            }
        }
        return false;
    }

    private boolean acquireGlobalLock() {
        return GLOBAL_LOCK.compareAndSet(this, false, true);
    }

    private void releaseGlobalLock() {
        GLOBAL_LOCK.set(this, false);
    }

    private int incWaiters() {
        return (int) WAITERS_COUNTER.getAndAdd(this, 1);
    }

    private int decWaiters() {
        return (int) WAITERS_COUNTER.getAndAdd(this, -1);
    }

    private TrainNode getTrainDataRelaxed() {
        return (TrainNode) TRAIN_DATA.get(this);
    }

    private void setTrainDataRelaxed(TrainNode newTrainData) {
        TRAIN_DATA.set(this, newTrainData);
    }

    private long medianSignDiff(long stepResult, long currentMedian) {
        return Long.signum(stepResult - currentMedian);
    }

    private int getCachedNumaId() {
        var numaId = LockUtils.getByThreadFromThreadLocal(numaNodeThreadLocal, LockUtils.getCurrentCarrierThread());
        var lockAcquires =
            LockUtils.getByThreadFromThreadLocal(lockAcquiresThreadLocal, LockUtils.getCurrentCarrierThread());
        lockAcquires++;
        if (lockAcquires >= 10_000) {
            lockAcquires = 1;
            LockUtils.setByThreadToThreadLocal(numaNodeThreadLocal, LockUtils.getCurrentCarrierThread(),
                LockUtils.getNumaNodeId());
        }
        LockUtils.setByThreadToThreadLocal(lockAcquiresThreadLocal, LockUtils.getCurrentCarrierThread(), lockAcquires);

        return numaId;
    }
    enum LockStrategy {
        SPIN,
        MCS,
        NUMA_MCS
    }

    private static class TrainNode {
        int maxWaiters;
        int csCount;
        long csTicksMed;
        int[] numaCount;

        TrainNode(int maxWaiters, int csCount, long csTicksMed, int[] numaCount) {
            this.maxWaiters = maxWaiters;
            this.csCount = csCount;
            this.csTicksMed = csTicksMed;
            if (numaCount != null) {
                this.numaCount = Arrays.copyOf(numaCount, numaCount.length);
            }
        }

        TrainNode(int maxWaiters, int csCount, long csTicksMed) {
            this(maxWaiters, csCount, csTicksMed, null);

            this.numaCount = new int[LockUtils.NUMA_NODES_CNT];
        }

        static TrainNode copyOf(TrainNode other) {
            return new TrainNode(other.maxWaiters, other.csCount, other.csTicksMed, other.numaCount);
        }
    }

    private static class Node {
        private static final VarHandle NEXT;

        private final Thread thread = Thread.currentThread();

        private volatile boolean locked = true;
        private volatile Node next = null;

        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                NEXT = l.findVarHandle(RTLMcs.class, "trainData", TrainNode.class);
            } catch (ReflectiveOperationException var1) {
                throw new ExceptionInInitializerError(var1);
            }
        }
    }

    public static class UnlockInfo {

        int queueId;
        long beginCsTime;
        Node node;

        public UnlockInfo() {
            this.queueId = -1;
        }

        public UnlockInfo(int queueId, Node node) {
            this.queueId = queueId;
            this.node = node;
        }

        boolean notInQueue() {
            return queueId == -1;
        }

        public UnlockInfo withNowTime() {
            this.beginCsTime = System.nanoTime();
            return this;
        }
    }
}
