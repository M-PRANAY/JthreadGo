import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jthreadgo.JthreadGo;

/** Run only in a disposable JVM: asynchronous stop can leave application state inconsistent. */
public final class StopCpuWorker {
    public static void main(String[] args) throws InterruptedException {
        if (!JthreadGo.isAvailable()) {
            throw new IllegalStateException("Start Java with -agentpath:<absolute path to the native agent>");
        }

        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch started = new CountDownLatch(1);
        Thread worker = Thread.ofPlatform().daemon(true).name("example-cpu-worker").unstarted(() -> {
            try {
                started.countDown();
                while (running.get()) {
                    // Deliberately ignores interrupt requests for this demonstration.
                    Thread.onSpinWait();
                }
            } finally {
                System.out.println("Worker finally block executed.");
            }
        });
        worker.setUncaughtExceptionHandler((thread, error) -> {
            if (error instanceof JthreadGo.StopRequested) {
                System.out.println("Worker exited with the asynchronous stop signal.");
            } else {
                error.printStackTrace();
            }
        });
        worker.start();
        try {
            if (!started.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Worker did not start");
            }
            worker.interrupt();
            Thread.sleep(250);
            System.out.println("Alive after interrupt: " + worker.isAlive());
            boolean exited = JthreadGo.stopAndWait(worker, Duration.ofSeconds(2));
            System.out.println("Exited after stop request: " + exited + "; state=" + worker.getState());
            if (!exited) {
                throw new IllegalStateException("The stop request did not terminate the worker");
            }
        } finally {
            running.set(false);
            worker.join(Duration.ofSeconds(2));
        }
    }
}
