package net.infiniteimperm.fabric.tagger.diagnose;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;
import net.infiniteimperm.fabric.tagger.TaggerMod;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class JfrParser {
    private static final double BAD_FRAME_MS = 16.67d;
    private static final int TOP_STACKS = 20;

    public void parse(Path jfrFile, Path presentMonCsv, Path outJson, Instant captureStart, Instant captureEnd) throws IOException {
        List<FrameRecord> frames = readPresentMon(presentMonCsv, captureStart);
        List<EventRecord> events = readJfrEvents(jfrFile);

        List<FrameRecord> badFrames = new ArrayList<>();
        List<FrameRecord> normalFrames = new ArrayList<>();
        for (FrameRecord frame : frames) {
            if (frame.frameMs > BAD_FRAME_MS) {
                badFrames.add(frame);
            } else {
                normalFrames.add(frame);
            }
        }

        Map<String, Integer> stackToId = new LinkedHashMap<>();
        List<String> idToStack = new ArrayList<>();
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"capture\": {\n");
        json.append("    \"start\": \"").append(captureStart).append("\",\n");
        json.append("    \"end\": \"").append(captureEnd).append("\",\n");
        json.append("    \"total_frames\": ").append(frames.size()).append(",\n");
        json.append("    \"bad_frames\": ").append(badFrames.size()).append("\n");
        json.append("  },\n");
        json.append("  \"baseline\": ").append(buildBaseline(normalFrames)).append(",\n");
        json.append("  \"bad_frames\": [\n");
        for (int i = 0; i < badFrames.size(); i++) {
            FrameRecord frame = badFrames.get(i);
            json.append(buildBadFrame(frame, events, stackToId, idToStack));
            if (i + 1 < badFrames.size()) {
                json.append(",");
            }
            json.append("\n");
        }
        json.append("  ],\n");
        json.append("  \"stacks\": [\n");
        for (int i = 0; i < idToStack.size(); i++) {
            json.append("    {\"id\": ").append(i + 1).append(", \"stack\": \"").append(escape(idToStack.get(i))).append("\"}");
            if (i + 1 < idToStack.size()) {
                json.append(",");
            }
            json.append("\n");
        }
        json.append("  ]\n");
        json.append("}\n");
        Files.writeString(outJson, json.toString(), StandardCharsets.UTF_8);
    }

    private String buildBadFrame(FrameRecord frame, List<EventRecord> events, Map<String, Integer> stackToId, List<String> idToStack) {
        long start = frame.windowStartMs;
        long end = frame.windowEndMs;
        List<EventRecord> inWindow = new ArrayList<>();
        for (EventRecord event : events) {
            if (event.endMs >= start && event.startMs <= end) {
                inWindow.add(event);
            }
        }

        Map<String, Integer> execStackCounts = new HashMap<>();
        Map<String, Long> allocByStack = new HashMap<>();
        int parkCount = 0;
        int monitorCount = 0;
        long gcPauseMs = 0;
        String gcType = "";
        for (EventRecord event : inWindow) {
            if (event.type.equals("jdk.ExecutionSample")) {
                execStackCounts.merge(event.stack, 1, Integer::sum);
            } else if (event.type.startsWith("jdk.ObjectAllocation")) {
                allocByStack.merge(event.stack, event.allocationBytes, Long::sum);
            } else if (event.type.equals("jdk.ThreadPark")) {
                parkCount++;
            } else if (event.type.equals("jdk.JavaMonitorEnter")) {
                monitorCount++;
            } else if (event.type.startsWith("jdk.GC")) {
                gcPauseMs += event.durationMs;
                gcType = event.type;
            }
        }

        List<Map.Entry<String, Integer>> topExec = execStackCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(TOP_STACKS).toList();
        List<Map.Entry<String, Long>> topAlloc = allocByStack.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(TOP_STACKS).toList();

        StringBuilder sb = new StringBuilder();
        sb.append("    {\n");
        sb.append("      \"frame_index\": ").append(frame.index).append(",\n");
        sb.append("      \"present_ts_ms\": ").append(frame.presentTsMs).append(",\n");
        sb.append("      \"frame_ms\": ").append(formatDouble(frame.frameMs)).append(",\n");
        sb.append("      \"window_start_ms\": ").append(frame.windowStartMs).append(",\n");
        sb.append("      \"window_end_ms\": ").append(frame.windowEndMs).append(",\n");
        sb.append("      \"execution_samples\": [\n");
        for (int i = 0; i < topExec.size(); i++) {
            Map.Entry<String, Integer> e = topExec.get(i);
            int stackId = stackId(e.getKey(), stackToId, idToStack);
            sb.append("        {\"stack_id\": ").append(stackId).append(", \"count\": ").append(e.getValue()).append("}");
            if (i + 1 < topExec.size()) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("      ],\n");
        sb.append("      \"allocations\": [\n");
        for (int i = 0; i < topAlloc.size(); i++) {
            Map.Entry<String, Long> e = topAlloc.get(i);
            int stackId = stackId(e.getKey(), stackToId, idToStack);
            sb.append("        {\"stack_id\": ").append(stackId).append(", \"bytes\": ").append(e.getValue()).append("}");
            if (i + 1 < topAlloc.size()) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("      ],\n");
        sb.append("      \"gc_overlap\": {\"pause_ms\": ").append(gcPauseMs).append(", \"type\": \"").append(escape(gcType)).append("\"},\n");
        sb.append("      \"contention\": {\"thread_park_count\": ").append(parkCount).append(", \"monitor_enter_count\": ").append(monitorCount).append("}\n");
        sb.append("    }");
        return sb.toString();
    }

    private int stackId(String stack, Map<String, Integer> stackToId, List<String> idToStack) {
        Integer existing = stackToId.get(stack);
        if (existing != null) {
            return existing;
        }
        int id = idToStack.size() + 1;
        stackToId.put(stack, id);
        idToStack.add(stack);
        return id;
    }

    private String buildBaseline(List<FrameRecord> normalFrames) {
        if (normalFrames.isEmpty()) {
            return "{\"median_frame_ms\": 0, \"sample_count\": 0}";
        }
        List<FrameRecord> sorted = new ArrayList<>(normalFrames);
        sorted.sort(Comparator.comparingDouble(fr -> fr.frameMs));
        double median = sorted.get(sorted.size() / 2).frameMs;
        return "{\"median_frame_ms\": " + formatDouble(median) + ", \"sample_count\": " + normalFrames.size() + "}";
    }

    private List<FrameRecord> readPresentMon(Path csv, Instant captureStart) throws IOException {
        List<FrameRecord> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return rows;
            }
            List<String> headers = parseCsvLine(headerLine);
            int frameMsCol = findHeader(headers, List.of("msbetweenpresents", "msbetweendisplaychange", "cpuframetime", "frametime", "msuntilrendercomplete"));
            int tsCol = findHeader(headers, List.of("timeinseconds", "qpctime", "runtime", "presentstart", "timestamp"));
            if (frameMsCol < 0) {
                throw new IOException("PresentMon CSV missing frame time column");
            }
            String line;
            int index = 0;
            while ((line = reader.readLine()) != null) {
                List<String> cols = parseCsvLine(line);
                if (cols.size() <= frameMsCol) {
                    continue;
                }
                double frameMs = parseDouble(cols.get(frameMsCol), -1d);
                if (frameMs < 0) {
                    continue;
                }
                long tsMs;
                if (tsCol >= 0 && cols.size() > tsCol) {
                    tsMs = normalizeTimestampToMs(cols.get(tsCol), captureStart.toEpochMilli(), index, frameMs);
                } else {
                    tsMs = captureStart.toEpochMilli() + (long) (index * frameMs);
                }
                long windowStart = tsMs - (long) frameMs;
                rows.add(new FrameRecord(index, tsMs, frameMs, windowStart, tsMs));
                index++;
            }
        }
        TaggerMod.LOGGER.info("[Diagnose][Parser] PresentMon frames={} badThreshold={}ms", rows.size(), BAD_FRAME_MS);
        return rows;
    }

    private List<EventRecord> readJfrEvents(Path jfr) throws IOException {
        List<EventRecord> events = new ArrayList<>();
        try (RecordingFile rf = new RecordingFile(jfr)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent event = rf.readEvent();
                String name = event.getEventType().getName();
                long start = event.getStartTime() != null ? event.getStartTime().toEpochMilli() : 0L;
                long end = event.getEndTime() != null ? event.getEndTime().toEpochMilli() : start;
                long duration = Math.max(0L, end - start);
                String stack = extractStack(event);
                long alloc = extractLong(event, "allocationSize", 0L);
                events.add(new EventRecord(name, start, end, duration, stack, alloc));
            }
        }
        TaggerMod.LOGGER.info("[Diagnose][Parser] JFR events={}", events.size());
        return events;
    }

    private String extractStack(RecordedEvent event) {
        try {
            RecordedStackTrace trace = event.getStackTrace();
            if (trace == null || trace.getFrames().isEmpty()) {
                return "<no-stack>";
            }
            StringBuilder sb = new StringBuilder();
            int limit = Math.min(20, trace.getFrames().size());
            for (int i = 0; i < limit; i++) {
                RecordedFrame frame = trace.getFrames().get(i);
                sb.append(frame.getMethod().getType().getName()).append(".").append(frame.getMethod().getName());
                if (i + 1 < limit) {
                    sb.append(" <- ");
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "<stack-error>";
        }
    }

    private long extractLong(RecordedEvent event, String field, long fallback) {
        try {
            Object value = event.getValue(field);
            if (value instanceof Number number) {
                return number.longValue();
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private int findHeader(List<String> headers, List<String> aliases) {
        for (int i = 0; i < headers.size(); i++) {
            String key = headers.get(i).toLowerCase(Locale.ROOT).replace(" ", "");
            for (String alias : aliases) {
                if (key.equals(alias) || key.contains(alias)) {
                    return i;
                }
            }
        }
        return -1;
    }

    static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quote = !quote;
                continue;
            }
            if (c == ',' && !quote) {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    private long normalizeTimestampToMs(String value, long fallbackStartMs, int index, double frameMs) {
        double raw = parseDouble(value, Double.NaN);
        if (Double.isNaN(raw)) {
            return fallbackStartMs + (long) (index * frameMs);
        }
        if (raw > 1_000_000_000_000d) {
            return (long) raw;
        }
        if (raw > 1_000_000_000d) {
            return (long) (raw / 1000d);
        }
        if (raw > 1_000_000d) {
            return fallbackStartMs + (long) raw;
        }
        return fallbackStartMs + (long) (raw * 1000d);
    }

    private double parseDouble(String s, double fallback) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private String formatDouble(double d) {
        return String.format(Locale.ROOT, "%.3f", d);
    }

    private String escape(String input) {
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record FrameRecord(int index, long presentTsMs, double frameMs, long windowStartMs, long windowEndMs) {
    }

    private record EventRecord(String type, long startMs, long endMs, long durationMs, String stack, long allocationBytes) {
    }
}
