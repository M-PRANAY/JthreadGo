# JthreadGo

An experimental asynchronous stop API for Java 25 platform threads, backed by a small JVMTI startup agent.

```java
import org.jthreadgo.JthreadGo;

JthreadGo.stop(workerThread);
```

The JVM injects `JthreadGo.StopRequested`, an `Error`, into the target thread. This can stop Java code that ignores interruption, including a running CPU loop. It also inherits the hazards of the historical `Thread.stop()` mechanism: application state can be left inconsistent, and code can catch the signal and continue.

**A successful stop request does not guarantee termination.** Use process isolation when uncooperative work must be terminated while preserving the parent application's health.

## Requirements

- JDK 25, with `JAVA_HOME` pointing to that JDK.
- Maven 3.9 or later.
- A C++17 compiler: Visual Studio Build Tools with the x64 C++ workload on Windows, or GCC/Clang on Linux and macOS. The Unix build also uses Bash.
- A JVM providing the JVMTI `can_signal_thread` capability. The Java API and agent must run in the same JVM.

The native library is built for the local operating system and architecture. The Windows build currently targets x64 JDKs. Virtual threads and GraalVM Native Image are outside this implementation's scope. Other JDK versions require separate validation.

## Build and run the example

Run these commands from the `JthreadGo` repository root:

```text
mvn verify
javac -cp target/jthreadgo-0.1.0-SNAPSHOT.jar -d target/examples examples/StopCpuWorker.java
```

`mvn verify` compiles Java and the native agent, then runs the test suite. Tests create their own disposable JVMs. See [testing](docs/TESTING.md) for what they check and the limits of those checks.

### Windows PowerShell

```powershell
$agentPath = (Resolve-Path 'target/native/jthreadgo.dll').Path
java --enable-native-access=ALL-UNNAMED "-agentpath:$agentPath" `
  -cp 'target/jthreadgo-0.1.0-SNAPSHOT.jar;target/examples' StopCpuWorker
```

### Linux

```sh
java --enable-native-access=ALL-UNNAMED \
  "-agentpath:$PWD/target/native/libjthreadgo.so" \
  -cp 'target/jthreadgo-0.1.0-SNAPSHOT.jar:target/examples' StopCpuWorker
```

On macOS, use `target/native/libjthreadgo.dylib` for the agent path.

The example requests a stop of its own CPU worker and reports whether the worker exits. Compiler support for an operating system is separate from verification on that operating system; consult the results of your local test run.

PowerShell users can also run `./build.ps1` for `mvn verify`, or `./test.ps1` for `mvn test`. Both wrappers accept `-JdkHome` and `-Offline`; the build wrapper also accepts `-SkipTests`.

## Use in an application

### 1. Install the Java artifact locally

```text
mvn install
```

Then add this dependency to the consuming application's `pom.xml`:

```xml
<dependency>
    <groupId>org.jthreadgo</groupId>
    <artifactId>jthreadgo</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

These are local development coordinates. The artifact has not been published to Maven Central. The native library is a separate build output and must also be available on the application machine.

### 2. Configure the application's JVM

Add these options to the JVM that runs your application:

```text
-agentpath:<absolute path to the built native library>
--enable-native-access=ALL-UNNAMED
```

| Operating system | Native build output |
| --- | --- |
| Windows | `target/native/jthreadgo.dll` |
| Linux | `target/native/libjthreadgo.so` |
| macOS | `target/native/libjthreadgo.dylib` |

In IntelliJ IDEA, place the options in **Run/Debug Configurations → your application → VM options**, then restart that application. Setting options only on Maven or the IDE's own JVM does not load the agent into the application. Quote the complete `-agentpath:...` argument when its path contains spaces.

The examples use the classpath. If the JAR is placed on the module path as the automatic module `org.jthreadgo`, use `--enable-native-access=org.jthreadgo`.

### 3. Pass the target `Thread`

```java
import org.jthreadgo.JthreadGo;

if (!JthreadGo.isAvailable()) {
    throw new IllegalStateException("Start this application with the JthreadGo agent");
}

JthreadGo.stop(workerThread);
```

To request a stop and observe thread exit in one call:

```java
import java.time.Duration;
import org.jthreadgo.JthreadGo;

// The enclosing method must handle or declare InterruptedException.
boolean exited = JthreadGo.stopAndWait(workerThread, Duration.ofSeconds(2));
```

The timeout limits the join **after the native stop request returns**. It does not impose a deadline on the native call. `true` establishes thread exit; `false` does not retract the stop request.

The argument must be a `Thread` object from this JVM. `ExecutorService`, `Future`, and operating-system thread IDs cannot be passed to this API. With executors, a `FutureTask` can catch the injected error, fail the current task, and leave its worker thread alive. See [API behavior](docs/API.md) before integrating it into a task scheduler.

## Important limits

- **No rollback:** shared objects can remain partly updated, and external writes may already have happened.
- **No termination guarantee:** `catch (Error)` and `catch (Throwable)` can catch the stop signal. Cleanup can block or loop.
- **Native execution can delay delivery:** the agent cannot promise to stop arbitrary JNI code or an operating-system call immediately.
- **No task-boundary guarantee:** a worker may finish one task and start another before the exception arrives.
- **Cleanup is vulnerable:** repeated stop requests can interrupt cleanup itself. Explicit locks require correctly completed cleanup.

The JVM handles exception delivery and thread exit. This mechanism uses JVMTI rather than an operating-system thread termination API. That JVM involvement does not make asynchronous stopping safe for arbitrary application code.

## Project guide

- [API contract and executor behavior](docs/API.md)
- [Agent architecture and references](docs/ARCHITECTURE.md)
- [Tests and interpreting results](docs/TESTING.md)
- [Migration from the original prototype](docs/MIGRATION.md)
- [Artifacts and sharing](docs/DISTRIBUTION.md)
- [Contributing](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)

## License

JthreadGo is available under the [MIT License](LICENSE).
