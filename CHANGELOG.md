# Changelog

## Unreleased

### Changed

- Renamed the Java entry point from `lab.threadstop.ThreadStop` to `org.jthreadgo.JthreadGo`.
- Adopted the local development coordinates `org.jthreadgo:jthreadgo:0.1.0-SNAPSHOT`.
- Moved generated artifacts to Maven's `target/` directory, with native libraries under `target/native/`.

### Added

- A Maven build for the Java API, native agent, and isolated child-JVM verification.
- Native build paths for MSVC on Windows and GCC/Clang on Linux and macOS.
- A standalone CPU-worker example and documentation covering API behavior, architecture, testing, and migration.
- The MIT License.

### Continuing limitations

- Supports Java 25 platform threads with the required JVMTI capability.
- Asynchronous stopping can leave inconsistent state and does not guarantee termination.
- Native execution can delay exception delivery; executor task wrappers can catch the signal.
- The development artifact is unpublished.
