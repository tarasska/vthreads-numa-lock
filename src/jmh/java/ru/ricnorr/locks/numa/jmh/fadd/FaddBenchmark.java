package ru.ricnorr.locks.numa.jmh.fadd;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.infra.Blackhole;
import ru.ricnorr.locks.numa.jmh.BenchmarkUtil;
import ru.ricnorr.locks.numa.jmh.LockType;

/**
 * Benchmark idea from https://onlinelibrary.wiley.com/doi/abs/10.1002/cpe.5964
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class FaddBenchmark {
    @State(Scope.Benchmark) // All threads share this state
    public static class MultiFaddState {

        @Param
        public LockType lockType;

        @Param({"4", "8", "16"})
        public int multiVariableCount;

        public AtomicIntegerArray multiVariableArray;

        public Lock lock;

        @Setup(Level.Trial)
        public void setUp() {
            lock = BenchmarkUtil.initLock(lockType);
            multiVariableArray = new AtomicIntegerArray(multiVariableCount);
        }
    }

    @Benchmark
    public void multiVar(Blackhole bh, MultiFaddState state) {
        state.lock.lock();
        int index = ThreadLocalRandom.current().nextInt(0, state.multiVariableCount);
        long t = System.currentTimeMillis();
        long tenSeconds = 10 * 1000;
        long end = t + tenSeconds;
        while (System.currentTimeMillis() < end) {
            bh.consume(state.multiVariableArray.getAndAdd(index, 1));
        }
        state.lock.unlock();
    }

    @State(Scope.Benchmark) // All threads share this state
    public static class SingleVariableBenchmarkState {

        @Param
        public LockType lockType;


        public AtomicInteger variable;

        public Lock lock;

        @Setup(Level.Trial)
        public void setUp() {
            lock = BenchmarkUtil.initLock(lockType);
            variable = new AtomicInteger();
        }
    }

    @Benchmark
    public void singleVar(Blackhole bh, SingleVariableBenchmarkState state) {
        state.lock.lock();
        bh.consume(state.variable.getAndAdd(1));
        state.lock.unlock();
    }
}
