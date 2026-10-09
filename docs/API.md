# API contract

The entry point is `org.jthreadgo.JthreadGo`. Load the matching native agent at JVM startup using `-agentpath` and grant native access to the module containing this class. Classpath applications use `--enable-native-access=ALL-UNNAMED`.

## `isAvailable()`

```java
boolean available = JthreadGo.isAvailable();
```

Returns whether this Java class can reach the initialized startup agent. This is useful for checking application configuration before accepting work. It does not establish that any particular thread can be stopped successfully.

## `stop(Thread target)`

```java
JthreadGo.stop(target);
```

Requests asynchronous delivery of a new `JthreadGo.StopRequested` error to a live platform thread. Normal return means that the request was accepted, or that the target had already exited. Delivery and termination can occur later.

| Target or configuration | Behavior |
| --- | --- |
| `null` | `NullPointerException` |
| Virtual thread | `UnsupportedOperationException` |
| Unstarted platform thread (`NEW`) | `IllegalThreadStateException` |
| Terminated platform thread | No-op |
| Live platform thread with unavailable agent | `IllegalStateException` |
| Live platform thread with available agent | Requests asynchronous exception delivery |

The native bridge also handles a target that exits between validation and the native request. Other JVMTI failures are reported as Java exceptions.

## `stopAndWait(Thread target, Duration timeout)`

```java
boolean exited = JthreadGo.stopAndWait(target, Duration.ofSeconds(2));
```

Calls `stop`, then joins the target for up to the supplied duration. It returns `true` if the thread has terminated. The method throws `InterruptedException` when its caller is interrupted while waiting.

Additional validation:

| Input | Behavior |
| --- | --- |
| Null target or timeout | `NullPointerException` |
| Zero or negative timeout | `IllegalArgumentException` |
| Calling thread as target | `IllegalArgumentException` |

The timeout applies only to the join phase. The native stop request can itself take time. A `false` result does not retract the request; a `true` result does not prove that cleanup completed correctly or that the stop request caused the exit.

## The injected signal

`JthreadGo.StopRequested` is a public nested final subtype of `Error`. Its instances are created by the library.

- A `catch (Exception)` block does not catch this error.
- A matching `catch (JthreadGo.StopRequested)`, `catch (Error)`, or `catch (Throwable)` can catch it.
- Ordinary Java exception unwinding executes applicable `finally` blocks and releases intrinsic monitors as their scopes unwind. Cleanup can fail or never finish.
- The default uncaught-exception handler can print the error. This differs from the special treatment of historical `ThreadDeath`.

Exception delivery can also affect the target's interrupt status. Applications should not rely on that status remaining unchanged.

## Executors and task identity

An `ExecutorService` manages workers and tasks. It is not a `Thread`, so this call does not compile:

```java
JthreadGo.stop(executorService);
```

A `ThreadFactory` can retain the platform `Thread` objects that an executor creates, or a running task can publish its own `Thread.currentThread()` reference. These approaches identify a worker. They do not guarantee which task is running when the asynchronous exception arrives.

In particular, tasks submitted through `ExecutorService.submit` are commonly wrapped in `FutureTask`. That wrapper catches `Throwable`, records the task's failure, and allows the worker to execute another task. Consequently:

- An exceptional `Future` does not imply that its worker exited.
- `stopAndWait` on that worker may return `false` even after the task failed.
- Cancelling a `Future` changes its result state; it does not prove the task's code stopped executing.
- Timing out a queued task must not be treated as proof that the worker is running that task.

The API has no job identifier, queue management, retry policy, or executor replacement behavior. Applications must design those policies around their own task lifecycle.
