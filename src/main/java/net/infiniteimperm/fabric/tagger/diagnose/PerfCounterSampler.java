package net.infiniteimperm.fabric.tagger.diagnose;

import com.sun.management.OperatingSystemMXBean;
import net.infiniteimperm.fabric.tagger.TaggerMod;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PerfCounterSampler {
    private final Path csvPath;
    private final Path logPath;
    private final long sampleIntervalMs;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread thread;

    public PerfCounterSampler(Path csvPath, Path logPath, long sampleIntervalMs) {
        this.csvPath = csvPath;
        this.logPath = logPath;
        this.sampleIntervalMs = sampleIntervalMs;
    }

    public void start() {
        try {
            Files.createDirectories(csvPath.getParent());
            Files.createDirectories(logPath.getParent());
            Files.writeString(csvPath, "ts,processCpuLoad,systemCpuLoad,freePhysicalMb,totalPhysicalMb,freeSwapMb,totalSwapMb\n", StandardCharsets.UTF_8);
            Files.writeString(logPath, "[" + Instant.now() + "] starting perf sampler\n", StandardCharsets.UTF_8);
        } catch (Exception e) {
            TaggerMod.LOGGER.warn("[Diagnose][PerfCounters] Failed to initialize sampler files", e);
            return;
        }

        running.set(true);
        thread = new Thread(this::runLoop, "insignia-perf-counter-sampler");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running.set(false);
        if (thread != null) {
            try {
                thread.join(1200L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void runLoop() {
        OperatingSystemMXBean os;
        try {
            os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        } catch (Exception e) {
            appendLog("No OperatingSystemMXBean available: " + e.getMessage());
            return;
        }
        appendLog("Sampling started at interval " + sampleIntervalMs + "ms");
        while (running.get()) {
            long ts = System.currentTimeMillis();
            double proc = os.getProcessCpuLoad();
            double sys = os.getCpuLoad();
            long freePhys = os.getFreeMemorySize() / (1024L * 1024L);
            long totalPhys = os.getTotalMemorySize() / (1024L * 1024L);
            long freeSwap = os.getFreeSwapSpaceSize() / (1024L * 1024L);
            long totalSwap = os.getTotalSwapSpaceSize() / (1024L * 1024L);
            String line = String.format(Locale.ROOT, "%d,%.6f,%.6f,%d,%d,%d,%d%n", ts, proc, sys, freePhys, totalPhys, freeSwap, totalSwap);
            try {
                Files.writeString(csvPath, line, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception e) {
                appendLog("Sample write failed: " + e.getMessage());
            }
            try {
                Thread.sleep(sampleIntervalMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        appendLog("Sampling stopped.");
    }

    private void appendLog(String line) {
        try {
            Files.writeString(logPath, "[" + Instant.now() + "] " + line + "\n", StandardCharsets.UTF_8,
                Files.exists(logPath) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE);
        } catch (Exception e) {
            TaggerMod.LOGGER.warn("[Diagnose][PerfCounters] Failed writing log", e);
        }
    }
}

