# Testing

Run the full build and verification from the repository root:

```text
mvn verify
```

`org.jthreadgo.JthreadGoTest` uses JUnit to launch eleven disposable child JVMs, each with a 30-second deadline, for the asynchronous-stop scenarios. Each child exercises threads it creates and runs with `-Xcheck:jni`. A child-process timeout is a test failure; the harness terminates that child so a swallowed signal or hung cleanup does not hang the build indefinitely.

The application under development does not need to be running. Do not point the probes at a business application.

## What the scenarios check

| Scenario | Intended observation |
| --- | --- |
| CPU loop | A platform thread doing Java work can receive the signal even when its loop ignores interruption. |
| Warmed CPU loop | The same behavior is exercised after a warmup period; the test does not establish a particular compilation tier. |
| Monitor and shared state | Exception unwinding releases an intrinsic monitor while partially updated state remains visible. |
| Sleeping thread | The signal can stop the tested sleeping platform thread. |
| Caught signal | A worker can catch `StopRequested` and continue, so termination is not guaranteed. |
| Executor task | `FutureTask` can record the error while the executor worker remains available. |
| Virtual thread | The API rejects virtual threads. |
| Unstarted thread | The API rejects a platform thread in `NEW` state. |
| Terminated thread | Stopping an already exited platform thread is a no-op. |
| Missing agent | Availability checks and attempts to stop a live thread report the missing startup agent. |
| Invalid arguments | Null inputs, nonpositive timeouts, and waiting for the calling thread are rejected. |

These scenarios demonstrate behavior under their specific synchronization and timing conditions. Passing them does not prove safety for arbitrary frameworks, native code, JVM implementations, or JDK versions.

## Reports and configuration

The harness writes captured child-process output to `target/probe-reports`. Use these logs alongside Maven's test reports when investigating a failure. A `PASS` message from one child establishes only that scenario's assertions. Verification succeeds only when the complete Maven run succeeds.

By default the harness uses the agent produced by the build under `target/native/`. To exercise a particular compatible agent binary, set the optional `jthreadgo.native.path` property to its absolute path:

```text
mvn verify -Djthreadgo.native.path=<absolute path to native library>
```

Quote the property argument if the path contains spaces. The selected binary must match the Java API's JNI names, the JDK, the operating system, and the architecture.

When sharing a result, include:

- Operating system and architecture.
- `java -version` and `mvn -version` output.
- Native compiler and version.
- The command used and relevant test output.
- Whether the failure occurred at native compilation, agent startup, API validation, or exception delivery.

Remove machine-specific paths and sensitive environment details from public reports.

## Gaps and limits

The initial repository build was verified locally on 2026-10-09 with Windows x64 and Oracle GraalVM 25.0.3: all 11 isolated probes passed, and both example programs produced their expected results. A fresh build from the final repository directory also passed. Linux and macOS are configured in CI but were not executed during this local verification.

The suite does not establish that arbitrary JNI loops, native libraries, or blocking operating-system calls can be stopped promptly. A successful sleep test does not cover all native waits. It also does not establish application rollback, safe repeated stops, or precise selection of an executor task during task transitions.

Operating-system build branches require verification on their respective systems. Do not infer a Linux or macOS test result from a Windows run, or infer support for a later JDK from a Java 25 run.
