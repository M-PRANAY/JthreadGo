# Migration from the original prototype

JthreadGo renames the standalone `lab.threadstop` prototype. Rebuild both the Java artifact and native agent; their JNI entry-point names must match.

| Original prototype | JthreadGo |
| --- | --- |
| `lab.threadstop.ThreadStop` | `org.jthreadgo.JthreadGo` |
| `ThreadStop.StopRequested` | `JthreadGo.StopRequested` |
| `lab.threadstop:threadstop:0.1.0` | `org.jthreadgo:jthreadgo:0.1.0-SNAPSHOT` |
| `build/threadstop.jar` | `target/jthreadgo-0.1.0-SNAPSHOT.jar` |
| `build/threadstop.dll` | `target/native/jthreadgo.dll` on Windows |

## Update application code

```java
import org.jthreadgo.JthreadGo;

JthreadGo.stop(workerThread);
```

The method names remain `isAvailable`, `stop`, and `stopAndWait`. Update any explicit catch blocks or uncaught-exception handlers that refer to the old nested signal class.

## Update the build and JVM options

1. Run `mvn install` in this repository to build and install the new Java artifact locally.
2. Replace the old Maven dependency or manually configured JAR in the application.
3. Point the application's `-agentpath` option at the newly built native library.
4. Keep the required native-access grant and restart the application JVM.

The new coordinates describe a local, unpublished artifact. Merely updating the dependency declaration will not download it from Maven Central.

The rename does not change the fundamental asynchronous-stop limitations. `stop` still accepts a Java `Thread`, and an executor worker can survive an injected error caught by its task wrapper.
