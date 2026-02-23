package net.infiniteimperm.fabric.tagger.diagnose;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessRunnerTest {
    @TempDir
    Path tempDir;

    @Test
    void capturesStdoutAndStderrToSeparateFiles() throws Exception {
        Path stdout = tempDir.resolve("tool.stdout.log");
        Path stderr = tempDir.resolve("tool.stderr.log");
        List<String> cmd = List.of("cmd.exe", "/c", "echo hello-out & echo hello-err 1>&2");

        ProcessRunner.ProcessResult result = ProcessRunner.run(cmd, tempDir, stdout, stderr, Duration.ofSeconds(5));
        assertEquals(0, result.exitCode());
        String out = Files.readString(stdout, StandardCharsets.UTF_8);
        String err = Files.readString(stderr, StandardCharsets.UTF_8);
        assertTrue(out.toLowerCase().contains("hello-out"));
        assertTrue(err.toLowerCase().contains("hello-err"));
    }

    @Test
    void rejectsSameFileForStdoutAndStderr() {
        Path log = tempDir.resolve("same.log");
        List<String> cmd = List.of("cmd.exe", "/c", "echo x");
        assertThrows(IllegalArgumentException.class, () ->
            ProcessRunner.run(cmd, tempDir, log, log, Duration.ofSeconds(1)));
    }

    @Test
    void startAlsoRejectsSameFileForStdoutAndStderr() {
        Path log = tempDir.resolve("same-start.log");
        List<String> cmd = List.of("cmd.exe", "/c", "echo x");
        assertThrows(IllegalArgumentException.class, () ->
            ProcessRunner.start(cmd, tempDir, log, log));
    }
}
