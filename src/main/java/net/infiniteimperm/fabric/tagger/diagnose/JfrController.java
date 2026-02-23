package net.infiniteimperm.fabric.tagger.diagnose;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import net.infiniteimperm.fabric.tagger.TaggerMod;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class JfrController {
    private static final AtomicReference<JfrController> ACTIVE = new AtomicReference<>();

    private final Path logPath;
    private final ConcurrentLinkedQueue<FrameBoundary> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong frameIndex = new AtomicLong(0);
    private Recording recording;
    private Thread worker;
    private Instant startWall;
    private Instant endWall;

    public JfrController(Path logPath) {
        this.logPath = logPath;
    }

    public void start() throws Exception {
        Files.createDirectories(logPath.getParent());
        this.recording = new Recording();
        recording.setToDisk(true);
        enableIfPresent("jdk.ExecutionSample");
        enableIfPresent("jdk.ObjectAllocationInNewTLAB");
        enableIfPresent("jdk.ObjectAllocationOutsideTLAB");
        enableIfPresent("jdk.JavaMonitorEnter");
        enableIfPresent("jdk.ThreadPark");
        enableIfPresent("jdk.GarbageCollection");
        enableIfPresent("jdk.GCPhasePause");
        enableIfPresent("jdk.ThreadCPULoad");
        enableIfPresent("jdk.ThreadStart");
        enableIfPresent("jdk.ThreadEnd");
        enableIfPresent("jdk.JavaErrorThrow");

        recording.start();
        startWall = Instant.now();
        running.set(true);
        ACTIVE.set(this);

        worker = new Thread(this::drainFrameEvents, "insignia-jfr-frame-events");
        worker.setDaemon(true);
        worker.start();
        logLine("[JFR] recording started at " + startWall);
    }

    public void stopAndDump(Path outputJfr) throws Exception {
        running.set(false);
        ACTIVE.compareAndSet(this, null);
        if (worker != null) {
            worker.join(1500);
        }
        drainBatch();
        endWall = Instant.now();
        logLine("[JFR] recording stopping at " + endWall);
        recording.stop();
        recording.dump(outputJfr);
        recording.close();
        logLine("[JFR] recording dumped to " + outputJfr.toAbsolutePath());
    }

    public Instant startWall() {
        return startWall;
    }

    public Instant endWall() {
        return endWall;
    }

    private void drainFrameEvents() {
        while (running.get()) {
            drainBatch();
            try {
                Thread.sleep(10L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void drainBatch() {
        FrameBoundary b;
        while ((b = queue.poll()) != null) {
            InsigniaFrameBoundaryEvent event = new InsigniaFrameBoundaryEvent();
            event.phase = "HUD_BOUNDARY";
            event.frameIndex = b.frameIndex();
            event.nanoTime = b.nanoTime();
            event.wallMillis = b.wallMillis();
            event.commit();
        }
    }

    private void logLine(String line) {
        TaggerMod.LOGGER.info("[Diagnose] {}", line);
        try (PrintWriter out = new PrintWriter(Files.newOutputStream(logPath, Files.exists(logPath)
            ? java.nio.file.StandardOpenOption.APPEND
            : java.nio.file.StandardOpenOption.CREATE))) {
            out.println(line);
        } catch (Exception e) {
            TaggerMod.LOGGER.error("[Diagnose][JFR] Failed to write jfr log", e);
        }
    }

    private void enableIfPresent(String eventName) {
        try {
            recording.enable(eventName);
            logLine("[JFR] enabled event: " + eventName);
        } catch (Throwable t) {
            logLine("[JFR] event not available: " + eventName + " (" + t.getClass().getSimpleName() + ")");
        }
    }

    public static void onHudFrameBoundary() {
        JfrController controller = ACTIVE.get();
        if (controller == null || !controller.running.get()) {
            return;
        }
        long nowNano = System.nanoTime();
        long nowMs = System.currentTimeMillis();
        long index = controller.frameIndex.incrementAndGet();
        controller.queue.add(new FrameBoundary(index, nowNano, nowMs));
    }

    private record FrameBoundary(long frameIndex, long nanoTime, long wallMillis) {
    }

    @Name("insignia.FrameBoundary")
    @Label("Insignia Frame Boundary")
    @Category("Insignia")
    static class InsigniaFrameBoundaryEvent extends Event {
        @Label("Phase")
        String phase;

        @Label("Frame Index")
        long frameIndex;

        @Label("Nano Time")
        long nanoTime;

        @Label("Wall Time Millis")
        long wallMillis;
    }
}
