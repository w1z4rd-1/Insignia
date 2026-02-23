package net.infiniteimperm.fabric.tagger.diagnose;

import net.infiniteimperm.fabric.tagger.TaggerMod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ProcessContentionSampler {
    private final Path outCsv;
    private final Path logFile;
    private final long intervalMs;
    private final int topN;
    private volatile boolean running;
    private Thread thread;

    public ProcessContentionSampler(Path outCsv, Path logFile, long intervalMs, int topN) {
        this.outCsv = outCsv;
        this.logFile = logFile;
        this.intervalMs = intervalMs;
        this.topN = topN;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::runLoop, "insignia-proc-contention");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread == null) {
            return;
        }
        try {
            thread.join(2000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void runLoop() {
        try {
            Files.createDirectories(outCsv.getParent());
            Files.createDirectories(logFile.getParent());
            Files.writeString(
                outCsv,
                "timestamp,pid,process,cpu_delta_ms,total_cpu_ms\n",
                StandardCharsets.UTF_8);
            appendLog("Process contention sampler started. intervalMs=" + intervalMs + " topN=" + topN);
            long selfPid = ProcessHandle.current().pid();
            Map<Long, Long> prevCpuByPid = new HashMap<>();
            snapshotCpu(prevCpuByPid);
            while (running) {
                Thread.sleep(intervalMs);
                List<Sample> top = sampleTop(prevCpuByPid, selfPid);
                String ts = Instant.now().toString();
                StringBuilder sb = new StringBuilder();
                for (Sample sample : top) {
                    sb.append(ts).append(',')
                        .append(sample.pid).append(',')
                        .append(escapeCsv(sample.name)).append(',')
                        .append(sample.deltaCpuMs).append(',')
                        .append(sample.totalCpuMs).append('\n');
                }
                if (!top.isEmpty()) {
                    Files.writeString(outCsv, sb.toString(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
                }
            }
            appendLog("Process contention sampler stopped.");
        } catch (Exception e) {
            TaggerMod.LOGGER.warn("[Diagnose][ProcContention] sampler failure", e);
            appendLog("Sampler failed: " + e.getMessage());
        }
    }

    private List<Sample> sampleTop(Map<Long, Long> prevCpuByPid, long selfPid) {
        Map<Long, Long> nowCpuByPid = new HashMap<>();
        List<Sample> candidates = new ArrayList<>();
        for (ProcessHandle handle : ProcessHandle.allProcesses().toList()) {
            try {
                long pid = handle.pid();
                long nowMs = cpuMillis(handle.info().totalCpuDuration());
                if (nowMs < 0L) {
                    continue;
                }
                nowCpuByPid.put(pid, nowMs);
                long prevMs = prevCpuByPid.getOrDefault(pid, nowMs);
                long delta = nowMs - prevMs;
                if (delta <= 0L || pid == selfPid) {
                    continue;
                }
                String name = processName(handle);
                candidates.add(new Sample(pid, name, delta, nowMs));
            } catch (Exception e) {
                appendLog("Skipping process due to sampling error: " + e.getClass().getSimpleName());
            }
        }
        prevCpuByPid.clear();
        prevCpuByPid.putAll(nowCpuByPid);
        candidates.sort(Comparator.comparingLong((Sample s) -> s.deltaCpuMs).reversed());
        if (candidates.size() > topN) {
            return new ArrayList<>(candidates.subList(0, topN));
        }
        return candidates;
    }

    private void snapshotCpu(Map<Long, Long> map) {
        for (ProcessHandle handle : ProcessHandle.allProcesses().toList()) {
            long ms = cpuMillis(handle.info().totalCpuDuration());
            if (ms >= 0L) {
                map.put(handle.pid(), ms);
            }
        }
    }

    private long cpuMillis(java.util.Optional<Duration> duration) {
        return duration.map(Duration::toMillis).orElse(-1L);
    }

    private String processName(ProcessHandle handle) {
        String command = handle.info().command().orElse("unknown");
        int slash = Math.max(command.lastIndexOf('/'), command.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < command.length()) {
            return command.substring(slash + 1).toLowerCase(Locale.ROOT);
        }
        return command.toLowerCase(Locale.ROOT);
    }

    private String escapeCsv(String value) {
        String v = value == null ? "" : value;
        if (v.contains(",") || v.contains("\"")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private void appendLog(String line) {
        try {
            Files.writeString(logFile, Instant.now() + " " + line + "\n", StandardCharsets.UTF_8,
                Files.exists(logFile)
                    ? java.nio.file.StandardOpenOption.APPEND
                    : java.nio.file.StandardOpenOption.CREATE);
        } catch (IOException ignored) {
        }
    }

    private record Sample(long pid, String name, long deltaCpuMs, long totalCpuMs) {
    }
}
