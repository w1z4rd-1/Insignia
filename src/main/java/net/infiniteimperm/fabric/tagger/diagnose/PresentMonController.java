package net.infiniteimperm.fabric.tagger.diagnose;

import net.infiniteimperm.fabric.tagger.TaggerMod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class PresentMonController {
    private static final int DEFAULT_TIMED_CAPTURE_SECONDS = 120;
    private static final String SESSION_NAME = "InsigniaPresentMon";
    private final ToolDetection.ToolInfo tool;
    private final Path workDir;
    private final Path csvPath;
    private final Path stdoutLog;
    private final Path stderrLog;
    private final int timedCaptureSeconds;
    private Process process;
    private long targetPid = -1L;
    private boolean elevatedTimedMode = false;

    public PresentMonController(ToolDetection.ToolInfo tool, Path workDir, Path csvPath, Path stdoutLog, Path stderrLog, int timedCaptureSeconds) {
        this.tool = tool;
        this.workDir = workDir;
        this.csvPath = csvPath;
        this.stdoutLog = stdoutLog;
        this.stderrLog = stderrLog;
        this.timedCaptureSeconds = timedCaptureSeconds > 0 ? timedCaptureSeconds : DEFAULT_TIMED_CAPTURE_SECONDS;
    }

    public void start() throws Exception {
        if (tool == null || !tool.found || tool.path == null) {
            throw new IllegalStateException("PresentMon is mandatory and not available.");
        }

        targetPid = ProcessHandle.current().pid();
        List<String> command = new ArrayList<>();
        command.add(tool.path.toString());
        command.add("--output_file");
        command.add(csvPath.toAbsolutePath().toString());
        command.add("--stop_existing_session");
        command.add("--delay");
        command.add("2");
        command.add("--timed");
        command.add(Integer.toString(timedCaptureSeconds));
        command.add("--terminate_after_timed");
        command.add("--session_name");
        command.add(SESSION_NAME);
        command.add("--process_id");
        command.add(Long.toString(targetPid));
        command.add("--set_circular_buffer_size");
        command.add("131072");
        command.add("--no_console_stats");

        TaggerMod.LOGGER.info("[Diagnose][PresentMon] Command: {}", command);
        process = ProcessRunner.start(command, workDir, stdoutLog, stderrLog);
        Thread.sleep(750L);
        if (process == null || !process.isAlive()) {
            TaggerMod.LOGGER.warn("[Diagnose][PresentMon] PID targeting failed, retrying with process_name javaw.exe");
            command = new ArrayList<>();
            command.add(tool.path.toString());
            command.add("--output_file");
            command.add(csvPath.toAbsolutePath().toString());
            command.add("--stop_existing_session");
            command.add("--delay");
            command.add("2");
            command.add("--timed");
            command.add(Integer.toString(timedCaptureSeconds));
            command.add("--terminate_after_timed");
            command.add("--session_name");
            command.add(SESSION_NAME);
            command.add("--process_name");
            command.add("javaw.exe");
            command.add("--set_circular_buffer_size");
            command.add("131072");
            command.add("--no_console_stats");
            process = ProcessRunner.start(command, workDir, stdoutLog, stderrLog);
            Thread.sleep(750L);
            if (process == null || !process.isAlive()) {
                String combined = readCombinedLogs();
                if (isAccessDenied(combined)) {
                    TaggerMod.LOGGER.warn("[Diagnose][PresentMon] Access denied on direct launch. Triggering UAC elevation fallback.");
                    ChatUi.warn("PresentMon requires elevation. Approve the UAC prompt to continue diagnostics.");
                    startElevatedTimedCapture();
                    return;
                }
                throw new IllegalStateException("PresentMon process exited immediately after launch. Check presentmon.stderr.log in the run folder.");
            }
        }
    }

    public void stop() throws Exception {
        if (elevatedTimedMode) {
            waitForCsvReady(Duration.ofSeconds(20));
            ensureCsvFromFallback();
            return;
        }
        ProcessRunner.stop(process, Duration.ofSeconds(3));
        ensureCsvFromFallback();
    }

    public Path csvPath() {
        return csvPath;
    }

    public long targetPid() {
        return targetPid;
    }

    private void startElevatedTimedCapture() throws Exception {
        elevatedTimedMode = true;
        process = null;
        List<String> pmArgs = new ArrayList<>();
        pmArgs.add("--output_file");
        pmArgs.add(csvPath.toAbsolutePath().toString());
        pmArgs.add("--stop_existing_session");
        pmArgs.add("--delay");
        pmArgs.add("2");
        pmArgs.add("--timed");
        pmArgs.add(Integer.toString(timedCaptureSeconds));
        pmArgs.add("--terminate_after_timed");
        pmArgs.add("--session_name");
        pmArgs.add(SESSION_NAME);
        pmArgs.add("--process_id");
        pmArgs.add(Long.toString(targetPid));
        pmArgs.add("--set_circular_buffer_size");
        pmArgs.add("131072");
        pmArgs.add("--no_console_stats");

        StringBuilder argList = new StringBuilder();
        for (String arg : pmArgs) {
            if (argList.length() > 0) {
                argList.append(",");
            }
            argList.append("'").append(psEscape(arg)).append("'");
        }

        String script = "$pm='" + psEscape(tool.path.toString()) + "'; "
            + "$a=@(" + argList + "); "
            + "Start-Process -FilePath $pm -ArgumentList $a -Verb RunAs";

        List<String> command = List.of(
            "powershell.exe",
            "-NoProfile",
            "-Command",
            script
        );
        TaggerMod.LOGGER.info("[Diagnose][PresentMon] Elevated fallback command: {}", command);
        ProcessRunner.ProcessResult result = ProcessRunner.run(command, workDir, stdoutLog, stderrLog, Duration.ofSeconds(20), false);
        if (result.timedOut() || result.exitCode() != 0) {
            throw new IllegalStateException("PresentMon UAC launch failed. Approve UAC and retry /diagnose.");
        }
    }

    private boolean isAccessDenied(String combinedLower) {
        return combinedLower.contains("access denied")
            || combinedLower.contains("requires either administrative privileges")
            || combinedLower.contains("performance log users");
    }

    private String readCombinedLogs() {
        String errorLog = readLog(stderrLog);
        String outLog = readLog(stdoutLog);
        return (errorLog + "\n" + outLog).toLowerCase(Locale.ROOT);
    }

    private void waitForCsvReady(Duration timeout) throws Exception {
        long end = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < end) {
            if (Files.exists(csvPath) && Files.size(csvPath) > 0) {
                return;
            }
            Thread.sleep(250L);
        }
    }

    private void ensureCsvFromFallback() throws Exception {
        if (Files.exists(csvPath) && Files.size(csvPath) > 0) {
            return;
        }
        if (!Files.exists(stdoutLog)) {
            throw new IllegalStateException("PresentMon did not create CSV output. stdout log missing.");
        }
        List<String> lines = Files.readAllLines(stdoutLog, StandardCharsets.UTF_8);
        List<String> csvLines = lines.stream()
            .filter(line -> line != null && line.contains(","))
            .filter(line -> !line.startsWith("warning:"))
            .filter(line -> !line.startsWith("Started recording"))
            .filter(line -> !line.startsWith("Stopped recording"))
            .collect(Collectors.toList());
        if (csvLines.isEmpty()) {
            throw new IllegalStateException("PresentMon produced no CSV lines. Check presentmon.stdout.log/presentmon.stderr.log.");
        }
        Files.writeString(csvPath, String.join(System.lineSeparator(), csvLines) + System.lineSeparator(), StandardCharsets.UTF_8);
        TaggerMod.LOGGER.warn("[Diagnose][PresentMon] Reconstructed presentmon.csv from stdout fallback.");
    }

    private String readLog(Path path) {
        try {
            if (!Files.exists(path)) {
                return "";
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private String psEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''");
    }
}
