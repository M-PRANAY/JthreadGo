package org.jthreadgo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each asynchronous exception experiment runs in its own bounded, disposable JVM. */
class JthreadGoTest {
    private static final long PROCESS_TIMEOUT_SECONDS = 30;

    @ParameterizedTest(name = "isolated JVM: {0}")
    @ValueSource(strings = {
        "cpu", "cpu-hot", "monitor", "sleeping", "swallowed", "future",
        "virtual", "new", "terminated", "no-agent", "arguments"
    })
    void isolatedProbe(String probe) throws Exception {
        Path project = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        Path reports = project.resolve("target/probe-reports");
        Files.createDirectories(reports);
        Path log = reports.resolve(probe + ".log");
        Path javaBin = Path.of(System.getProperty("java.home"), "bin");
        Path java = javaBin.resolve(Files.exists(javaBin.resolve("java.exe")) ? "java.exe" : "java");

        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-Xcheck:jni");
        if (!"no-agent".equals(probe)) {
            Path agent = Path.of(System.getProperty("jthreadgo.native.path",
                    project.resolve("target/native/" + System.mapLibraryName("jthreadgo")).toString()))
                    .toAbsolutePath().normalize();
            assertTrue(Files.isRegularFile(agent), "Build the native agent first: " + agent);
            command.add("-agentpath:" + agent);
        }
        command.add("-cp");
        command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(JthreadGoProbe.class.getName());
        command.add(probe);

        Process child = new ProcessBuilder(command)
                .directory(project.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            boolean exited = child.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue(exited, () -> "Probe exceeded " + PROCESS_TIMEOUT_SECONDS
                    + " seconds; see " + log + "\n" + output(log));
            assertEquals(0, child.exitValue(), () -> "Probe failed; see " + log + "\n" + output(log));
            assertTrue(output(log).contains("PASS " + probe + ":"),
                    () -> "Probe exited without its success marker; see " + log + "\n" + output(log));
        } finally {
            // This handle belongs only to the disposable probe created above.
            // No OS thread termination or termination of another application's process occurs.
            if (child.isAlive()) {
                child.destroyForcibly();
                assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Disposable probe did not exit after cleanup");
            }
        }
    }

    private static String output(Path log) {
        try (var stream = Files.newInputStream(log)) {
            return new String(stream.readNBytes(65_536), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            return "Could not read probe log: " + failure;
        }
    }
}
