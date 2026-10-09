# Architecture

JthreadGo has two parts: a Java API and a native JVMTI agent loaded into the same JVM at startup.

```text
Application thread
    |
    | JthreadGo.stop(target)
    v
Java validation + new StopRequested()
    |
    | JNI
    v
Native bridge -> JVMTI StopThread(target, signal)
    |
    v
JVM arranges asynchronous exception delivery
    |
    v
Target catches the signal or unwinds
    |
    v
If it escapes the thread boundary, the JVM processes thread exit
```

## Startup and native lookup

The launcher loads the native library through `-agentpath`. `Agent_OnLoad` obtains a JVMTI environment, requests the optional `can_signal_thread` capability, and verifies that the capability is available. Initialization failure prevents the agent from being treated as ready.

JVMTI agent libraries participate in JNI native-method lookup. The Java class calls native methods exported by the agent; application code does not need reflection or `--add-opens` for this bridge.

The Java JAR and the native library must have matching JNI names. Build and deploy them together. A native library must also match the application's operating system and architecture.

## Requesting a stop

Java validates the target and checks agent availability. The native bridge calls JVMTI `StopThread` with the target and a custom Java error. A target that has already exited is handled as a no-op.

JVMTI acceptance indicates that a request was made. It does not guarantee an immediate exception, successful cleanup, or eventual termination. Arbitrary native execution can delay delivery until the JVM can process the pending exception.

The use of `StopRequested extends Error` avoids a dependency on the historical `ThreadDeath` signal. It preserves the possibility that application or framework code catches the signal.

## Scope

- Java 25 platform threads in a JVM with the required JVMTI capability.
- Startup loading of the agent; no remote control endpoint or dynamic attach workflow.
- Java references to target threads in the same JVM.
- No Windows `TerminateThread`, native thread-ID lookup, or process termination in the library.

The native bridge leaves thread lifecycle bookkeeping to the JVM. Asynchronous exception delivery can still break application invariants: releasing a monitor does not repair the partially updated state that monitor protects.

## Primary references

- [Java 25 JVMTI: StopThread](https://docs.oracle.com/en/java/javase/25/docs/specs/jvmti.html#StopThread) describes the required capability and asynchronous exception request.
- [Java 25 JVMTI: agent startup](https://docs.oracle.com/en/java/javase/25/docs/specs/jvmti.html#starting) describes loading agents with JVM startup options.
- [Java 25 Thread API: stop](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Thread.html#stop()) documents the disabled Java API.
- [Java 25 Thread API: join(Duration)](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Thread.html#join(java.time.Duration)) describes the bounded wait used by `stopAndWait`.
- [OpenJDK 25 HotSpot JavaThread implementation](https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/hotspot/share/runtime/javaThread.cpp) provides implementation context for exception delivery and platform-thread interruption.
