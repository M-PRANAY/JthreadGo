import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.jthreadgo.JthreadGo;

/** Demonstrates that submit() can catch the stop signal and keep its worker thread alive. */
public final class StopExecutorTask {
    public static void main(String[] args) throws Exception {
        if (!JthreadGo.isAvailable()) {
            throw new IllegalStateException("Start Java with -agentpath:<absolute path to the native agent>");
        }
        AtomicReference<Thread> workerReference = new AtomicReference<>();
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch enteredTask = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor(task -> {
            Thread worker = Thread.ofPlatform().daemon(true).name("example-executor-worker").unstarted(task);
            workerReference.set(worker);
            return worker;
        });
        try {
            var future = executor.submit(() -> {
                try {
                    enteredTask.countDown();
                    while (running.get()) {
                        Thread.onSpinWait();
                    }
                } finally {
                    System.out.println("Task finally block executed.");
                }
            });
            if (!enteredTask.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Task did not start");
            }

            Thread worker = workerReference.get();
            // The task deliberately remains in its loop until stopped. With real jobs,
            // completion may race this request and expose an unrelated subsequent task.
            JthreadGo.stop(worker);
            try {
                future.get(5, TimeUnit.SECONDS);
                throw new IllegalStateException("Expected the submitted task to fail");
            } catch (ExecutionException failure) {
                if (!(failure.getCause() instanceof JthreadGo.StopRequested)) {
                    throw failure;
                }
                System.out.println("Future reports the task failed with StopRequested.");
            }

            System.out.println("Original worker is still alive: " + worker.isAlive());
            Thread nextWorker = executor.submit(Thread::currentThread).get(5, TimeUnit.SECONDS);
            System.out.println("Next task used the same worker: " + (worker == nextWorker));
        } finally {
            running.set(false);
            executor.shutdownNow();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Executor did not shut down");
            }
        }
    }
}
