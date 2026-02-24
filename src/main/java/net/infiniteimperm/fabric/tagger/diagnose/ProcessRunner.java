package net.infiniteimperm.fabric.tagger.diagnose;

import net.infiniteimperm.fabric.tagger.TaggerMod;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ProcessRunner {
    private ProcessRunner() {
    }

    public static ProcessResult run(List<String> command, Path workDir, Path stdoutLog, Path stderrLog, Duration timeout) throws IOException, InterruptedException {
        return run(command, workDir, stdoutLog, stderrLog, timeout, true);
    }

    public static ProcessResult run(List<String> command, Path workDir, Path stdoutLog, Path stderrLog, Duration timeout, boolean warnOnTimeout) throws IOException, InterruptedException {
        validateLogTargets(stdoutLog, stderrLog);
        TaggerMod.LOGGER.info("[Diagnose][Process] Running command: {}", command);
        Files.createDirectories(stdoutLog.getParent());
        Files.createDirectories(stderrLog.getParent());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        Process process = pb.start();
        Instant start = Instant.now();

        Thread outThread = pump(process.getInputStream(), stdoutLog);
        Thread errThread = pump(process.getErrorStream(), stderrLog);

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            if (warnOnTimeout) {
                TaggerMod.LOGGER.warn("[Diagnose][Process] Timeout reached, killing: {}", command);
            } else {
                TaggerMod.LOGGER.info("[Diagnose][Process] Timeout reached (non-fatal), killing: {}", command);
            }
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
        outThread.join(1500);
        errThread.join(1500);

        int exitCode = finished ? process.exitValue() : -1;
        Instant end = Instant.now();
        return new ProcessResult(exitCode, !finished, Duration.between(start, end));
    }

    public static Process start(List<String> command, Path workDir, Path stdoutLog, Path stderrLog) throws IOException {
        validateLogTargets(stdoutLog, stderrLog);
        TaggerMod.LOGGER.info("[Diagnose][Process] Starting process: {}", command);
        Files.createDirectories(stdoutLog.getParent());
        Files.createDirectories(stderrLog.getParent());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        Process process = pb.start();
        pump(process.getInputStream(), stdoutLog);
        pump(process.getErrorStream(), stderrLog);
        return process;
    }

    public static void stop(Process process, Duration gracefulWait) throws InterruptedException {
        if (process == null) {
            return;
        }
        process.destroy();
        if (!process.waitFor(gracefulWait.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        }
    }

    private static Thread pump(java.io.InputStream in, Path target) {
        Thread thread = new Thread(() -> {
            try (in; OutputStream out = Files.newOutputStream(
                target,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE)) {
                in.transferTo(out);
            } catch (IOException e) {
                TaggerMod.LOGGER.error("[Diagnose][Process] Stream pump failure {}", target, e);
            }
        }, "insignia-process-pump");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    public record ProcessResult(int exitCode, boolean timedOut, Duration elapsed) {
    }

    private static void validateLogTargets(Path stdoutLog, Path stderrLog) {
        try {
            if (stdoutLog.toAbsolutePath().normalize().equals(stderrLog.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("stdout and stderr log paths must be different");
            }
        } catch (Exception ignored) {
            if (stdoutLog.equals(stderrLog)) {
                throw new IllegalArgumentException("stdout and stderr log paths must be different");
            }
        }
    }
}
