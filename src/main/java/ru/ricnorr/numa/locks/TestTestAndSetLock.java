package ru.ricnorr.numa.locks;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

import static ru.ricnorr.numa.locks.Utils.spinWaitYield;

public class TestTestAndSetLock implements Lock {

    private AtomicBoolean flag = new AtomicBoolean(false);

    @Override
    public void lock() {
        int spinCounter = 1;
        while (true) {
            if (!flag.get() && flag.compareAndSet(false, true)) {
                return;
            }
            spinCounter = spinWaitYield(spinCounter);
        }
    }

    @Override
    public void unlock() {
        flag.set(false);
    }

    @Override
    public void lockInterruptibly() {
        throw new RuntimeException("Not implemented");
    }

    @Override
    public boolean tryLock() {
        throw new RuntimeException("Not implemented");
    }

    @Override
    public boolean tryLock(long l, @NotNull TimeUnit timeUnit) {
        throw new RuntimeException("Not implemented");
    }

    @NotNull
    @Override
    public Condition newCondition() {
        throw new RuntimeException("Not implemented");
    }
}

