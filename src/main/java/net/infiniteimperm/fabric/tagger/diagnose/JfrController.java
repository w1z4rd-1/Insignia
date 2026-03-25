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
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class JfrController {
    private static final AtomicReference<JfrController> ACTIVE = new AtomicReference<>();
    static final long UPLOAD_BATCH_BYTES_THRESHOLD = 65_536L;
    static final long UPLOAD_BATCH_DURATION_THRESHOLD_NS = 500_000L;
    static final int UPLOAD_BATCH_COUNT_THRESHOLD = 32;
    static final long SHADER_BATCH_DURATION_THRESHOLD_NS = 1_000_000L;
    private static final int PHASE_WORLD = 1;
    private static final int PHASE_GUI = 5;
    private static final int PHASE_POST = 6;
    private static final int TRANSITION_JOIN = 1;
    private static final int TRANSITION_LEAVE = 2;
    private static final int TRANSITION_DIM_CHANGE = 3;

    private final Path logPath;
    private final ConcurrentLinkedQueue<FrameBoundary> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong frameIndex = new AtomicLong(0);
    private final AtomicLong hudStartCount = new AtomicLong(0);
    private final AtomicLong hudEndCount = new AtomicLong(0);
    private final AtomicLong phaseCount = new AtomicLong(0);
    private final AtomicLong frameSummaryCount = new AtomicLong(0);
    private final AtomicLong worldTransitionCount = new AtomicLong(0);
    private final AtomicLong userMarkCount = new AtomicLong(0);
    private final AtomicLong resourceReloadCount = new AtomicLong(0);
    private final AtomicLong chunkBatchCount = new AtomicLong(0);
    private final AtomicLong bufferUploadBatchCount = new AtomicLong(0);
    private final AtomicLong textureUploadBatchCount = new AtomicLong(0);
    private final AtomicLong shaderBatchCount = new AtomicLong(0);
    private final AtomicLong vanillaChunkHookSeen = new AtomicLong(0);
    private final AtomicLong sodiumChunkHookSeen = new AtomicLong(0);
    private final AtomicLong vanillaBufferHookSeen = new AtomicLong(0);
    private final AtomicLong sodiumBufferHookSeen = new AtomicLong(0);
    private final AtomicLong textureHookSeen = new AtomicLong(0);
    private final AtomicLong vanillaShaderHookSeen = new AtomicLong(0);
    private final AtomicLong sodiumShaderHookSeen = new AtomicLong(0);
    private final AtomicLong textureNoFrameDrops = new AtomicLong(0);
    private final AtomicLong shaderNoFrameDrops = new AtomicLong(0);
    private final AtomicLong shaderBelowThresholdDrops = new AtomicLong(0);
    private volatile long activeFrameId;
    private volatile long activeFrameStartNs;
    private volatile long activeFrameStartWallMs;
    private volatile long worldPhaseStartNs;
    private volatile long hudPhaseStartNs;
    private volatile long worldDurationNs;
    private volatile long hudDurationNs;
    private volatile long postDurationNs;
    private volatile int chunkBuildCount;
    private volatile long chunkBuildDurationNs;
    private volatile long chunkUploadDurationNs;
    private volatile int bufferUploadCount;
    private volatile long bufferUploadBytes;
    private volatile long bufferUploadDurationNs;
    private volatile int pendingBufferUploadCount;
    private volatile long pendingBufferUploadBytes;
    private volatile long pendingBufferUploadDurationNs;
    private volatile int textureUploadCount;
    private volatile long textureUploadBytes;
    private volatile long textureUploadDurationNs;
    private volatile int pendingTextureUploadCount;
    private volatile long pendingTextureUploadBytes;
    private volatile long pendingTextureUploadDurationNs;
    private volatile int pendingOffFrameTextureUploadCount;
    private volatile long pendingOffFrameTextureUploadBytes;
    private volatile long pendingOffFrameTextureUploadDurationNs;
    private volatile int shaderCompileCount;
    private volatile long shaderCompileDurationNs;
    private volatile int pendingShaderCompileCount;
    private volatile long pendingShaderCompileDurationNs;
    private volatile int pendingOffFrameShaderCompileCount;
    private volatile long pendingOffFrameShaderCompileDurationNs;
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
        enableExecutionSample();
        enableIfPresent("jdk.ObjectAllocationInNewTLAB", true);
        enableIfPresent("jdk.ObjectAllocationOutsideTLAB", true);
        enableIfPresent("jdk.JavaMonitorEnter", true);
        enableIfPresent("jdk.ThreadPark", true);
        enableIfPresent("jdk.GarbageCollection", false);
        enableIfPresent("jdk.GCPhasePause", false);
        enableIfPresent("jdk.ThreadCPULoad", false);
        enableIfPresent("jdk.ThreadStart", false);
        enableIfPresent("jdk.ThreadEnd", false);
        enableIfPresent("jdk.JavaErrorThrow", true);

        recording.start();
        startWall = Instant.now();
        running.set(true);
        ACTIVE.set(this);

        worker = new Thread(this::drainFrameEvents, "insignia-jfr-frame-events");
        worker.setDaemon(true);
        worker.start();
        logLine("[JFR] recording started at " + startWall);
        logLine("[JFR] custom instrumentation enabled: HudFrameStart/HudFrameEnd/FramePhase/FrameSummary/WorldTransition/UserStutterMark/ResourceReload/ChunkBuildBatch/BufferUploadBatch/TextureUploadBatch/ShaderCompileBatch");
        logLine("[JFR] thresholds: upload batch flush when bytes>=" + UPLOAD_BATCH_BYTES_THRESHOLD
            + " or durationNs>=" + UPLOAD_BATCH_DURATION_THRESHOLD_NS
            + " or uploadCount>=" + UPLOAD_BATCH_COUNT_THRESHOLD
            + ", shader_compile durationNs>=" + SHADER_BATCH_DURATION_THRESHOLD_NS);
    }

    public void stopAndDump(Path outputJfr) throws Exception {
        running.set(false);
        ACTIVE.compareAndSet(this, null);
        if (worker != null) {
            worker.join(1500);
        }
        flushPendingOffFrameBatches();
        drainBatch();
        endWall = Instant.now();
        logLine("[JFR] recording stopping at " + endWall);
        recording.stop();
        recording.dump(outputJfr);
        recording.close();
        logLine("[JFR] recording dumped to " + outputJfr.toAbsolutePath());
        logLine("[JFR] custom_event_counts hudStart=" + hudStartCount.get()
            + " hudEnd=" + hudEndCount.get()
            + " phase=" + phaseCount.get()
            + " frameSummary=" + frameSummaryCount.get()
            + " worldTransition=" + worldTransitionCount.get()
            + " userMark=" + userMarkCount.get()
            + " resourceReload=" + resourceReloadCount.get()
            + " chunkBatch=" + chunkBatchCount.get()
            + " bufferUploadBatch=" + bufferUploadBatchCount.get()
            + " textureUploadBatch=" + textureUploadBatchCount.get()
            + " shaderBatch=" + shaderBatchCount.get());
        logLine("[JFR] hook_seen_counts vanillaChunk=" + vanillaChunkHookSeen.get()
            + " sodiumChunk=" + sodiumChunkHookSeen.get()
            + " vanillaBuffer=" + vanillaBufferHookSeen.get()
            + " sodiumBuffer=" + sodiumBufferHookSeen.get()
            + " texture=" + textureHookSeen.get()
            + " vanillaShader=" + vanillaShaderHookSeen.get()
            + " sodiumShader=" + sodiumShaderHookSeen.get());
        logLine("[JFR] offframe_activity texture=" + textureNoFrameDrops.get()
            + " shader=" + shaderNoFrameDrops.get()
            + " subthreshold_shader_updates=" + shaderBelowThresholdDrops.get());
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

    private void enableExecutionSample() {
        try {
            recording.enable("jdk.ExecutionSample")
                .withPeriod(Duration.ofMillis(10))
                .withStackTrace();
            logLine("[JFR] enabled event: jdk.ExecutionSample period=10 ms stacktrace=true");
        } catch (Throwable t) {
            logLine("[JFR] event not available: jdk.ExecutionSample (" + t.getClass().getSimpleName() + ")");
        }
    }

    private void enableIfPresent(String eventName, boolean withStackTrace) {
        try {
            var settings = recording.enable(eventName);
            if (withStackTrace) {
                settings.withStackTrace();
            }
            logLine("[JFR] enabled event: " + eventName + (withStackTrace ? " stacktrace=true" : ""));
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

    public static void onHudFrameStart() {
        JfrController controller = ACTIVE.get();
        if (controller == null || !controller.running.get()) {
            return;
        }
        long nowNs = System.nanoTime();
        long nowMs = System.currentTimeMillis();
        if (controller.activeFrameId != 0L) {
            controller.flushFrameSummary(nowNs, nowMs);
        }
        controller.activeFrameId = controller.frameIndex.incrementAndGet();
        controller.activeFrameStartNs = nowNs;
        controller.activeFrameStartWallMs = nowMs;
        controller.worldDurationNs = 0L;
        controller.hudDurationNs = 0L;
        controller.postDurationNs = 0L;
        controller.chunkBuildCount = 0;
        controller.chunkBuildDurationNs = 0L;
        controller.chunkUploadDurationNs = 0L;
        controller.bufferUploadCount = 0;
        controller.bufferUploadBytes = 0L;
        controller.bufferUploadDurationNs = 0L;
        controller.pendingBufferUploadCount = 0;
        controller.pendingBufferUploadBytes = 0L;
        controller.pendingBufferUploadDurationNs = 0L;
        controller.textureUploadCount = 0;
        controller.textureUploadBytes = 0L;
        controller.textureUploadDurationNs = 0L;
        controller.pendingTextureUploadCount = 0;
        controller.pendingTextureUploadBytes = 0L;
        controller.pendingTextureUploadDurationNs = 0L;
        controller.shaderCompileCount = 0;
        controller.shaderCompileDurationNs = 0L;
        controller.pendingShaderCompileCount = 0;
        controller.pendingShaderCompileDurationNs = 0L;
        controller.worldPhaseStartNs = 0L;
        controller.hudPhaseStartNs = nowNs;

        HudFrameStartEvent event = new HudFrameStartEvent();
        event.frameId = controller.activeFrameId;
        event.nanoTime = nowNs;
        event.wallMillis = nowMs;
        event.commit();
        controller.hudStartCount.incrementAndGet();
    }

    public static void onHudFrameEnd() {
        JfrController controller = ACTIVE.get();
        if (controller == null || !controller.running.get()) {
            return;
        }
        if (controller.activeFrameId == 0L) {
            return;
        }
        long nowNs = System.nanoTime();
        long nowMs = System.currentTimeMillis();
        if (controller.hudPhaseStartNs > 0L) {
            long dur = Math.max(0L, nowNs - controller.hudPhaseStartNs);
            controller.hudDurationNs += dur;
            controller.emitPhase(controller.activeFrameId, PHASE_GUI, dur, nowNs, nowMs);
            controller.hudPhaseStartNs = 0L;
        }
        long frameDurationNs = Math.max(0L, nowNs - controller.activeFrameStartNs);
        HudFrameEndEvent event = new HudFrameEndEvent();
        event.frameId = controller.activeFrameId;
        event.nanoTime = nowNs;
        event.wallMillis = nowMs;
        event.frameDurationNs = frameDurationNs;
        event.commit();
        controller.hudEndCount.incrementAndGet();
        controller.flushFrameSummary(nowNs, nowMs);
    }

    public static void onWorldFrameStart() {
        JfrController controller = ACTIVE.get();
        if (controller == null || !controller.running.get()) {
            return;
        }
        if (controller.activeFrameId == 0L) {
            return;
        }
        controller.worldPhaseStartNs = System.nanoTime();
    }

    public static void onWorldFrameEnd() {
        JfrController controller = ACTIVE.get();
        if (controller == null || !controller.running.get()) {
            return;
        }
        if (controller.activeFrameId == 0L || controller.worldPhaseStartNs <= 0L) {
            return;
        }
        long nowNs = System.nanoTime();
        long nowMs = System.currentTimeMillis();
        long dur = Math.max(0L, nowNs - controller.worldPhaseStartNs);
        controller.worldDurationNs += dur;
        controller.emitPhase(controller.activeFrameId, PHASE_WORLD, dur, nowNs, nowMs);
        controller.worldPhaseStartNs = 0L;
    }

    public static void onPostPhaseDuration(long durationNs) {
        JfrController controller = ACTIVE.get();
        if (controller == null || !controller.running.get()) {
            return;
        }
        if (controller.activeFrameId == 0L || durationNs <= 0L) {
            return;
        }
        long nowNs = System.nanoTime();
        long nowMs = System.currentTimeMillis();
        long clamped = Math.max(0L, durationNs);
        controller.postDurationNs += clamped;
        controller.emitPhase(controller.activeFrameId, PHASE_POST, clamped, nowNs, nowMs);
    }

    public static void onWorldTransition(int transitionType, long durationNs) {
        JfrController controller = ACTIVE.get();
        if (controller == null || !controller.running.get()) {
            return;
        }
        WorldTransitionEvent event = new WorldTransitionEvent();
        event.nanoTime = System.nanoTime();
        event.wallMillis = System.currentTimeMillis();
        event.transitionType = transitionType;
        event.durationNs = Math.max(0L, durationNs);
        event.commit();
        controller.worldTransitionCount.incrementAndGet();
    }

    public static void onResourceReload(int phase, long durationNs) {
        JfrController controller = ACTIVE.get();
        if (controller == null || !controller.running.get()) {
            return;
        }
        ResourceReloadEvent event = new ResourceReloadEvent();
        event.nanoTime = System.nanoTime();
        event.wallMillis = System.currentTimeMillis();
        event.phase = phase;
        event.durationNs = Math.max(0L, durationNs);
        event.commit();
        controller.resourceReloadCount.incrementAndGet();
    }

    public static void onUserStutterMark(String label) {
        JfrController controller = ACTIVE.get();
        if (controller == null || !controller.running.get()) {
            return;
        }
        UserStutterMarkEvent event = new UserStutterMarkEvent();
        event.nanoTime = System.nanoTime();
        event.wallMillis = System.currentTimeMillis();
        event.frameId = controller.activeFrameId;
        event.label = label == null ? "" : label;
        event.commit();
        controller.userMarkCount.incrementAndGet();
    }

    public static void onChunkBuildBatch(int chunkCount, long buildDurationNs, long uploadDurationNs) {
        JfrController controller = ACTIVE.get();
        if (controller == null || !controller.running.get() || controller.activeFrameId == 0L) {
            return;
        }
        int safeCount = Math.max(0, chunkCount);
        long safeBuild = Math.max(0L, buildDurationNs);
        long safeUpload = Math.max(0L, uploadDurationNs);
        if (safeCount <= 0 && safeBuild <= 0L && safeUpload <= 0L) {
            return;
        }
        controller.chunkBuildCount += safeCount;
        controller.chunkBuildDurationNs += safeBuild;
        controller.chunkUploadDurationNs += safeUpload;
        ChunkBuildBatchEvent event = new ChunkBuildBatchEvent();
        event.frameId = controller.activeFrameId;
        event.nanoTime = System.nanoTime();
        event.wallMillis = System.currentTimeMillis();
        event.chunkCount = safeCount;
        event.buildDurationNs = safeBuild;
        event.uploadDurationNs = safeUpload;
        event.commit();
        controller.chunkBatchCount.incrementAndGet();
    }

    public static void onVanillaChunkHookSeen() {
        markHookSeen(HookType.VANILLA_CHUNK);
    }

    public static void onSodiumChunkHookSeen() {
        markHookSeen(HookType.SODIUM_CHUNK);
    }

    public static void onBufferUploadBatch(int uploadCount, long bytes, long durationNs) {
        JfrController controller = ACTIVE.get();
        if (controller == null || !controller.running.get() || controller.activeFrameId == 0L) {
            return;
        }
        int safeCount = Math.max(0, uploadCount);
        long safeBytes = Math.max(0L, bytes);
        long safeDur = Math.max(0L, durationNs);
        if (safeCount <= 0 && safeBytes <= 0L && safeDur <= 0L) {
            return;
        }
        controller.bufferUploadCount += safeCount;
        controller.bufferUploadBytes += safeBytes;
        controller.bufferUploadDurationNs += safeDur;
        controller.pendingBufferUploadCount += safeCount;
        controller.pendingBufferUploadBytes += safeBytes;
        controller.pendingBufferUploadDurationNs += safeDur;
        if (!shouldFlushUploadBatch(
            controller.pendingBufferUploadCount,
            controller.pendingBufferUploadBytes,
            controller.pendingBufferUploadDurationNs)) {
            return;
        }
        controller.emitBufferUploadBatch(
            controller.pendingBufferUploadCount,
            controller.pendingBufferUploadBytes,
            controller.pendingBufferUploadDurationNs);
    }

    private void emitBufferUploadBatch(int uploadCount, long bytes, long durationNs) {
        BufferUploadBatchEvent event = new BufferUploadBatchEvent();
        event.frameId = activeFrameId;
        event.nanoTime = System.nanoTime();
        event.wallMillis = System.currentTimeMillis();
        event.uploadCount = uploadCount;
        event.bytes = bytes;
        event.durationNs = durationNs;
        event.commit();
        bufferUploadBatchCount.incrementAndGet();
        pendingBufferUploadCount = 0;
        pendingBufferUploadBytes = 0L;
        pendingBufferUploadDurationNs = 0L;
    }

    public static void onVanillaBufferHookSeen() {
        markHookSeen(HookType.VANILLA_BUFFER);
    }

    public static void onSodiumBufferHookSeen() {
        markHookSeen(HookType.SODIUM_BUFFER);
    }

    public static void onTextureUploadBatch(int uploadCount, long bytes, long durationNs) {
        markHookSeen(HookType.TEXTURE);
        JfrController controller = ACTIVE.get();
        if (controller == null || !controller.running.get()) {
            return;
        }
        int safeCount = Math.max(0, uploadCount);
        long safeBytes = Math.max(0L, bytes);
        long safeDur = Math.max(0L, durationNs);
        if (safeCount <= 0 && safeBytes <= 0L && safeDur <= 0L) {
            return;
        }
        if (controller.activeFrameId == 0L) {
            controller.textureNoFrameDrops.incrementAndGet();
            controller.pendingOffFrameTextureUploadCount += safeCount;
            controller.pendingOffFrameTextureUploadBytes += safeBytes;
            controller.pendingOffFrameTextureUploadDurationNs += safeDur;
            if (shouldFlushUploadBatch(
                controller.pendingOffFrameTextureUploadCount,
                controller.pendingOffFrameTextureUploadBytes,
                controller.pendingOffFrameTextureUploadDurationNs)) {
                controller.emitTextureUploadBatch(
                    0L,
                    controller.pendingOffFrameTextureUploadCount,
                    controller.pendingOffFrameTextureUploadBytes,
                    controller.pendingOffFrameTextureUploadDurationNs);
            }
            return;
        }
        controller.textureUploadCount += safeCount;
        controller.textureUploadBytes += safeBytes;
        controller.textureUploadDurationNs += safeDur;
        controller.pendingTextureUploadCount += safeCount;
        controller.pendingTextureUploadBytes += safeBytes;
        controller.pendingTextureUploadDurationNs += safeDur;
        if (!shouldFlushUploadBatch(
            controller.pendingTextureUploadCount,
            controller.pendingTextureUploadBytes,
            controller.pendingTextureUploadDurationNs)) {
            return;
        }
        controller.emitTextureUploadBatch(
            controller.activeFrameId,
            controller.pendingTextureUploadCount,
            controller.pendingTextureUploadBytes,
            controller.pendingTextureUploadDurationNs);
    }

    private void emitTextureUploadBatch(long frameId, int uploadCount, long bytes, long durationNs) {
        TextureUploadBatchEvent event = new TextureUploadBatchEvent();
        event.frameId = frameId;
        event.nanoTime = System.nanoTime();
        event.wallMillis = System.currentTimeMillis();
        event.uploadCount = uploadCount;
        event.bytes = bytes;
        event.durationNs = durationNs;
        event.commit();
        textureUploadBatchCount.incrementAndGet();
        if (frameId == 0L) {
            pendingOffFrameTextureUploadCount = 0;
            pendingOffFrameTextureUploadBytes = 0L;
            pendingOffFrameTextureUploadDurationNs = 0L;
        } else {
            pendingTextureUploadCount = 0;
            pendingTextureUploadBytes = 0L;
            pendingTextureUploadDurationNs = 0L;
        }
    }

    public static void onShaderCompileBatch(int compileCount, long durationNs) {
        JfrController controller = ACTIVE.get();
        if (controller == null || !controller.running.get()) {
            return;
        }
        int safeCount = Math.max(0, compileCount);
        long safeDur = Math.max(0L, durationNs);
        if (safeCount <= 0) {
            return;
        }
        if (controller.activeFrameId == 0L) {
            controller.shaderNoFrameDrops.incrementAndGet();
            controller.pendingOffFrameShaderCompileCount += safeCount;
            controller.pendingOffFrameShaderCompileDurationNs += safeDur;
            if (shouldFlushShaderBatch(
                controller.pendingOffFrameShaderCompileCount,
                controller.pendingOffFrameShaderCompileDurationNs)) {
                controller.emitShaderCompileBatch(
                    0L,
                    controller.pendingOffFrameShaderCompileCount,
                    controller.pendingOffFrameShaderCompileDurationNs);
            }
            return;
        }
        controller.shaderCompileCount += safeCount;
        controller.shaderCompileDurationNs += safeDur;
        controller.pendingShaderCompileCount += safeCount;
        controller.pendingShaderCompileDurationNs += safeDur;
        if (!shouldFlushShaderBatch(controller.pendingShaderCompileCount, controller.pendingShaderCompileDurationNs)) {
            controller.shaderBelowThresholdDrops.incrementAndGet();
            return;
        }
        controller.emitShaderCompileBatch(controller.activeFrameId, controller.pendingShaderCompileCount, controller.pendingShaderCompileDurationNs);
    }

    public static void onVanillaShaderHookSeen() {
        markHookSeen(HookType.VANILLA_SHADER);
    }

    public static void onSodiumShaderHookSeen() {
        markHookSeen(HookType.SODIUM_SHADER);
    }

    public static int transitionJoin() {
        return TRANSITION_JOIN;
    }

    public static int transitionLeave() {
        return TRANSITION_LEAVE;
    }

    public static int transitionDimChange() {
        return TRANSITION_DIM_CHANGE;
    }

    public static int resourceReloadStartPhase() {
        return 1;
    }

    public static int resourceReloadEndPhase() {
        return 2;
    }

    private void emitPhase(long frameId, int phase, long durationNs, long nowNs, long nowMs) {
        FramePhaseEvent event = new FramePhaseEvent();
        event.frameId = frameId;
        event.phase = phase;
        event.durationNs = Math.max(0L, durationNs);
        event.nanoTime = nowNs;
        event.wallMillis = nowMs;
        event.commit();
        phaseCount.incrementAndGet();
    }

    private void flushFrameSummary(long nowNs, long nowMs) {
        if (activeFrameId == 0L) {
            return;
        }
        if (pendingBufferUploadCount > 0 || pendingBufferUploadBytes > 0L || pendingBufferUploadDurationNs > 0L) {
            emitBufferUploadBatch(pendingBufferUploadCount, pendingBufferUploadBytes, pendingBufferUploadDurationNs);
        }
        if (pendingTextureUploadCount > 0 || pendingTextureUploadBytes > 0L || pendingTextureUploadDurationNs > 0L) {
            emitTextureUploadBatch(activeFrameId, pendingTextureUploadCount, pendingTextureUploadBytes, pendingTextureUploadDurationNs);
        }
        if (pendingShaderCompileCount > 0 || pendingShaderCompileDurationNs > 0L) {
            emitShaderCompileBatch(activeFrameId, pendingShaderCompileCount, pendingShaderCompileDurationNs);
        }
        long totalFrameNs = Math.max(0L, nowNs - activeFrameStartNs);
        FrameSummaryEvent event = new FrameSummaryEvent();
        event.frameId = activeFrameId;
        event.nanoTime = nowNs;
        event.wallMillis = nowMs;
        event.frameStartWallMillis = activeFrameStartWallMs;
        event.totalFrameDurationNs = totalFrameNs;
        event.worldDurationNs = worldDurationNs;
        event.hudDurationNs = hudDurationNs;
        event.postDurationNs = postDurationNs;
        event.chunkBuildCount = chunkBuildCount;
        event.chunkBuildDurationNs = chunkBuildDurationNs;
        event.chunkUploadDurationNs = chunkUploadDurationNs;
        event.bufferUploadCount = bufferUploadCount;
        event.bufferUploadBytes = bufferUploadBytes;
        event.bufferUploadDurationNs = bufferUploadDurationNs;
        event.textureUploadCount = textureUploadCount;
        event.textureUploadBytes = textureUploadBytes;
        event.textureUploadDurationNs = textureUploadDurationNs;
        event.shaderCompileCount = shaderCompileCount;
        event.shaderCompileDurationNs = shaderCompileDurationNs;
        event.commit();
        frameSummaryCount.incrementAndGet();

        activeFrameId = 0L;
        activeFrameStartNs = 0L;
        activeFrameStartWallMs = 0L;
        worldPhaseStartNs = 0L;
        hudPhaseStartNs = 0L;
        worldDurationNs = 0L;
        hudDurationNs = 0L;
        postDurationNs = 0L;
        chunkBuildCount = 0;
        chunkBuildDurationNs = 0L;
        chunkUploadDurationNs = 0L;
        bufferUploadCount = 0;
        bufferUploadBytes = 0L;
        bufferUploadDurationNs = 0L;
        pendingBufferUploadCount = 0;
        pendingBufferUploadBytes = 0L;
        pendingBufferUploadDurationNs = 0L;
        textureUploadCount = 0;
        textureUploadBytes = 0L;
        textureUploadDurationNs = 0L;
        pendingTextureUploadCount = 0;
        pendingTextureUploadBytes = 0L;
        pendingTextureUploadDurationNs = 0L;
        shaderCompileCount = 0;
        shaderCompileDurationNs = 0L;
        pendingShaderCompileCount = 0;
        pendingShaderCompileDurationNs = 0L;
    }

    static boolean shouldFlushUploadBatch(int uploadCount, long bytes, long durationNs) {
        return uploadCount >= UPLOAD_BATCH_COUNT_THRESHOLD
            || bytes >= UPLOAD_BATCH_BYTES_THRESHOLD
            || durationNs >= UPLOAD_BATCH_DURATION_THRESHOLD_NS;
    }

    static boolean shouldFlushShaderBatch(int compileCount, long durationNs) {
        return compileCount > 0 && durationNs >= SHADER_BATCH_DURATION_THRESHOLD_NS;
    }

    private void emitShaderCompileBatch(long frameId, int compileCount, long durationNs) {
        ShaderCompileBatchEvent event = new ShaderCompileBatchEvent();
        event.frameId = frameId;
        event.nanoTime = System.nanoTime();
        event.wallMillis = System.currentTimeMillis();
        event.compileCount = compileCount;
        event.durationNs = durationNs;
        event.commit();
        shaderBatchCount.incrementAndGet();
        if (frameId == 0L) {
            pendingOffFrameShaderCompileCount = 0;
            pendingOffFrameShaderCompileDurationNs = 0L;
        } else {
            pendingShaderCompileCount = 0;
            pendingShaderCompileDurationNs = 0L;
        }
    }

    private void flushPendingOffFrameBatches() {
        if (pendingOffFrameTextureUploadCount > 0 || pendingOffFrameTextureUploadBytes > 0L || pendingOffFrameTextureUploadDurationNs > 0L) {
            emitTextureUploadBatch(0L, pendingOffFrameTextureUploadCount, pendingOffFrameTextureUploadBytes, pendingOffFrameTextureUploadDurationNs);
        }
        if (pendingOffFrameShaderCompileCount > 0 || pendingOffFrameShaderCompileDurationNs > 0L) {
            emitShaderCompileBatch(0L, pendingOffFrameShaderCompileCount, pendingOffFrameShaderCompileDurationNs);
        }
    }

    private record FrameBoundary(long frameIndex, long nanoTime, long wallMillis) {
    }

    private static void markHookSeen(HookType type) {
        JfrController controller = ACTIVE.get();
        if (controller == null || !controller.running.get()) {
            return;
        }
        AtomicLong counter = switch (type) {
            case VANILLA_CHUNK -> controller.vanillaChunkHookSeen;
            case SODIUM_CHUNK -> controller.sodiumChunkHookSeen;
            case VANILLA_BUFFER -> controller.vanillaBufferHookSeen;
            case SODIUM_BUFFER -> controller.sodiumBufferHookSeen;
            case TEXTURE -> controller.textureHookSeen;
            case VANILLA_SHADER -> controller.vanillaShaderHookSeen;
            case SODIUM_SHADER -> controller.sodiumShaderHookSeen;
        };
        long seen = counter.incrementAndGet();
        if (seen == 1L) {
            controller.logLine("[JFR] hook first_seen: " + type.logName);
        }
    }

    private enum HookType {
        VANILLA_CHUNK("vanilla_chunk"),
        SODIUM_CHUNK("sodium_chunk"),
        VANILLA_BUFFER("vanilla_buffer"),
        SODIUM_BUFFER("sodium_buffer"),
        TEXTURE("texture"),
        VANILLA_SHADER("vanilla_shader"),
        SODIUM_SHADER("sodium_shader");

        private final String logName;

        HookType(String logName) {
            this.logName = logName;
        }
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

    @Name("insignia.HudFrameStart")
    @Label("Insignia Hud Frame Start")
    @Category("Insignia")
    static class HudFrameStartEvent extends Event {
        @Label("Frame ID")
        long frameId;
        @Label("Nano Time")
        long nanoTime;
        @Label("Wall Time Millis")
        long wallMillis;
    }

    @Name("insignia.HudFrameEnd")
    @Label("Insignia Hud Frame End")
    @Category("Insignia")
    static class HudFrameEndEvent extends Event {
        @Label("Frame ID")
        long frameId;
        @Label("Nano Time")
        long nanoTime;
        @Label("Wall Time Millis")
        long wallMillis;
        @Label("Frame Duration Nanos")
        long frameDurationNs;
    }

    @Name("insignia.FramePhase")
    @Label("Insignia Frame Phase")
    @Category("Insignia")
    static class FramePhaseEvent extends Event {
        @Label("Frame ID")
        long frameId;
        @Label("Phase")
        int phase;
        @Label("Duration Nanos")
        long durationNs;
        @Label("Nano Time")
        long nanoTime;
        @Label("Wall Time Millis")
        long wallMillis;
    }

    @Name("insignia.FrameSummary")
    @Label("Insignia Frame Summary")
    @Category("Insignia")
    static class FrameSummaryEvent extends Event {
        @Label("Frame ID")
        long frameId;
        @Label("Nano Time")
        long nanoTime;
        @Label("Wall Time Millis")
        long wallMillis;
        @Label("Frame Start Wall Millis")
        long frameStartWallMillis;
        @Label("Total Frame Duration Nanos")
        long totalFrameDurationNs;
        @Label("World Phase Duration Nanos")
        long worldDurationNs;
        @Label("Hud Phase Duration Nanos")
        long hudDurationNs;
        @Label("Post Phase Duration Nanos")
        long postDurationNs;
        @Label("Chunk Build Count")
        int chunkBuildCount;
        @Label("Chunk Build Duration Nanos")
        long chunkBuildDurationNs;
        @Label("Chunk Upload Duration Nanos")
        long chunkUploadDurationNs;
        @Label("Buffer Upload Count")
        int bufferUploadCount;
        @Label("Buffer Upload Bytes")
        long bufferUploadBytes;
        @Label("Buffer Upload Duration Nanos")
        long bufferUploadDurationNs;
        @Label("Texture Upload Count")
        int textureUploadCount;
        @Label("Texture Upload Bytes")
        long textureUploadBytes;
        @Label("Texture Upload Duration Nanos")
        long textureUploadDurationNs;
        @Label("Shader Compile Count")
        int shaderCompileCount;
        @Label("Shader Compile Duration Nanos")
        long shaderCompileDurationNs;
    }

    @Name("insignia.ChunkBuildBatch")
    @Label("Insignia Chunk Build Batch")
    @Category("Insignia")
    static class ChunkBuildBatchEvent extends Event {
        @Label("Frame ID")
        long frameId;
        @Label("Nano Time")
        long nanoTime;
        @Label("Wall Time Millis")
        long wallMillis;
        @Label("Chunk Count")
        int chunkCount;
        @Label("Build Duration Nanos")
        long buildDurationNs;
        @Label("Upload Duration Nanos")
        long uploadDurationNs;
    }

    @Name("insignia.BufferUploadBatch")
    @Label("Insignia Buffer Upload Batch")
    @Category("Insignia")
    static class BufferUploadBatchEvent extends Event {
        @Label("Frame ID")
        long frameId;
        @Label("Nano Time")
        long nanoTime;
        @Label("Wall Time Millis")
        long wallMillis;
        @Label("Upload Count")
        int uploadCount;
        @Label("Bytes")
        long bytes;
        @Label("Duration Nanos")
        long durationNs;
    }

    @Name("insignia.TextureUploadBatch")
    @Label("Insignia Texture Upload Batch")
    @Category("Insignia")
    static class TextureUploadBatchEvent extends Event {
        @Label("Frame ID")
        long frameId;
        @Label("Nano Time")
        long nanoTime;
        @Label("Wall Time Millis")
        long wallMillis;
        @Label("Upload Count")
        int uploadCount;
        @Label("Bytes")
        long bytes;
        @Label("Duration Nanos")
        long durationNs;
    }

    @Name("insignia.ShaderCompileBatch")
    @Label("Insignia Shader Compile Batch")
    @Category("Insignia")
    static class ShaderCompileBatchEvent extends Event {
        @Label("Frame ID")
        long frameId;
        @Label("Nano Time")
        long nanoTime;
        @Label("Wall Time Millis")
        long wallMillis;
        @Label("Compile Count")
        int compileCount;
        @Label("Duration Nanos")
        long durationNs;
    }

    @Name("insignia.ResourceReload")
    @Label("Insignia Resource Reload")
    @Category("Insignia")
    static class ResourceReloadEvent extends Event {
        @Label("Phase")
        int phase;
        @Label("Duration Nanos")
        long durationNs;
        @Label("Nano Time")
        long nanoTime;
        @Label("Wall Time Millis")
        long wallMillis;
    }

    @Name("insignia.WorldTransition")
    @Label("Insignia World Transition")
    @Category("Insignia")
    static class WorldTransitionEvent extends Event {
        @Label("Transition Type")
        int transitionType;
        @Label("Duration Nanos")
        long durationNs;
        @Label("Nano Time")
        long nanoTime;
        @Label("Wall Time Millis")
        long wallMillis;
    }

    @Name("insignia.UserStutterMark")
    @Label("Insignia User Stutter Mark")
    @Category("Insignia")
    static class UserStutterMarkEvent extends Event {
        @Label("Frame ID")
        long frameId;
        @Label("Label")
        String label;
        @Label("Nano Time")
        long nanoTime;
        @Label("Wall Time Millis")
        long wallMillis;
    }
}
