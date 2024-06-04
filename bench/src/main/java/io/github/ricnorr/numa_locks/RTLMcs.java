package io.github.ricnorr.numa_locks;

import jdk.internal.vm.annotation.Contended;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

@Contended
public class RTLMcs implements VthreadNumaLock<RTLMcs.UnlockInfo> {

    private static final VarHandle VALUE;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            VALUE = l.findVarHandle(RTLMcs.class, "globalLock", Boolean.TYPE);
        } catch (ReflectiveOperationException var1) {
            throw new ExceptionInInitializerError(var1);
        }
    }


    private static int MAX_QUEUE_CNT = LockUtils.NUMA_NODES_CNT * 2;

    int maxFastLocks = 1;
    int cores = Runtime.getRuntime().availableProcessors();
    int numaSize = cores / LockUtils.NUMA_NODES_CNT;

    AtomicReference<TrainNode> trainData = new AtomicReference<>(new TrainNode(0, 1, 300));
    ThreadLocal<Integer> acqToStrategyReload = ThreadLocal.withInitial(() -> 0);
    ThreadLocal<LockStrategy> cachedLockStrategy = ThreadLocal.withInitial(() -> LockStrategy.NUMA_MCS);

    volatile boolean globalLock = false;
    final AtomicInteger tickets = new AtomicInteger(0);
    final List<AtomicReference<Node>> queues;


    public RTLMcs() {
        queues = new ArrayList<>(MAX_QUEUE_CNT);
        for (int i = 0; i < MAX_QUEUE_CNT; i++) {
            queues.add(new AtomicReference<>());
        }
    }


    @Override
    public UnlockInfo lock() {
        var ticket = tickets.getAndIncrement();
        if (acquireGlobalLock()) {
            return new UnlockInfo().withNowTime();
        }

        int acqToStrategyReloadVal = acqToStrategyReload.get();
        if (--acqToStrategyReloadVal <= 0) {
            acqToStrategyReload.set(25);
            var tdCopy = trainData.getAcquire();
            long avgCsSize = tdCopy.csTicksMed;
            if (avgCsSize > 5000 || ((tdCopy.maxWaiters < numaSize) && inNumaWork(tdCopy) && (avgCsSize > 1000))) {
                cachedLockStrategy.set(LockStrategy.MCS);

            } else if ((tdCopy.csCount > 1000) && ( // fast spin is not allowed for small training dataset
                ticket < maxFastLocks || avgCsSize < 200 || (avgCsSize < 1000 && tdCopy.maxWaiters > cores / 10 * 9))) {

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

            case NUMA_MCS -> {
                int core = LockUtils.getCpuId();
                int queueId;
                if (ticket <= cores) {
                    queueId = (core / 4) % MAX_QUEUE_CNT;
                } else {
                    queueId = core / numaSize;
                }

                yield lockNumaMcs(queueId);
            }
        };

        return unlockInfo.withNowTime();
    }

    @Override
    public void unlock(UnlockInfo unlockInfo) {
        long csEndTime = System.nanoTime();
        globalLock = false;
        int ticket = tickets.getAndDecrement();

        long csTimeSize = csEndTime - unlockInfo.beginCsTime;

        if (!unlockInfo.notInQueue()) {
            unlockNumaMcs(unlockInfo.queueId, unlockInfo.node);
        }

        // Potentially we may lose data, but it's not matter for approximation
        if (csTimeSize > 0) {
            int numa = LockUtils.getNumaNodeId();
            TrainNode curTrainNode = TrainNode.copyOf(trainData.get());

            curTrainNode.csTicksMed += medianSignDiff(csTimeSize, curTrainNode.csTicksMed);
            ++curTrainNode.csCount;
            ++curTrainNode.numaCount[numa];
            curTrainNode.maxWaiters = Math.max(curTrainNode.maxWaiters, ticket);
            trainData.set(curTrainNode);
        }
    }

    private UnlockInfo spinOnGlobal() {
        while (globalLock || !acquireGlobalLock()) {
            Thread.onSpinWait();
        }
        return new UnlockInfo();
    }

    private UnlockInfo lockNumaMcs(int queueId) {
        AtomicReference<Node> tail = queues.get(queueId);
        Node node = new Node();
        Node predecessor = tail.getAndSet(node);

        if (predecessor != null) {
            predecessor.next.set(node);

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
                next = node.next.get();
                if (next != null) {
                    LockSupport.unpark(next.thread);
                }
            }
            Thread.onSpinWait();
        }
        return new UnlockInfo(queueId, node);
    }

    private void unlockNumaMcs(int queueId, Node node) {
        AtomicReference<Node> tail = queues.get(queueId);

        Node next = node.next.get();
        if (next == null) {

            if (tail.compareAndSet(node, null)) {
                return;
            }

            do {
                Thread.onSpinWait();
                next = node.next.get();
            } while (next == null);
        }

        node.next.set(null);
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
        return VALUE.compareAndSet(this, false, true);
    }

    private long medianSignDiff(long stepResult, long currentMedian) {
        return Long.signum(stepResult - currentMedian);
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

    @Contended
    private static class Node {
        volatile boolean locked = true;
        AtomicReference<Node> next = new AtomicReference<>();
        Thread thread = Thread.currentThread();
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
