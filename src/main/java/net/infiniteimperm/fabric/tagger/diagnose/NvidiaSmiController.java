package net.infiniteimperm.fabric.tagger.diagnose;

import net.infiniteimperm.fabric.tagger.TaggerMod;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class NvidiaSmiController {
    private final ToolDetection.ToolInfo tool;
    private final Path workDir;
    private final Path stdoutLog;
    private final Path stderrLog;
    private Process process;

    public NvidiaSmiController(ToolDetection.ToolInfo tool, Path workDir, Path stdoutLog, Path stderrLog) {
        this.tool = tool;
        this.workDir = workDir;
        this.stdoutLog = stdoutLog;
        this.stderrLog = stderrLog;
    }

    public boolean start(List<String> notes) {
        if (tool == null || !tool.found || tool.path == null) {
            notes.add("nvidia-smi not available.");
            return false;
        }
        try {
            List<String> cmd = List.of(
                tool.path.toString(),
                "--query-gpu=timestamp,index,name,utilization.gpu,utilization.memory,memory.used,memory.total,temperature.gpu,power.draw,clocks.current.graphics,clocks.current.memory",
                "--format=csv,noheader,nounits",
                "-l",
                "1"
            );
            TaggerMod.LOGGER.info("[Diagnose][nvidia-smi] Command: {}", cmd);
            process = ProcessRunner.start(cmd, workDir, stdoutLog, stderrLog);
            Thread.sleep(300L);
            if (!process.isAlive()) {
                notes.add("nvidia-smi exited immediately.");
                return false;
            }
            notes.add("nvidia-smi capture started.");
            return true;
        } catch (Exception e) {
            notes.add("nvidia-smi start failed: " + e.getMessage());
            TaggerMod.LOGGER.warn("[Diagnose][nvidia-smi] start failed", e);
            return false;
        }
    }

    public void stop(List<String> notes) {
        try {
            ProcessRunner.stop(process, Duration.ofSeconds(3));
            notes.add("nvidia-smi capture stopped.");
        } catch (Exception e) {
            notes.add("nvidia-smi stop failed: " + e.getMessage());
            TaggerMod.LOGGER.warn("[Diagnose][nvidia-smi] stop failed", e);
        }
    }
}
