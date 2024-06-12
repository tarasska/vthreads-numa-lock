package io.github.ricnorr.numa_locks;

import jdk.internal.vm.annotation.Contended;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

public class ParkMCS implements VthreadNumaLock<ParkMCS.QNode> {

    private final AtomicReference<QNode> tail = new AtomicReference<>(null);

    @Override
    public QNode lock() {

        QNode qnode = new QNode();
        qnode.spin = true;
        qnode.next.set(null);

        QNode pred = tail.getAndSet(qnode);
        if (pred != null) {
            pred.next.set(qnode);
            int i = 0;
            while (qnode.spin) {
                if (i > 1024) {
                    LockSupport.park();
                }
                i++;
            }
        }
        return qnode;
    }


    @Override
    public void unlock(QNode node) {
        if (node.next.get() == null) {
            if (tail.compareAndSet(node, null)) {
                return;
            }
            while (node.next.get() == null) {
                Thread.onSpinWait();
            }
        }
        node.next.get().spin = false;
        LockSupport.unpark(node.next.get().thread);
    }

    public static class QNode {

        @Contended
        private final AtomicReference<QNode> next = new AtomicReference<>(null);

        @Contended
        private volatile boolean spin = true;

        private final Thread thread = Thread.currentThread();
    }
}