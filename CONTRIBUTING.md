# Contributing to JthreadGo

JthreadGo is an experimental Java 25 API and JVMTI agent. Changes should keep its behavior and limits explicit.

## Development setup

Install JDK 25, Maven 3.9 or later, and a native compiler for your operating system. Set `JAVA_HOME`, then run:

```text
mvn verify
```

See the [README](README.md) for native compiler requirements and application launch options, and [testing](docs/TESTING.md) for the test model.

## Making changes

- Keep Java native declarations, generated JNI headers, and native exports consistent.
- Keep the Java API independent of a particular application, executor implementation, or operating-system thread ID.
- Add isolated child-JVM coverage when changing exception delivery, lifecycle validation, or termination observation.
- Give asynchronous tests bounded waits and a parent-process deadline.
- Distinguish request acceptance, task completion, and thread exit in code, tests, and documentation.
- Update documentation when changing the API, build artifacts, startup options, or supported configurations.

Tests must operate only on threads and child processes created by the test harness. Preserve useful child output when a test fails.

## Reporting issues and proposing changes

Provide a minimal reproducible case plus the JDK, operating system, architecture, compiler, exact command, and relevant output. Redact secrets and machine-specific details before sharing logs.

For a proposed change, describe the behavior it changes, why it is needed, the checks run, and the platforms actually verified. A build configuration for a platform is not evidence of a successful test run there.

## License and release status

The project uses the [MIT License](LICENSE). Keep the license and copyright notice with redistributed copies as required by that license.

The development coordinates are `org.jthreadgo:jthreadgo:0.1.0-SNAPSHOT`. Documentation must not imply Maven Central publication or successful hosted CI until those actions have actually occurred.
