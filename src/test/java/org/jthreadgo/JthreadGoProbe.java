package org.jthreadgo;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Run one case per disposable JVM. No case uses Windows native thread termination.
 * Usage: JthreadGoProbe cpu|cpu-hot|monitor|sleeping|swallowed|future|virtual|new|terminated|no-agent|arguments
 */
public final class JthreadGoProbe {
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);
    private static final AtomicReference<Throwable> UNEXPECTED_WORKER_FAILURE = new AtomicReference<>();

    private JthreadGoProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Supply exactly one probe case name");
        }
        String probe = args[0];
        if (!"no-agent".equals(probe)) {
            require(JthreadGo.isAvailable(), "JVMTI agent is not available for " + probe);
        }
        switch (probe) {
            case "cpu" -> cpu();
            case "cpu-hot" -> cpuHot();
            case "monitor" -> monitor();
            case "sleeping" -> sleeping();
            case "swallowed" -> swallowed();
            case "future" -> future();
            case "virtual" -> virtual();
            case "new" -> newThread();
            case "terminated" -> terminated();
            case "no-agent" -> noAgent();
            case "arguments" -> arguments();
            default -> throw new IllegalArgumentException("Unknown probe case: " + probe);
        }
        if (UNEXPECTED_WORKER_FAILURE.get() != null) {
            throw new AssertionError("Unexpected worker exception", UNEXPECTED_WORKER_FAILURE.get());
        }
    }

    private static void cpu() throws Exception {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicBoolean finallyRan = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch ignoredInterrupt = new CountDownLatch(1);
        Thread worker = daemon("probe-cpu", () -> {
            try {
                entered.countDown();
                while (running.get()) {
                    if (Thread.currentThread().isInterrupted()) {
                        ignoredInterrupt.countDown();
                    }
                    Thread.onSpinWait();
                }
            } finally {
                finallyRan.set(true);
            }
        });
        worker.start();
        try {
            await(entered, "CPU loop entry");
            worker.interrupt();
            await(ignoredInterrupt, "CPU loop observation of ignored interruption");
            require(worker.isAlive(), "Interruption unexpectedly stopped the CPU worker");
            require(JthreadGo.stopAndWait(worker, STOP_TIMEOUT), "CPU worker did not terminate");
            require(finallyRan.get(), "CPU worker did not execute finally");
            pass("cpu", "An interrupt-ignoring CPU loop terminated; its finally block executed.");
        } finally {
            cleanup(worker, running);
        }
    }

    private static void cpuHot() throws Exception {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicBoolean finallyRan = new AtomicBoolean();
        AtomicLong iterations = new AtomicLong();
        AtomicReference<Throwable> uncaughtSignal = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        Thread worker = daemon("probe-cpu-hot", () -> hotCpuLoop(running, finallyRan, entered, iterations));
        worker.setUncaughtExceptionHandler((thread, error) -> uncaughtSignal.set(error));
        worker.start();
        try {
            await(entered, "hot CPU loop entry");
            worker.interrupt();
            // Give the loop time to become hot enough for compilation under normal JIT settings.
            // Compilation logs, when enabled by the caller, can verify actual compiler decisions.
            Thread.sleep(2_000);
            require(worker.isAlive(), "Interruption unexpectedly stopped the hot CPU worker");
            require(iterations.get() > 0, "Hot CPU loop made no progress");
            require(JthreadGo.stopAndWait(worker, STOP_TIMEOUT), "Hot CPU worker did not terminate");
            require(finallyRan.get(), "Hot CPU worker did not execute finally");
            require(uncaughtSignal.get() instanceof JthreadGo.StopRequested,
                    "Hot CPU worker did not expose the expected uncaught stop signal: " + uncaughtSignal.get());
            pass("cpu-hot", "CPU loop warmed for two seconds (" + iterations.get()
                    + " iterations); finally ran and uncaught StopRequested was observed.");
        } finally {
            cleanup(worker, running);
        }
    }

    private static void hotCpuLoop(AtomicBoolean running, AtomicBoolean finallyRan,
                                   CountDownLatch entered, AtomicLong iterations) {
        try {
            entered.countDown();
            while (running.get()) {
                iterations.incrementAndGet();
                Thread.onSpinWait();
            }
        } finally {
            finallyRan.set(true);
        }
    }

    private static void monitor() throws Exception {
        Object monitor = new Object();
        int[] pairedState = {0, 0};
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicBoolean finallyRan = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        Thread worker = daemon("probe-monitor-owner", () -> {
            synchronized (monitor) {
                try {
                    pairedState[0] = 1;
                    entered.countDown();
                    while (running.get()) {
                        Thread.onSpinWait();
                    }
                    pairedState[1] = 1;
                } finally {
                    finallyRan.set(true);
                }
            }
        });
        worker.start();
        try {
            await(entered, "synchronized section entry");
            require(JthreadGo.stopAndWait(worker, STOP_TIMEOUT), "Monitor owner did not terminate");
            require(finallyRan.get(), "Monitor owner's finally did not execute");
            CountDownLatch acquired = new CountDownLatch(1);
            Thread observer = daemon("probe-monitor-observer", () -> {
                synchronized (monitor) {
                    acquired.countDown();
                }
            });
            observer.start();
            await(acquired, "monitor reacquisition after unwinding");
            join(observer, "monitor observer");
            require(pairedState[0] == 1 && pairedState[1] == 0,
                    "Expected partially updated application state");
            pass("monitor", "Java monitor was released and finally ran, but paired state remained [1, 0].");
        } finally {
            cleanup(worker, running);
        }
    }

    private static void sleeping() throws Exception {
        AtomicBoolean finallyRan = new AtomicBoolean();
        AtomicBoolean wasInterrupted = new AtomicBoolean();
        Thread worker = daemon("probe-sleeping", () -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                wasInterrupted.set(true);
            } finally {
                finallyRan.set(true);
            }
        });
        worker.start();
        try {
            awaitState(worker, Thread.State.TIMED_WAITING);
            require(JthreadGo.stopAndWait(worker, Duration.ofSeconds(3)),
                    "Sleeping worker did not terminate within three seconds");
            require(finallyRan.get(), "Sleeping worker did not execute finally");
            require(!wasInterrupted.get(), "Sleep returned InterruptedException rather than JthreadGo.StopRequested");
            pass("sleeping", "A sleeping platform thread terminated within three seconds; finally ran.");
        } finally {
            worker.interrupt();
            join(worker, "sleeping worker cleanup");
        }
    }

    private static void swallowed() throws Exception {
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch caught = new CountDownLatch(1);
        Thread worker = daemon("probe-swallowed", () -> {
            try {
                entered.countDown();
                while (running.get()) {
                    Thread.onSpinWait();
                }
            } catch (JthreadGo.StopRequested expected) {
                caught.countDown();
                while (running.get()) {
                    Thread.onSpinWait();
                }
            }
        });
        worker.start();
        try {
            await(entered, "catching worker entry");
            boolean terminated = JthreadGo.stopAndWait(worker, Duration.ofMillis(500));
            await(caught, "JthreadGo.StopRequested catch");
            require(!terminated && worker.isAlive(), "A worker that caught JthreadGo.StopRequested should remain alive");
            running.set(false);
            join(worker, "cooperative exit after swallowed JthreadGo.StopRequested");
            pass("swallowed", "JthreadGo.StopRequested was caught; stopAndWait returned false. A cooperative flag ended the worker.");
        } finally {
            cleanup(worker, running);
        }
    }

    private static void future() throws Exception {
        AtomicReference<Thread> workerReference = new AtomicReference<>();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicBoolean finallyRan = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread worker = daemon("probe-pool-worker", task);
            workerReference.set(worker);
            return worker;
        });
        try {
            Future<?> task = executor.submit(() -> {
                try {
                    entered.countDown();
                    while (running.get()) {
                        Thread.onSpinWait();
                    }
                } finally {
                    finallyRan.set(true);
                }
            });
            await(entered, "FutureTask body entry");
            Thread worker = workerReference.get();
            require(worker != null, "Executor did not expose its worker");
            JthreadGo.stop(worker);
            try {
                task.get(5, TimeUnit.SECONDS);
                throw new AssertionError("FutureTask completed normally after JthreadGo.StopRequested");
            } catch (ExecutionException expected) {
                require(expected.getCause() instanceof JthreadGo.StopRequested,
                        "Unexpected task failure: " + expected.getCause());
            }
            require(finallyRan.get(), "FutureTask body did not execute finally");
            require(worker.isAlive(), "Pool worker did not survive FutureTask catching JthreadGo.StopRequested");
            Thread nextWorker = executor.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
            require(nextWorker == worker, "Follow-up task ran on a replacement worker");
            pass("future", "FutureTask caught JthreadGo.StopRequested as task failure; the same pool thread ran the next task.");
        } finally {
            running.set(false);
            executor.shutdownNow();
            require(executor.awaitTermination(5, TimeUnit.SECONDS), "Executor cleanup timed out");
        }
    }

    private static void virtual() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread worker = Thread.ofVirtual().name("probe-virtual").unstarted(() -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        worker.start();
        try {
            await(entered, "virtual thread entry");
            expect(UnsupportedOperationException.class, () -> JthreadGo.stop(worker));
            require(worker.isAlive(), "Rejected virtual thread was unexpectedly terminated");
            pass("virtual", "A live virtual thread was rejected before requesting termination.");
        } finally {
            release.countDown();
            join(worker, "virtual worker cleanup");
        }
    }

    private static void newThread() throws Exception {
        Thread worker = daemon("probe-new", () -> {});
        expect(IllegalThreadStateException.class, () -> JthreadGo.stop(worker));
        require(worker.getState() == Thread.State.NEW, "Rejected NEW thread changed state");
        pass("new", "An unstarted platform thread was rejected with IllegalThreadStateException.");
    }

    private static void terminated() throws Exception {
        Thread worker = daemon("probe-terminated", () -> {});
        worker.start();
        join(worker, "initial normal completion");
        JthreadGo.stop(worker);
        require(JthreadGo.stopAndWait(worker, STOP_TIMEOUT), "Terminated thread did not report completed");
        pass("terminated", "Stopping an already terminated thread was a no-op; stopAndWait returned true.");
    }

    private static void noAgent() throws Exception {
        require(!JthreadGo.isAvailable(), "no-agent case must run without loading the native agent");
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch entered = new CountDownLatch(1);
        Thread worker = daemon("probe-no-agent", () -> {
            entered.countDown();
            while (running.get()) {
                Thread.onSpinWait();
            }
        });
        worker.start();
        try {
            await(entered, "no-agent worker entry");
            IllegalStateException failure = expect(IllegalStateException.class, () -> JthreadGo.stop(worker));
            require(failure.getMessage() != null && !failure.getMessage().isBlank(),
                    "Unavailable-agent error should explain why stop failed");
            require(worker.isAlive(), "Worker changed state after unavailable-agent failure");
            pass("no-agent", "isAvailable returned false; stop failed clearly: " + failure.getMessage());
        } finally {
            cleanup(worker, running);
        }
    }

    private static void arguments() throws Exception {
        Thread caller = Thread.currentThread();
        expect(NullPointerException.class, () -> JthreadGo.stop(null));
        expect(NullPointerException.class, () -> JthreadGo.stopAndWait(null, STOP_TIMEOUT));
        expect(NullPointerException.class, () -> JthreadGo.stopAndWait(caller, null));
        expect(IllegalArgumentException.class, () -> JthreadGo.stopAndWait(caller, Duration.ZERO));
        expect(IllegalArgumentException.class, () -> JthreadGo.stopAndWait(caller, Duration.ofNanos(-1)));
        expect(IllegalArgumentException.class, () -> JthreadGo.stopAndWait(caller, STOP_TIMEOUT));
        require(!caller.isInterrupted(), "Argument validation unexpectedly changed caller interrupt status");
        pass("arguments", "Null targets, invalid timeouts, and waiting for oneself were rejected before stopping anything.");
    }

    private static Thread daemon(String name, Runnable body) {
        Thread worker = Thread.ofPlatform().daemon(true).name(name).unstarted(body);
        worker.setUncaughtExceptionHandler((thread, error) -> {
            if (!(error instanceof JthreadGo.StopRequested)) {
                UNEXPECTED_WORKER_FAILURE.compareAndSet(null, error);
                error.printStackTrace(System.err);
            }
        });
        return worker;
    }

    private static void await(CountDownLatch latch, String observation) throws InterruptedException {
        require(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for " + observation);
    }

    private static void awaitState(Thread worker, Thread.State expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (worker.getState() != expected && worker.isAlive() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
        require(worker.getState() == expected,
                "Expected " + expected + " but observed " + worker.getState());
    }

    private static void join(Thread worker, String observation) throws InterruptedException {
        worker.join(5_000);
        require(!worker.isAlive(), "Timed out waiting for " + observation);
    }

    private static void cleanup(Thread worker, AtomicBoolean running) throws InterruptedException {
        running.set(false);
        worker.interrupt();
        join(worker, worker.getName() + " cooperative cleanup");
    }

    private static <T extends Throwable> T expect(Class<T> expected, ThrowingAction action) throws Exception {
        try {
            action.run();
        } catch (Throwable failure) {
            if (expected.isInstance(failure)) {
                return expected.cast(failure);
            }
            throw new AssertionError("Expected " + expected.getSimpleName() + ", got " + failure, failure);
        }
        throw new AssertionError("Expected " + expected.getSimpleName() + " but operation succeeded");
    }

    private static void require(boolean condition, String failure) {
        if (!condition) {
            throw new AssertionError(failure);
        }
    }

    private static void pass(String probe, String observation) {
        System.out.println("PASS " + probe + ": " + observation);
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
