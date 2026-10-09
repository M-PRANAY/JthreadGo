# Building and sharing

JthreadGo is a source repository and a local Maven artifact. It has not been published to a public Maven repository.

## Build outputs

`mvn verify` produces:

- `target/jthreadgo-0.1.0-SNAPSHOT.jar`: Java API, with no Java runtime dependencies.
- `target/jthreadgo-0.1.0-SNAPSHOT-sources.jar`: Java sources.
- `target/jthreadgo-0.1.0-SNAPSHOT-javadoc.jar`: API documentation.
- `target/native/jthreadgo.dll`, `libjthreadgo.so`, or `libjthreadgo.dylib`: the agent for the build machine.

The native binary must match the application's operating system and architecture. The Java JAR does not extract or load an agent automatically; the application uses `-agentpath` at startup.

## Maven installation

`mvn install` installs the Java artifact, sources, Javadocs, and the native agent into your local Maven repository. The agent is attached with an OS/architecture classifier and its native extension. For example, a Windows x64 build normally installs:

```text
org.jthreadgo:jthreadgo:jar:0.1.0-SNAPSHOT
org.jthreadgo:jthreadgo:dll:windows-amd64:0.1.0-SNAPSHOT
```

Linux uses `linux-${os.arch}` and `so`; macOS uses `macos-${os.arch}` and `dylib`. The architecture value comes from the JDK used by Maven. Windows builds currently require x64.

Installing locally does not make the artifact available to other developers. They can clone this repository and run `mvn install`, or obtain the artifacts from a repository your team configures. Public Maven publication would require a verified namespace, release metadata, and publishing credentials; none are embedded here.

## Share the repository

Share the Git repository so recipients get the Java API, native source, build scripts, examples, tests, and MIT license. The `.gitignore` excludes generated output, JVM crash reports, IDE settings, and `.local/` scratch material. Do not distribute a ZIP of the entire working directory, which may include those local files.

For a binary distribution, include the JAR, matching native library, README, documentation, and LICENSE. Describe the JDK, operating system, and architecture used to build and test it.

The GitHub Actions workflow is configured to build and test Windows, Linux, and macOS with JDK 25. Workflow configuration alone does not establish a successful run on those platforms.
