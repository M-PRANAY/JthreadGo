package org.jthreadgo;

import java.time.Duration;
import java.util.Objects;

/**
 * Experimental JVM-mediated asynchronous abort for platform threads.
 * Requires the JthreadGo native library loaded at JVM startup with -agentpath.
 * This API does not preserve application invariants or guarantee termination.
 */
public final class JthreadGo {
    private JthreadGo() {}

    /**
     * Returns whether this class can reach the initialized startup agent.
     * @return true if the native bridge is bound and the agent is initialized
     */
    public static boolean isAvailable() {
        try {
            return isAgentLoaded0();
        } catch (UnsatisfiedLinkError unavailable) {
            return false;
        }
    }

    /**
     * Requests asynchronous delivery of StopRequested in a live platform thread.
     * A successful return means the request was accepted (or the target exited),
     * not that the thread has terminated. The target or its framework can catch
     * the signal. Native execution can delay delivery indefinitely.
     *
     * @param target the platform thread to signal, in this JVM
     * @throws NullPointerException if target is null
     * @throws UnsupportedOperationException for a virtual thread
     * @throws IllegalThreadStateException for an unstarted thread
     * @throws IllegalStateException if the startup agent is unavailable
     */
    public static void stop(Thread target) {
        Objects.requireNonNull(target, "target");
        if (target.isVirtual()) {
            throw new UnsupportedOperationException("JthreadGo supports platform threads only");
        }
        Thread.State state = target.getState();
        if (state == Thread.State.NEW) {
            throw new IllegalThreadStateException("Cannot stop an unstarted thread");
        }
        if (state == Thread.State.TERMINATED) {
            return;
        }
        if (!isAvailable()) {
            throw new IllegalStateException("JthreadGo agent is unavailable. Start this JVM with "
                    + "-agentpath:<absolute path to the JthreadGo native library> "
                    + "--enable-native-access=ALL-UNNAMED");
        }
        requestStop0(target, new StopRequested());
    }

    /**
     * Requests a stop, then waits up to timeout for thread termination.
     * The timeout bounds only the join phase; it does not bound the preceding
     * JVMTI call. True proves thread exit, not safe cleanup or restored state.
     *
     * @param target the platform thread to signal, different from the caller
     * @param timeout positive maximum duration for the join phase
     * @return true if termination was observed, false if the join timed out
     * @throws InterruptedException if the caller is interrupted while joining
     * @throws NullPointerException if target or timeout is null
     * @throws IllegalArgumentException if timeout is not positive or target is the caller
     * @throws UnsupportedOperationException for a virtual thread
     * @throws IllegalThreadStateException for an unstarted thread
     * @throws IllegalStateException if the startup agent is unavailable
     */
    public static boolean stopAndWait(Thread target, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (target == Thread.currentThread()) {
            throw new IllegalArgumentException("A thread cannot stopAndWait for itself");
        }
        stop(target);
        return target.join(timeout);
    }

    /**
     * Asynchronous abort signal. Code catching Throwable or Error can swallow it.
     * Unlike historical ThreadDeath, the default uncaught handler prints it.
     */
    public static final class StopRequested extends Error {
        private static final long serialVersionUID = 1L;

        private StopRequested() {
            super("Asynchronous thread stop requested", null, false, false);
        }
    }

    private static native boolean isAgentLoaded0();
    private static native void requestStop0(Thread target, Throwable signal);
}
