package net.infiniteimperm.fabric.tagger.diagnose;

import net.infiniteimperm.fabric.tagger.TaggerMod;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class TypeperfController {
    private final ToolDetection.ToolInfo tool;
    private final Path workDir;
    private final Path csvPath;
    private final Path stdoutLog;
    private final Path stderrLog;
    private Process process;

    public TypeperfController(ToolDetection.ToolInfo tool, Path workDir, Path csvPath, Path stdoutLog, Path stderrLog) {
        this.tool = tool;
        this.workDir = workDir;
        this.csvPath = csvPath;
        this.stdoutLog = stdoutLog;
        this.stderrLog = stderrLog;
    }

    public boolean start(List<String> notes) {
        if (tool == null || !tool.found || tool.path == null) {
            notes.add("typeperf not available.");
            return false;
        }
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(tool.path.toString());
            cmd.add("\\Processor(_Total)\\% Processor Time");
            cmd.add("\\Memory\\Available MBytes");
            cmd.add("\\GPU Engine(*)\\Utilization Percentage");
            cmd.add("\\PhysicalDisk(_Total)\\Avg. Disk Queue Length");
            cmd.add("\\Network Interface(*)\\Bytes Total/sec");
            cmd.add("-si");
            cmd.add("1");
            cmd.add("-f");
            cmd.add("CSV");
            cmd.add("-o");
            cmd.add(csvPath.toAbsolutePath().toString());
            TaggerMod.LOGGER.info("[Diagnose][typeperf] Command: {}", cmd);
            process = ProcessRunner.start(cmd, workDir, stdoutLog, stderrLog);
            Thread.sleep(500L);
            if (process == null || !process.isAlive()) {
                notes.add("typeperf exited immediately.");
                return false;
            }
            notes.add("typeperf capture started.");
            return true;
        } catch (Exception e) {
            notes.add("typeperf start failed: " + e.getMessage());
            TaggerMod.LOGGER.warn("[Diagnose][typeperf] start failed", e);
            return false;
        }
    }

    public void stop(List<String> notes) {
        try {
            ProcessRunner.stop(process, Duration.ofSeconds(4));
            notes.add("typeperf capture stopped.");
        } catch (Exception e) {
            notes.add("typeperf stop failed: " + e.getMessage());
            TaggerMod.LOGGER.warn("[Diagnose][typeperf] stop failed", e);
        }
    }
}
