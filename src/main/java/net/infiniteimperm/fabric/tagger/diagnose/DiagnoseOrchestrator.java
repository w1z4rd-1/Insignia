package net.infiniteimperm.fabric.tagger.diagnose;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.infiniteimperm.fabric.tagger.InsigniaConfig;
import net.infiniteimperm.fabric.tagger.TaggerMod;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Collections;

public final class DiagnoseOrchestrator {
    public enum Mode {
        NORMAL,
        FULL,
        CUSTOM
    }

    private static final class CapturePlan {
        boolean presentMon;
        boolean jfr;
        boolean perfCounters;
        boolean typeperf;
        boolean processContention;
        boolean nvidiaSmi;
        boolean spark;
        boolean nsys;
        boolean wpr;

        boolean anyEnabled() {
            return presentMon || jfr || perfCounters || typeperf || processContention || nvidiaSmi || spark || nsys || wpr;
        }
    }

    private static final DiagnoseOrchestrator INSTANCE = new DiagnoseOrchestrator();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int DEFAULT_CAPTURE_SECONDS = 120;
    private static final long EXPORT_WAIT_NOTICE_MS = 30_000L;
    private static final long EXPORT_TIMEOUT_MS = 60_000L;
    private static final String ACTIVE_STATE_FILE = "active-session.properties";
    private static final long SHARE_ZIP_MAX_BYTES = 512L * 1024L * 1024L;
    private static final int SHARE_ZIP_MAX_PARTS = 3;
    private static final Pattern SPARK_URL_PATTERN = Pattern.compile("(https://spark\\.lucko\\.me/[A-Za-z0-9]+)");
    private static final int SPARK_FETCH_RETRIES = 3;
    private static final long SPARK_FETCH_RETRY_BACKOFF_MS = 1500L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "insignia-diagnose");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile Path activeRunDir;
    private volatile ElevatedHelper elevatedHelper;
    private volatile Instant sparkDispatchAt;
    private volatile String sparkUrlFromChat;
    private volatile Instant sparkUrlCapturedAt;

    private DiagnoseOrchestrator() {
    }

    public static DiagnoseOrchestrator getInstance() {
        return INSTANCE;
    }

    public static void onHudFrameBoundary() {
        JfrController.onHudFrameBoundary();
    }

    public static void onHudFrameStart() {
        JfrController.onHudFrameStart();
    }

    public static void onHudFrameEnd() {
        JfrController.onHudFrameEnd();
    }

    public static void onWorldFrameStart() {
        JfrController.onWorldFrameStart();
    }

    public static void onWorldFrameEnd() {
        JfrController.onWorldFrameEnd();
    }

    public static void onPostPhaseDuration(long durationNs) {
        JfrController.onPostPhaseDuration(durationNs);
    }

    public static void onWorldTransitionJoin(long durationNs) {
        JfrController.onWorldTransition(JfrController.transitionJoin(), durationNs);
    }

    public static void onWorldTransitionLeave(long durationNs) {
        JfrController.onWorldTransition(JfrController.transitionLeave(), durationNs);
    }

    public static void onWorldTransitionDimChange(long durationNs) {
        JfrController.onWorldTransition(JfrController.transitionDimChange(), durationNs);
    }

    public static void onUserStutterMark(String label) {
        JfrController.onUserStutterMark(label);
    }

    public static void onResourceReloadStart() {
        JfrController.onResourceReload(JfrController.resourceReloadStartPhase(), 0L);
    }

    public static void onResourceReloadEnd(long durationNs) {
        JfrController.onResourceReload(JfrController.resourceReloadEndPhase(), durationNs);
    }

    public void onIncomingGameMessage(String text) {
        if (!active.get() || text == null || text.isBlank()) {
            return;
        }
        // Ignore unrelated spark URLs unless we actually dispatched a spark profile in this run.
        if (sparkDispatchAt == null) {
            return;
        }
        Matcher matcher = SPARK_URL_PATTERN.matcher(text);
        String found = null;
        while (matcher.find()) {
            found = matcher.group(1);
        }
        if (found == null || found.isBlank()) {
            return;
        }
        sparkUrlFromChat = found;
        sparkUrlCapturedAt = Instant.now();
        try {
            Path run = activeRunDir;
            if (run != null) {
                Path out = run.resolve("results").resolve("spark-profile-url.txt");
                Files.createDirectories(out.getParent());
                Files.writeString(out, found + System.lineSeparator(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
        }
        TaggerMod.LOGGER.info("[Diagnose] Captured spark URL from chat: {}", found);
    }

    public void showSetupHelp(FabricClientCommandSource source) {
        ToolDetection.DetectionReport report = ToolDetection.detectAll();
        statusLine("PresentMon", report.presentMon.found);
        statusLine("JFR", report.jfrAvailable);

        statusLine("spark mod", report.sparkPresent);
        statusLine("Nsight Systems (nsys)", report.nsys.found);
        statusLine("Windows Performance Recorder (wpr)", report.wpr.found);
        statusLine("WPAExporter", report.wpaExporter != null && report.wpaExporter.found);
        statusLine("tracerpt", report.tracerpt != null && report.tracerpt.found);
        statusLine("Windows hardware counters (typeperf)", report.typeperf != null && report.typeperf.found);
        statusLine("NVIDIA GPU telemetry (nvidia-smi)", report.nvidiaSmi != null && report.nvidiaSmi.found);

        if (!report.presentMon.found) {
            ChatUi.error("PresentMon missing: install it, then run /diagnose again.");
            ChatUi.hintGray("PowerShell (recommended): winget install --id=Intel.PresentMon.Console -e");
            ChatUi.clickableUrl("Download PresentMon", "https://github.com/GameTechDev/PresentMon/releases");
            for (String checked : report.presentMon.checkedPaths) {
                ChatUi.hintGray("checked: " + checked);
            }
        }
        if (!report.sparkPresent) {
            ChatUi.hintGray("to profile the game itself get the mod spark profiler");
            ChatUi.clickableUrl("spark profiler mod", "https://modrinth.com/mod/spark");
        }
        if (!report.nsys.found) {
            ChatUi.clickableUrl("Nsight Systems", "https://developer.nvidia.com/nsight-systems");
        }
        if (!report.wpr.found) {
            ChatUi.clickableUrl("Windows Performance Recorder", "https://learn.microsoft.com/windows-hardware/test/wpt/");
        }
        if (report.wpaExporter == null || !report.wpaExporter.found) {
            ChatUi.clickableUrl("WPAExporter (Windows ADK)", "https://learn.microsoft.com/windows-hardware/test/wpt/");
        }
        if (report.tracerpt == null || !report.tracerpt.found) {
            ChatUi.clickableUrl("tracerpt (Windows tools)", "https://learn.microsoft.com/windows-server/administration/windows-commands/tracerpt");
        }
        if (report.typeperf == null || !report.typeperf.found) {
            ChatUi.clickableUrl("typeperf (Windows perf counters)", "https://learn.microsoft.com/windows-server/administration/windows-commands/typeperf");
        }
        if (report.nvidiaSmi == null || !report.nvidiaSmi.found) {
            ChatUi.clickableUrl("nvidia-smi", "https://developer.nvidia.com/system-management-interface");
        }
    }

    private void statusLine(String name, boolean ok) {
        if (ok) {
            ChatUi.success("[FOUND] " + name);
        } else {
            ChatUi.error("[MISSING] " + name);
        }
    }

    private CapturePlan buildCapturePlan(Mode mode) {
        CapturePlan plan = new CapturePlan();
        if (mode == Mode.FULL) {
            plan.presentMon = true;
            plan.jfr = true;
            plan.perfCounters = true;
            plan.typeperf = true;
            plan.processContention = true;
            plan.nvidiaSmi = true;
            plan.spark = true;
            plan.nsys = true;
            plan.wpr = true;
            return plan;
        }
        if (mode == Mode.NORMAL) {
            plan.presentMon = true;
            plan.jfr = true;
            plan.perfCounters = true;
            plan.typeperf = true;
            plan.processContention = true;
            plan.nvidiaSmi = true;
            plan.spark = true;
            plan.nsys = false;
            plan.wpr = false;
            return plan;
        }

        InsigniaConfig cfg = InsigniaConfig.getInstance();
        plan.presentMon = cfg.diagnoseCustomPresentMon;
        plan.jfr = cfg.diagnoseCustomJfr;
        plan.perfCounters = cfg.diagnoseCustomPerfCounters;
        plan.typeperf = cfg.diagnoseCustomTypeperf;
        plan.processContention = cfg.diagnoseCustomProcessContention;
        plan.nvidiaSmi = cfg.diagnoseCustomNvidiaSmi;
        plan.spark = cfg.diagnoseCustomSpark;
        plan.nsys = cfg.diagnoseCustomNsight;
        plan.wpr = cfg.diagnoseCustomWpr;
        return plan;
    }

    public void start(Mode mode, FabricClientCommandSource source) {
        start(mode, DEFAULT_CAPTURE_SECONDS, source);
    }

    public void start(Mode mode, int captureSeconds, FabricClientCommandSource source) {
        if (active.get()) {
            ChatUi.warn("Diagnostics already running.");
            if (activeRunDir != null) {
                ChatUi.info("Active run: " + activeRunDir.toAbsolutePath());
            }
            return;
        }
        if (!ToolDetection.isWindows()) {
            ChatUi.error("Diagnostics currently supports Windows only.");
            return;
        }
        CapturePlan plan = buildCapturePlan(mode);
        if (!plan.anyEnabled()) {
            ChatUi.error("No profilers enabled for /diagnose " + mode.name().toLowerCase(Locale.ROOT) + ".");
            ChatUi.hintGray("Open /insignia -> Profiler and enable at least one toggle.");
            return;
        }
        ToolDetection.DetectionReport report = ToolDetection.detectAll();
        if (plan.jfr && !report.jfrAvailable) {
            ChatUi.error("Cannot run diagnose: JFR is unavailable in this Java runtime.");
            return;
        }
        if (plan.presentMon && (report.presentMon == null || !report.presentMon.found)) {
            ChatUi.error("Cannot run diagnose: PresentMon is missing.");
            ChatUi.hintGray("Install command: winget install --id=Intel.PresentMon.Console -e");
            showSetupHelp(source);
            return;
        }
        if (plan.typeperf && (report.typeperf == null || !report.typeperf.found)) {
            ChatUi.error("Cannot run diagnose: typeperf is missing.");
            showSetupHelp(source);
            return;
        }
        if (!active.compareAndSet(false, true)) {
            ChatUi.warn("Diagnostics already running.");
            return;
        }
        int effectiveCaptureSeconds = Math.max(5, captureSeconds);
        ChatUi.info("Starting diagnostics (" + effectiveCaptureSeconds + "s)...");
        executor.submit(() -> runCapture(mode, plan, report, effectiveCaptureSeconds));
    }

    private void runCapture(Mode mode, CapturePlan plan, ToolDetection.DetectionReport report, int captureSeconds) {
        List<String> notes = Collections.synchronizedList(new ArrayList<>());
        List<String> working = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        String sparkUrl = "not available";
        Path runRootDir = null;
        Path runDir = null;
        PresentMonController presentMon = null;
        JfrController jfr = null;
        Instant sparkDispatchTime = null;
        NsysStartMode nsysMode = NsysStartMode.NOT_STARTED;
        String nsysSession = null;
        WprStartStatus wprStatus = WprStartStatus.NOT_AVAILABLE;
        PerfCounterSampler perfSampler = null;
        TypeperfController typeperf = null;
        boolean typeperfStarted = false;
        NvidiaSmiController nvidiaSmi = null;
        boolean nvidiaSmiStarted = false;
        ProcessContentionSampler contentionSampler = null;
        Path nsysStartLog = null;
        Path nsysStopLog = null;
        Path wprStartLog = null;
        Path wprStopLog = null;
        Path latestLog = null;
        Path stateFile = null;
        ProfilerSessionState.State state = null;
        try {
            elevatedHelper = null;
            sparkDispatchAt = null;
            sparkUrlFromChat = null;
            sparkUrlCapturedAt = null;
            runRootDir = createRunDir();
            runDir = runRootDir.resolve("results");
            Files.createDirectories(runDir);
            activeRunDir = runRootDir;
            Path logsDir = runDir.resolve("logs");
            Files.createDirectories(logsDir);
            latestLog = runDir.resolve("diagnose.log");
            stateFile = runRootDir.getParent().resolve(ACTIVE_STATE_FILE);
            Path presentStdout = logsDir.resolve("presentmon.stdout.log");
            Path presentStderr = logsDir.resolve("presentmon.stderr.log");
            Path jfrLog = logsDir.resolve("jfr.log");
            Path countersCsv = runDir.resolve("counters.csv");
            Path countersLog = logsDir.resolve("counters.log");
            Path hardwareCountersCsv = runDir.resolve("windows_hardware_counters.csv");
            Path typeperfStdout = logsDir.resolve("typeperf.stdout.log");
            Path typeperfStderr = logsDir.resolve("typeperf.stderr.log");
            Path nvidiaSmiCsv = runDir.resolve("nvidia_smi.csv");
            Path nvidiaSmiStderr = logsDir.resolve("nvidia-smi.stderr.log");
            Path contentionCsv = runDir.resolve("process_contention.csv");
            Path contentionLog = logsDir.resolve("process_contention.log");

            log(latestLog, "Run started. mode=" + mode + " runDir=" + runRootDir + " resultsDir=" + runDir);
            recoverStaleSessions(report, runDir, latestLog, stateFile, notes);

            Path presentCsv = plan.presentMon ? runDir.resolve("presentmon.csv") : null;
            Path recordingJfr = plan.jfr ? runDir.resolve("recording.jfr") : null;
            Path badFrames = (plan.jfr && plan.presentMon) ? runDir.resolve("bad_frames.json") : null;
            Path systemInfo = runDir.resolve("system_info.json");
            Path readme = runDir.resolve("README_ANALYZE.txt");

            if (plan.presentMon) {
                presentMon = new PresentMonController(report.presentMon, runDir, presentCsv, presentStdout, presentStderr, captureSeconds);
            }
            if (plan.jfr) {
                jfr = new JfrController(jfrLog);
            }
            if (plan.perfCounters) {
                perfSampler = new PerfCounterSampler(countersCsv, countersLog, 100L);
            }
            if (plan.typeperf) {
                typeperf = new TypeperfController(report.typeperf, runDir, hardwareCountersCsv, typeperfStdout, typeperfStderr);
            }
            if (plan.nvidiaSmi) {
                nvidiaSmi = new NvidiaSmiController(report.nvidiaSmi, runDir, nvidiaSmiCsv, nvidiaSmiStderr);
            }
            if (plan.processContention) {
                contentionSampler = new ProcessContentionSampler(contentionCsv, contentionLog, 1000L, 12);
            }
            state = new ProfilerSessionState.State();
            state.ownerPid = ProcessHandle.current().pid();
            state.runDir = runRootDir.toString();
            state.createdAt = Instant.now().toString();
            ProfilerSessionState.save(stateFile, state);

            if (plan.wpr) {
                clearExistingWprIfRunning(report, runDir, notes);
            }

            if (plan.jfr && jfr != null) {
                log(latestLog, "Starting JFR recording...");
                jfr.start();
                announceStarted("JFR");
            }
            if (plan.presentMon && presentMon != null) {
                log(latestLog, "Starting PresentMon capture...");
                presentMon.start();
                announceStarted("PresentMon");
            }
            if (plan.perfCounters && perfSampler != null) {
                log(latestLog, "Starting perf counter sampler...");
                perfSampler.start();
                announceStarted("perf counters");
            }
            if (plan.typeperf && typeperf != null) {
                typeperfStarted = typeperf.start(notes);
                if (!typeperfStarted) {
                    throw new IllegalStateException("typeperf failed to start (required profiler). Check typeperf.stderr.log");
                }
                announceStarted("typeperf");
            }
            if (plan.processContention && contentionSampler != null) {
                log(latestLog, "Starting process contention sampler...");
                contentionSampler.start();
                announceStarted("process contention");
            }
            if (plan.nvidiaSmi && nvidiaSmi != null) {
                nvidiaSmiStarted = nvidiaSmi.start(notes);
                if (nvidiaSmiStarted) {
                    announceStarted("nvidia-smi");
                }
            }
            log(latestLog, "Mandatory captures started.");

            nsysStartLog = logsDir.resolve("nsys-start.log");
            nsysStopLog = logsDir.resolve("nsys-stop.log");
            wprStartLog = logsDir.resolve("wpr-start.log");
            wprStopLog = logsDir.resolve("wpr-stop.log");
            nsysSession = "insignia_" + runRootDir.getFileName();
            log(latestLog, "Optional tool detection summary: mode=" + mode + ", plan[spark=" + plan.spark + ",nsys=" + plan.nsys + ",wpr=" + plan.wpr + "] detected[spark=" + report.sparkPresent
                + ", nsys=" + (report.nsys != null && report.nsys.found)
                + ", wpr=" + (report.wpr != null && report.wpr.found)
                + ", wpaexporter=" + (report.wpaExporter != null && report.wpaExporter.found)
                + ", tracerpt=" + (report.tracerpt != null && report.tracerpt.found)
                + ", nvidia-smi=" + (report.nvidiaSmi != null && report.nvidiaSmi.found));
            if ((plan.nsys || plan.wpr) && ((report.nsys != null && report.nsys.found) || (report.wpr != null && report.wpr.found))) {
                ensureElevatedHelper(runDir, notes);
            }
            final Path runDirFinal = runDir;
            final Path nsysStartLogFinal = nsysStartLog;
            final Path wprStartLogFinal = wprStartLog;
            final String nsysSessionFinal = nsysSession;
            final List<String> notesFinal = notes;
            CompletableFuture<Instant> sparkFuture;
            if (plan.spark && report.sparkPresent) {
                sparkFuture = CompletableFuture.supplyAsync(() -> startSparkCapture(logsDir.resolve("spark.log"), notesFinal, captureSeconds));
            } else {
                if (plan.spark) {
                    ChatUi.hintGray("to profile the game itself get the mod spark profiler");
                }
                sparkFuture = CompletableFuture.completedFuture(null);
            }
            CompletableFuture<NsysStartMode> nsysFuture = plan.nsys
                ? CompletableFuture.supplyAsync(() -> startOptionalNsys(report, runDirFinal, nsysStartLogFinal, notesFinal, nsysSessionFinal, captureSeconds))
                : CompletableFuture.completedFuture(NsysStartMode.NOT_STARTED);
            CompletableFuture<WprStartStatus> wprFuture = plan.wpr
                ? CompletableFuture.supplyAsync(() -> startOptionalWpr(report, runDirFinal, wprStartLogFinal, notesFinal))
                : CompletableFuture.completedFuture(WprStartStatus.NOT_AVAILABLE);
            sparkDispatchTime = sparkFuture.join();
            nsysMode = nsysFuture.join();
            boolean nsysWasTimedProfile = (nsysMode == NsysStartMode.TIMED_PROFILE);
            wprStatus = wprFuture.join();
            if (state != null) {
                state.nsysStartedByUs = nsysMode == NsysStartMode.SESSION;
                state.nsysSession = nsysMode == NsysStartMode.SESSION ? nsysSession : "";
                state.wprStartedByUs = wprStatus == WprStartStatus.STARTED
                    || wprStatus == WprStartStatus.STARTED_ELEVATED
                    || wprStatus == WprStartStatus.ALREADY_RUNNING;
                state.wprStartedElevated = wprStatus == WprStartStatus.STARTED_ELEVATED;
                ProfilerSessionState.save(stateFile, state);
            }
            log(latestLog, "Optional capture status: nsysMode=" + nsysMode + ", wprStatus=" + wprStatus);

            long started = System.currentTimeMillis();
            long captureMs = captureSeconds * 1000L;
            // Deadline by which the timed-profile nsys process should have written its .nsys-rep.
            // nsys --duration is set to captureSeconds+15, so it finishes ~15s after capture ends.
            long nsysTimedDeadline = started + (long)(captureSeconds + 15) * 1000L;
            while (System.currentTimeMillis() - started < captureMs) {
                Thread.sleep(250L);
            }

            stopOptionalExportersParallel(
                report,
                runDir,
                notes,
                nsysMode == NsysStartMode.SESSION,
                nsysStopLog,
                nsysSession,
                wprStatus == WprStartStatus.STARTED
                    || wprStatus == WprStartStatus.STARTED_ELEVATED
                    || wprStatus == WprStartStatus.ALREADY_RUNNING,
                wprStatus == WprStartStatus.STARTED_ELEVATED,
                wprStopLog
            );
            nsysMode = NsysStartMode.NOT_STARTED;
            if (wprStatus == WprStartStatus.STARTED
                || wprStatus == WprStartStatus.STARTED_ELEVATED
                || wprStatus == WprStartStatus.ALREADY_RUNNING) {
                wprStatus = WprStartStatus.STOPPED;
            }
            if (perfSampler != null) {
                perfSampler.stop();
            }
            if (typeperfStarted && typeperf != null) {
                typeperf.stop(notes);
                typeperfStarted = false;
            }
            if (contentionSampler != null) {
                contentionSampler.stop();
            }
            if (nvidiaSmiStarted && nvidiaSmi != null) {
                nvidiaSmi.stop(notes);
                nvidiaSmiStarted = false;
            }
            copySparkOutputsIfAny(sparkDispatchTime, runDir, notes);
            sparkUrl = readSparkUrl(runDir);
            if (plan.presentMon && presentMon != null) {
                log(latestLog, "Stopping PresentMon...");
                presentMon.stop();
                announceExportIfExists("PresentMon", presentCsv);
            }
            if (plan.jfr && jfr != null && recordingJfr != null) {
                log(latestLog, "Stopping JFR and dumping recording...");
                jfr.stopAndDump(recordingJfr);
                announceExportIfExists("JFR", recordingJfr);
            }
            log(latestLog, "Capture completed. Exporting.");

            ChatUi.info("Exporting profiler results...");
            if (plan.jfr && plan.presentMon && recordingJfr != null && presentCsv != null && badFrames != null && jfr != null) {
                log(latestLog, "Running JFR parser against recording.jfr + presentmon.csv...");
                new JfrParser().parse(recordingJfr, presentCsv, badFrames, jfr.startWall(), jfr.endWall());
                announceExportIfExists("JFR bad frame analysis", badFrames);
            }
            if (plan.wpr) {
                log(latestLog, "Running ETL export pipeline...");
                new EtlExportController(report, runDir, logsDir, notes).exportIfPossible();
                announceExportIfExists("WPR trace", runDir.resolve("trace.etl"));
                announceExportIfExists("WPA export", runDir.resolve("wpa_exports"));
                announceExportIfExists("tracerpt export", runDir.resolve("trace_raw.csv"));
            }
            if (nsysWasTimedProfile) {
                Path nsysRep = runDir.resolve("nsys-report.nsys-rep");
                long nsysWaitDeadline = nsysTimedDeadline + 5_000L; // 5s grace past expected finish
                log(latestLog, "Waiting for nsys timed profile report (deadline in " + Math.max(0, nsysWaitDeadline - System.currentTimeMillis()) + "ms)...");
                while (!Files.exists(nsysRep) && System.currentTimeMillis() < nsysWaitDeadline) {
                    Thread.sleep(500L);
                }
                if (Files.exists(nsysRep)) {
                    notes.add("nsys timed profile report appeared before bundle export.");
                    log(latestLog, "nsys-report.nsys-rep found.");
                } else {
                    notes.add("nsys timed profile: report not found within wait window; profile may still be running.");
                    log(latestLog, "nsys-report.nsys-rep not found after wait; proceeding.");
                }
            }
            log(latestLog, "Collecting external Nsight reports if present...");
            NsysExternalCollector.collectPossibleReports(runDir, jfr != null ? jfr.startWall() : Instant.now(), notes);
            if (plan.nsys) {
                announceExportIfExists("Nsight Systems", runDir.resolve("nsys-report.nsys-rep"));
            }
            if (plan.typeperf) {
                announceExportIfExists("typeperf", hardwareCountersCsv);
            }
            if (plan.nvidiaSmi) {
                announceExportIfExists("nvidia-smi", nvidiaSmiCsv);
            }
            if (plan.processContention) {
                announceExportIfExists("process contention", contentionCsv);
            }

            log(latestLog, "Writing system_info.json and README_ANALYZE.txt...");
            long targetPid = presentMon != null ? presentMon.targetPid() : ProcessHandle.current().pid();
            Instant startWall = jfr != null ? jfr.startWall() : Instant.now();
            Instant endWall = jfr != null ? jfr.endWall() : Instant.now();
            BundleWriter.writeSystemInfo(systemInfo, mode, report, targetPid, startWall, endWall);
            if (plan.nsys || plan.wpr) {
                BundleWriter.includeFullModeFiles(runDir, notes);
            }
            BundleWriter.writeReadme(readme, mode, report.sparkPresent, notes);

            List<Path> required = new ArrayList<>();
            if (presentCsv != null) {
                required.add(presentCsv);
                required.add(presentStdout);
                required.add(presentStderr);
            }
            if (recordingJfr != null) {
                required.add(recordingJfr);
                required.add(jfrLog);
            }
            if (badFrames != null) {
                required.add(badFrames);
            }
            required.add(systemInfo);
            required.add(readme);
            required.add(latestLog);
            if (plan.perfCounters) {
                required.add(countersCsv);
            }
            if (plan.typeperf) {
                required.add(hardwareCountersCsv);
            }
            List<Path> optional = discoverOptionalFiles(runDir, required);
            for (Path req : required) {
                if (!Files.exists(req)) {
                    throw new IllegalStateException("Required artifact missing before export: " + req.getFileName());
                }
            }
            Path manifest = runDir.resolve("index.json");
            BundleWriter.writeManifest(manifest, report, required, optional, notes);
            List<Path> requiredWithManifest = new ArrayList<>(required);
            requiredWithManifest.add(manifest);
            log(latestLog, "Manifest written. requiredFiles=" + requiredWithManifest.size() + ", optionalCandidates=" + optional.size());

            String zipBaseName = "mc-diagnose-" + runRootDir.getFileName();
            ChatUi.info("zipping, this might take a second");
            List<Path> shareZips = BundleWriter.zipResultsPartitioned(
                runRootDir,
                zipBaseName,
                requiredWithManifest,
                optional,
                notes,
                SHARE_ZIP_MAX_BYTES,
                SHARE_ZIP_MAX_PARTS
            );
            for (Path zip : shareZips) {
                log(latestLog, "Share zip completed: " + zip);
            }

            collectProfilerStatus(runRootDir, runDir, report, plan, working, failed);
            sendFinalSummary(true, runRootDir, working, failed, sparkUrl);
        } catch (Exception e) {
            TaggerMod.LOGGER.error("[Diagnose] Capture failed", e);
            if (runRootDir != null && runDir != null) {
                failed.add("diagnostics: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                collectProfilerStatus(runRootDir, runDir, report, plan, working, failed);
                sparkUrl = readSparkUrl(runDir);
                sendFinalSummary(false, runRootDir, working, failed, sparkUrl);
            } else {
                ChatUi.error("Diagnostics failed before run directory creation: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        } finally {
            try {
                if (elevatedHelper != null) {
                    elevatedHelper.shutdown();
                }
            } catch (Exception ignored) {
            }
            try {
                if (nsysMode == NsysStartMode.SESSION && runDir != null && nsysStopLog != null && nsysSession != null) {
                    stopOptionalNsys(report, runDir, nsysStopLog, notes, nsysSession);
                }
            } catch (Exception e) {
                TaggerMod.LOGGER.warn("[Diagnose] Failed to stop nsys during cleanup", e);
            }
            try {
                if ((wprStatus == WprStartStatus.STARTED
                    || wprStatus == WprStartStatus.STARTED_ELEVATED
                    || wprStatus == WprStartStatus.ALREADY_RUNNING) && runDir != null && wprStopLog != null) {
                    stopOptionalWpr(report, runDir, wprStopLog, notes, wprStatus == WprStartStatus.STARTED_ELEVATED);
                }
            } catch (Exception e) {
                TaggerMod.LOGGER.warn("[Diagnose] Failed to stop WPR during cleanup", e);
            }
            if (typeperfStarted && typeperf != null) {
                typeperf.stop(notes);
            }
            if (contentionSampler != null) {
                contentionSampler.stop();
            }
            if (nvidiaSmiStarted && nvidiaSmi != null) {
                nvidiaSmi.stop(notes);
            }
            try {
                if (presentMon != null) {
                    presentMon.stop();
                }
            } catch (Exception e) {
                TaggerMod.LOGGER.warn("[Diagnose] Failed to stop PresentMon during cleanup", e);
            }
            try {
                if (jfr != null && jfr.endWall() == null) {
                    Path fallback = runDir != null ? runDir.resolve("recording.jfr") : null;
                    if (fallback != null) {
                        jfr.stopAndDump(fallback);
                    }
                }
            } catch (Exception e) {
                TaggerMod.LOGGER.warn("[Diagnose] Failed to stop JFR during cleanup", e);
            }
            if (perfSampler != null) {
                perfSampler.stop();
            }
            if (stateFile != null) {
                ProfilerSessionState.clear(stateFile);
            }
            active.set(false);
            activeRunDir = null;
            sparkDispatchAt = null;
            sparkUrlFromChat = null;
            sparkUrlCapturedAt = null;
        }
    }

    private NsysStartMode startOptionalNsys(ToolDetection.DetectionReport report, Path runDir, Path logBaseFile, List<String> notes, String sessionName, int captureSeconds) {
        if (report.nsys == null || !report.nsys.found || report.nsys.path == null) {
            return NsysStartMode.NOT_STARTED;
        }
        try {
            Path out = runDir.resolve("nsys-report");
            Path stdout = replaceSuffix(logBaseFile, ".stdout.log");
            Path stderr = replaceSuffix(logBaseFile, ".stderr.log");
            List<String> baseArgs = List.of(
                "start",
                "--session-new",
                sessionName,
                "-o",
                out.toAbsolutePath().toString(),
                "--force-overwrite",
                "true"
            );
            List<String> start = List.of(
                report.nsys.path.toString(),
                baseArgs.get(0),
                baseArgs.get(1),
                baseArgs.get(2),
                baseArgs.get(3),
                baseArgs.get(4),
                baseArgs.get(5),
                baseArgs.get(6)
            );
            if (elevatedHelper != null && elevatedHelper.isReady()) {
                ExportCommandResult elevated = runNsysElevated(report, runDir, baseArgs, replaceSuffix(logBaseFile, "-uac.stdout.log"), replaceSuffix(logBaseFile, "-uac.stderr.log"));
                if (elevated.success()) {
                    notes.add("nsys started via elevated helper.");
                    announceStarted("Nsight Systems");
                    return NsysStartMode.SESSION;
                }
                notes.add("nsys elevated helper start failed with exitCode=" + elevated.exitCode() + " timedOut=" + elevated.timedOut());
                if (launchNsysTimedProfileConsole(report, runDir, logBaseFile, notes, captureSeconds)) {
                    announceStarted("Nsight Systems");
                    return NsysStartMode.TIMED_PROFILE;
                }
                return NsysStartMode.NOT_STARTED;
            }
            ProcessRunner.ProcessResult result = ProcessRunner.run(start, runDir, stdout, stderr, Duration.ofSeconds(10));
            if (result.timedOut() || result.exitCode() != 0) {
                notes.add("nsys start failed with exitCode=" + result.exitCode() + " timedOut=" + result.timedOut());
                ChatUi.warn("Nsight Systems needs admin rights. Approve the UAC prompt if shown.");
                ensureElevatedHelper(runDir, notes);
                ExportCommandResult elevated = runNsysElevated(report, runDir, baseArgs, replaceSuffix(logBaseFile, "-uac.stdout.log"), replaceSuffix(logBaseFile, "-uac.stderr.log"));
                if (!elevated.success()) {
                    notes.add("nsys UAC fallback failed with exitCode=" + elevated.exitCode() + " timedOut=" + elevated.timedOut());
                    if (launchNsysTimedProfileConsole(report, runDir, logBaseFile, notes, captureSeconds)) {
                        announceStarted("Nsight Systems");
                        return NsysStartMode.TIMED_PROFILE;
                    }
                    return NsysStartMode.NOT_STARTED;
                }
                notes.add("nsys started via elevated UAC fallback.");
                announceStarted("Nsight Systems");
                return NsysStartMode.SESSION;
            }
            String statusLower = readText(stderr).toLowerCase(Locale.ROOT);
            if (statusLower.contains("requires administrative privileges")) {
                ChatUi.warn("Nsight Systems needs admin rights. Approve the UAC prompt if shown.");
                ensureElevatedHelper(runDir, notes);
                try {
                    // If non-elevated start created a degraded session, cancel it before elevated restart.
                    Path cancelStdout = replaceSuffix(logBaseFile, "-cancel.stdout.log");
                    Path cancelStderr = replaceSuffix(logBaseFile, "-cancel.stderr.log");
                    List<String> cancelCmd = List.of(report.nsys.path.toString(), "cancel", "--session", sessionName);
                    ProcessRunner.run(cancelCmd, runDir, cancelStdout, cancelStderr, Duration.ofSeconds(10), false);
                } catch (Exception ignored) {
                }
                ExportCommandResult elevated = runNsysElevated(report, runDir, baseArgs, replaceSuffix(logBaseFile, "-uac.stdout.log"), replaceSuffix(logBaseFile, "-uac.stderr.log"));
                if (elevated.success()) {
                    notes.add("nsys restarted via elevated UAC fallback.");
                    announceStarted("Nsight Systems");
                    return NsysStartMode.SESSION;
                }
                notes.add("nsys UAC fallback failed with exitCode=" + elevated.exitCode() + " timedOut=" + elevated.timedOut());
                if (launchNsysTimedProfileConsole(report, runDir, logBaseFile, notes, captureSeconds)) {
                    announceStarted("Nsight Systems");
                    return NsysStartMode.TIMED_PROFILE;
                }
                return NsysStartMode.NOT_STARTED;
            }
            notes.add("Attempted nsys session start.");
            announceStarted("Nsight Systems");
            return NsysStartMode.SESSION;
        } catch (Exception e) {
            notes.add("nsys capture failed: " + e.getMessage());
            TaggerMod.LOGGER.warn("[Diagnose] nsys capture failed", e);
            if (launchNsysTimedProfileConsole(report, runDir, logBaseFile, notes, captureSeconds)) {
                announceStarted("Nsight Systems");
                return NsysStartMode.TIMED_PROFILE;
            }
            return NsysStartMode.NOT_STARTED;
        }
    }

    private void stopOptionalNsys(ToolDetection.DetectionReport report, Path runDir, Path logBaseFile, List<String> notes, String sessionName) {
        try {
            Path stdout = replaceSuffix(logBaseFile, ".stdout.log");
            Path stderr = replaceSuffix(logBaseFile, ".stderr.log");
            ExportCommandResult result = runNsysStopWithFallback(report, runDir, sessionName, stdout, stderr);
            if (!result.success()) {
                notes.add("nsys stop failed with exitCode=" + result.exitCode() + " timedOut=" + result.timedOut());
                return;
            }
            notes.add("Attempted nsys session stop.");
        } catch (Exception e) {
            notes.add("nsys stop failed: " + e.getMessage());
            TaggerMod.LOGGER.warn("[Diagnose] nsys stop failed", e);
        }
    }

    private boolean launchNsysTimedProfileConsole(ToolDetection.DetectionReport report, Path runDir, Path logBaseFile, List<String> notes, int captureSeconds) {
        try {
            Path stdout = replaceSuffix(logBaseFile, "-profile-console.stdout.log");
            Path stderr = replaceSuffix(logBaseFile, "-profile-console.stderr.log");
            String exe = report.nsys.path.toAbsolutePath().toString().replace("'", "''");
            String out = runDir.resolve("nsys-report").toAbsolutePath().toString().replace("'", "''");
            // Profile duration is tied to the requested diagnose window plus a small buffer so the
            // .nsys-rep is written before (or shortly after) bundle export begins.
            int nsysDuration = captureSeconds + 15;
            String script =
                quickEditDisableScript() +
                "$ErrorActionPreference='Stop'; " +
                "$a=@('profile','--trace=none','--sample=none','--cpuctxsw=none','--duration','" + nsysDuration + "','--force-overwrite=true','-o','" + out + "','cmd','/c','timeout /t " + (nsysDuration + 1) + " >nul'); " +
                "$p=Start-Process -FilePath '" + exe + "' -ArgumentList $a -WindowStyle Minimized -PassThru; " +
                "if ($null -eq $p) { exit 1 }; exit 0";
            List<String> cmd = List.of("powershell.exe", "-NoProfile", "-Command", script);
            ProcessRunner.ProcessResult result = ProcessRunner.run(cmd, runDir, stdout, stderr, Duration.ofSeconds(20), false);
            notes.add("nsys timed profile console fallback exitCode=" + result.exitCode() + " timedOut=" + result.timedOut() + " duration=" + nsysDuration + "s");
            return !result.timedOut() && result.exitCode() == 0;
        } catch (Exception e) {
            notes.add("nsys timed profile console fallback failed: " + e.getMessage());
            return false;
        }
    }

    private WprStartStatus startOptionalWpr(ToolDetection.DetectionReport report, Path runDir, Path logBaseFile, List<String> notes) {
        if (report.wpr == null || !report.wpr.found || report.wpr.path == null) {
            return WprStartStatus.NOT_AVAILABLE;
        }
        try {
            if (elevatedHelper != null && elevatedHelper.isReady()) {
                if (startWprElevated(report, runDir, notes)) {
                    notes.add("Started WPR via elevated helper.");
                    announceStarted("WPR");
                    return WprStartStatus.STARTED_ELEVATED;
                }
            }
            Path stdout = replaceSuffix(logBaseFile, ".stdout.log");
            Path stderr = replaceSuffix(logBaseFile, ".stderr.log");
            List<String> start = List.of(report.wpr.path.toString(), "-start", "generalprofile", "-filemode");
            ProcessRunner.ProcessResult result = ProcessRunner.run(start, runDir, stdout, stderr, Duration.ofSeconds(15));
            if (result.timedOut()) {
                notes.add("WPR start failed with exitCode=" + result.exitCode() + " timedOut=" + result.timedOut());
                return WprStartStatus.FAILED;
            }
            if (result.exitCode() != 0) {
                String err = readText(stderr).toLowerCase(Locale.ROOT);
                if (err.contains("profiles are already running") || err.contains("0xc5583001")) {
                    notes.add("WPR already had running profiles; issuing wpr -cancel and retrying start.");
                    if (!runWprCancel(report, runDir, notes, false)) {
                        if (!runWprCancel(report, runDir, notes, true)) {
                            notes.add("WPR cancel failed; will attempt stop/export at end.");
                            return WprStartStatus.ALREADY_RUNNING;
                        }
                    }
                    ProcessRunner.ProcessResult retry = ProcessRunner.run(start, runDir, stdout, stderr, Duration.ofSeconds(15));
                    if (!retry.timedOut() && retry.exitCode() == 0) {
                        notes.add("WPR restart succeeded after cancel.");
                        announceStarted("WPR");
                        return WprStartStatus.STARTED;
                    }
                    notes.add("WPR restart after cancel failed with exitCode=" + retry.exitCode() + " timedOut=" + retry.timedOut());
                    return WprStartStatus.FAILED;
                }
                if (requiresElevation(err)) {
                    ChatUi.warn("WPR needs admin rights. Approve the UAC prompt if shown.");
                    ensureElevatedHelper(runDir, notes);
                    if (startWprElevated(report, runDir, notes)) {
                        notes.add("Started WPR via elevated UAC fallback.");
                        announceStarted("WPR");
                        return WprStartStatus.STARTED_ELEVATED;
                    }
                }
                notes.add("WPR start failed with exitCode=" + result.exitCode() + " timedOut=" + result.timedOut());
                return WprStartStatus.FAILED;
            }
            notes.add("Started WPR general profile.");
            announceStarted("WPR");
            return WprStartStatus.STARTED;
        } catch (Exception e) {
            notes.add("WPR start failed: " + e.getMessage());
            TaggerMod.LOGGER.warn("[Diagnose] WPR start failed", e);
            return WprStartStatus.FAILED;
        }
    }

    private void stopOptionalWpr(ToolDetection.DetectionReport report, Path runDir, Path logBaseFile, List<String> notes, boolean elevated) {
        try {
            Path stdout = replaceSuffix(logBaseFile, ".stdout.log");
            Path stderr = replaceSuffix(logBaseFile, ".stderr.log");
            Path out = runDir.resolve("trace.etl");
            ExportCommandResult result = elevated
                ? runWprStopElevated(report, runDir, out, stdout, stderr)
                : runExportCommandWithProgress("WPR", List.of(report.wpr.path.toString(), "-stop", out.toAbsolutePath().toString()), runDir, stdout, stderr);
            if (!result.success()) {
                notes.add("WPR stop failed with exitCode=" + result.exitCode() + " timedOut=" + result.timedOut());
                return;
            }
            notes.add("Stopped WPR trace.");
        } catch (Exception e) {
            notes.add("WPR stop failed: " + e.getMessage());
            TaggerMod.LOGGER.warn("[Diagnose] WPR stop failed", e);
        }
    }

    private void stopOptionalExportersParallel(
        ToolDetection.DetectionReport report,
        Path runDir,
        List<String> notes,
        boolean nsysStarted,
        Path nsysStopLog,
        String nsysSession,
        boolean wprRunning,
        boolean wprElevated,
        Path wprStopLog) {
        List<ExportTask> tasks = new ArrayList<>();
        if (nsysStarted && report.nsys != null && report.nsys.found && report.nsys.path != null && nsysStopLog != null && nsysSession != null) {
            Path stdout = replaceSuffix(nsysStopLog, ".stdout.log");
            Path stderr = replaceSuffix(nsysStopLog, ".stderr.log");
            tasks.add(new ExportTask("Nsight Systems", CompletableFuture.supplyAsync(() -> runNsysStopWithFallback(report, runDir, nsysSession, stdout, stderr))));
        }
        if (wprRunning && report.wpr != null && report.wpr.found && report.wpr.path != null && wprStopLog != null) {
            Path stdout = replaceSuffix(wprStopLog, ".stdout.log");
            Path stderr = replaceSuffix(wprStopLog, ".stderr.log");
            Path out = runDir.resolve("trace.etl");
            if (wprElevated) {
                tasks.add(new ExportTask("WPR", CompletableFuture.supplyAsync(() -> runWprStopElevated(report, runDir, out, stdout, stderr))));
            } else {
                List<String> cmd = List.of(report.wpr.path.toString(), "-stop", out.toAbsolutePath().toString());
                tasks.add(new ExportTask("WPR", CompletableFuture.supplyAsync(() -> runExportCommand(cmd, runDir, stdout, stderr))));
            }
        }
        if (tasks.isEmpty()) {
            return;
        }

        long waitStart = System.currentTimeMillis();
        boolean waitingNoticeSent = false;
        while (true) {
            boolean allDone = true;
            for (ExportTask task : tasks) {
                if (!task.future().isDone()) {
                    allDone = false;
                    break;
                }
            }
            if (allDone) {
                break;
            }

            long elapsed = System.currentTimeMillis() - waitStart;
            if (!waitingNoticeSent && elapsed >= EXPORT_WAIT_NOTICE_MS) {
                for (ExportTask task : tasks) {
                    if (!task.future().isDone()) {
                        ChatUi.warn("waiting on " + task.name() + " to finish exporting");
                    }
                }
                waitingNoticeSent = true;
            }
            if (elapsed >= EXPORT_TIMEOUT_MS) {
                break;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        for (ExportTask task : tasks) {
            ExportCommandResult result;
            if (task.future().isDone()) {
                result = task.future().join();
            } else {
                result = new ExportCommandResult(false, true, -1);
            }
            boolean artifactOk = result.success() && exportArtifactExists(task.name(), runDir);
            if (artifactOk) {
                ChatUi.success(task.name() + " Successfully Exported");
                notes.add(task.name() + " export succeeded.");
            } else if (result.timedOut()) {
                ChatUi.error(task.name() + " timed out while exporting");
                notes.add(task.name() + " export timed out.");
            } else {
                if (result.success() && !artifactOk) {
                    notes.add(task.name() + " export command succeeded but artifact not found.");
                }
                notes.add(task.name() + " export failed with exitCode=" + result.exitCode());
            }
        }
    }

    private Instant startSparkCapture(Path sparkLog, List<String> notes, int captureSeconds) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() == null) {
            notes.add("spark present but no network handler to dispatch command.");
            return null;
        }
        try {
            int timeout = Math.max(5, captureSeconds);
            Instant dispatchAt = Instant.now();
            this.sparkDispatchAt = dispatchAt;
            client.execute(() -> {
                if (client.getNetworkHandler() != null) {
                    client.getNetworkHandler().sendChatCommand("sparkc profiler --timeout " + timeout + " --interval 1");
                }
            });
            notes.add("Dispatched spark profiler command.");
            Files.writeString(sparkLog, "spark command dispatched at " + dispatchAt + "\n");
            return dispatchAt;
        } catch (Exception e) {
            notes.add("spark capture dispatch failed: " + e.getMessage());
            return null;
        }
    }

    private void copySparkOutputsIfAny(Instant dispatchTime, Path runDir, List<String> notes) {
        if (dispatchTime == null) {
            return;
        }
        int copied = 0;
        try {
            Path sparkDir = FabricLoader.getInstance().getGameDir().resolve("spark");
            if (Files.isDirectory(sparkDir)) {
                try (var stream = Files.walk(sparkDir)) {
                    List<Path> candidates = stream.filter(Files::isRegularFile)
                        .filter(path -> {
                            try {
                                return Files.getLastModifiedTime(path).toInstant().isAfter(dispatchTime.minusSeconds(10));
                            } catch (Exception e) {
                                return false;
                            }
                        })
                        .toList();
                    for (Path candidate : candidates) {
                        Path target = runDir.resolve("spark-" + candidate.getFileName().toString());
                        Files.copy(candidate, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        copied++;
                    }
                }
            } else {
                notes.add("spark output directory not found.");
            }
        } catch (Exception e) {
            notes.add("spark output copy failed: " + e.getMessage());
        }
        copied += fetchSparkFromUrl(dispatchTime, runDir, notes);
        notes.add("Copied spark outputs: " + copied);
    }

    private List<Path> discoverOptionalFiles(Path runDir, List<Path> required) throws Exception {
        java.util.Set<Path> requiredSet = new java.util.HashSet<>();
        for (Path req : required) {
            requiredSet.add(req.toAbsolutePath().normalize());
        }
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(runDir)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                Path normalized = path.toAbsolutePath().normalize();
                if (!requiredSet.contains(normalized)) {
                    files.add(path);
                }
            });
        }
        return files;
    }

    private Path createRunDir() throws Exception {
        Path base = Path.of(System.getProperty("java.io.tmpdir")).resolve("mc-diagnose");
        Files.createDirectories(base);
        String stamp = LocalDateTime.now().format(TS);
        Path runDir = base.resolve(stamp);
        Files.createDirectories(runDir);
        return runDir;
    }

    private void recoverStaleSessions(ToolDetection.DetectionReport report, Path runDir, Path latestLog, Path stateFile, List<String> notes) {
        try {
            var stale = ProfilerSessionState.load(stateFile);
            if (stale.isEmpty()) {
                return;
            }
            ProfilerSessionState.State old = stale.get();
            log(latestLog, "Found stale session state from previous run. ownerPid=" + old.ownerPid + " runDir=" + old.runDir);
            notes.add("Recovered stale profiler session state from prior run.");

            if (old.nsysStartedByUs && old.nsysSession != null && !old.nsysSession.isBlank()
                && report.nsys != null && report.nsys.found && report.nsys.path != null) {
                Path stdout = runDir.resolve("logs").resolve("nsys-recovery.stdout.log");
                Path stderr = runDir.resolve("logs").resolve("nsys-recovery.stderr.log");
                List<String> cmd = List.of(report.nsys.path.toString(), "stop", "--session", old.nsysSession);
                ProcessRunner.ProcessResult result = ProcessRunner.run(cmd, runDir, stdout, stderr, Duration.ofSeconds(25));
                notes.add("Stale nsys cleanup attempted for session " + old.nsysSession + " exit=" + result.exitCode());
            }

            if (old.wprStartedByUs && report.wpr != null && report.wpr.found && report.wpr.path != null) {
                if (runWprCancel(report, runDir, notes, old.wprStartedElevated)) {
                    notes.add("Stale WPR cleanup completed.");
                } else {
                    notes.add("Stale WPR cleanup failed.");
                }
            }
        } catch (Exception e) {
            notes.add("Stale session recovery failed: " + e.getMessage());
            TaggerMod.LOGGER.warn("[Diagnose] stale session recovery failed", e);
        } finally {
            ProfilerSessionState.clear(stateFile);
        }
    }

    private void log(Path diagnoseLog, String line) {
        try {
            String full = Instant.now() + " " + line + "\n";
            Files.writeString(diagnoseLog, full, java.nio.charset.StandardCharsets.UTF_8,
                Files.exists(diagnoseLog)
                    ? java.nio.file.StandardOpenOption.APPEND
                    : java.nio.file.StandardOpenOption.CREATE);
            TaggerMod.LOGGER.info("[Diagnose] {}", line);
        } catch (Exception e) {
            TaggerMod.LOGGER.error("[Diagnose] Failed writing diagnose log", e);
        }
    }

    private Path replaceSuffix(Path base, String newSuffix) {
        String name = base.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot >= 0 ? name.substring(0, dot) : name;
        return base.getParent().resolve(stem + newSuffix);
    }

    private String readText(Path file) {
        try {
            if (file == null || !Files.exists(file)) {
                return "";
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private String readTail(Path file, int maxBytes) {
        try {
            if (file == null || !Files.exists(file)) {
                return "";
            }
            long size = Files.size(file);
            if (size <= maxBytes) {
                return Files.readString(file, StandardCharsets.UTF_8);
            }
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "r")) {
                long offset = size - maxBytes;
                raf.seek(offset);
                byte[] buf = new byte[maxBytes];
                int read = raf.read(buf);
                return new String(buf, 0, read, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return "";
        }
    }

    private boolean runWprCancel(ToolDetection.DetectionReport report, Path runDir, List<String> notes, boolean elevated) {
        try {
            Path stdout = runDir.resolve("logs").resolve("wpr-cancel.stdout.log");
            Path stderr = runDir.resolve("logs").resolve("wpr-cancel.stderr.log");
            ProcessRunner.ProcessResult result;
            if (elevated) {
                String exe = report.wpr.path.toAbsolutePath().toString().replace("'", "''");
                String script =
                    "$p=Start-Process -FilePath '" + exe + "' -ArgumentList @('-cancel') -Verb RunAs -PassThru; " +
                    "$p.WaitForExit(); exit $p.ExitCode";
                List<String> cmd = List.of("powershell.exe", "-NoProfile", "-Command", script);
                result = ProcessRunner.run(cmd, runDir, stdout, stderr, Duration.ofSeconds(30), false);
            } else {
                List<String> cmd = List.of(report.wpr.path.toString(), "-cancel");
                result = ProcessRunner.run(cmd, runDir, stdout, stderr, Duration.ofSeconds(15));
            }
            notes.add("WPR cancel result exitCode=" + result.exitCode() + " timedOut=" + result.timedOut());
            if (result.timedOut()) {
                return false;
            }
            String err = readText(stderr).toLowerCase(Locale.ROOT);
            return result.exitCode() == 0 || err.contains("there are no trace profiles running");
        } catch (Exception e) {
            notes.add("WPR cancel failed: " + e.getMessage());
            return false;
        }
    }

    private void collectProfilerStatus(Path runRootDir, Path resultsDir, ToolDetection.DetectionReport report, CapturePlan plan, List<String> working, List<String> failed) {
        if (plan.presentMon) {
            addArtifactStatus("PresentMon", resultsDir.resolve("presentmon.csv"), working, failed);
        }
        if (plan.jfr) {
            addArtifactStatus("JFR", resultsDir.resolve("recording.jfr"), working, failed);
        }
        if (plan.typeperf) {
            addArtifactStatus("typeperf", resultsDir.resolve("windows_hardware_counters.csv"), working, failed);
        }
        if (plan.processContention) {
            addArtifactStatus("process contention", resultsDir.resolve("process_contention.csv"), working, failed);
        }
        if (plan.nvidiaSmi && report.nvidiaSmi != null && report.nvidiaSmi.found) {
            addArtifactStatus("nvidia-smi", resultsDir.resolve("nvidia_smi.csv"), working, failed);
        }
        if (plan.nsys && report.nsys != null && report.nsys.found) {
            addArtifactStatus("Nsight Systems export", resultsDir.resolve("nsys-report.nsys-rep"), working, failed);
        }
        if (plan.wpr && report.wpr != null && report.wpr.found) {
            addArtifactStatus("WPR export", resultsDir.resolve("trace.etl"), working, failed);
        }
        addArtifactStatus("manifest", resultsDir.resolve("index.json"), working, failed);
        try (var stream = Files.list(runRootDir)) {
            boolean anyZip = stream.anyMatch(path -> {
                String n = path.getFileName().toString().toLowerCase(Locale.ROOT);
                String base = "mc-diagnose-" + runRootDir.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.startsWith(base) && n.endsWith(".zip");
            });
            if (anyZip) {
                addUnique(working, "zip bundle");
            } else {
                addUnique(failed, "zip bundle");
            }
        } catch (Exception e) {
            addUnique(failed, "zip bundle");
        }
    }

    private void addArtifactStatus(String name, Path artifact, List<String> working, List<String> failed) {
        if (artifact != null && Files.exists(artifact)) {
            addUnique(working, name);
        } else {
            addUnique(failed, name);
        }
    }

    private void addUnique(List<String> list, String value) {
        if (!list.contains(value)) {
            list.add(value);
        }
    }

    private void announceStarted(String profilerName) {
        ChatUi.success(profilerName + " started successfully");
    }

    private void clearExistingWprIfRunning(ToolDetection.DetectionReport report, Path runDir, List<String> notes) {
        if (report.wpr == null || !report.wpr.found || report.wpr.path == null) {
            return;
        }
        try {
            boolean active = isWprRecordingInProgress(report, runDir);
            if (!active) {
                return;
            }
            notes.add("Preflight: existing WPR recording detected, attempting cancel.");
            boolean cancelled = runWprCancel(report, runDir, notes, false);
            if (!cancelled) {
                cancelled = runWprCancel(report, runDir, notes, true);
            }
            notes.add("Preflight WPR cancel result=" + cancelled);
            if (!cancelled) {
                ChatUi.warn("Pre-existing WPR session could not be canceled automatically.");
            }
        } catch (Exception e) {
            notes.add("Preflight WPR cleanup failed: " + e.getMessage());
        }
    }

    private String readSparkUrl(Path runDir) {
        try {
            Path sparkUrlFile = runDir.resolve("spark-profile-url.txt");
            if (!Files.exists(sparkUrlFile)) {
                return "not available";
            }
            String text = Files.readString(sparkUrlFile, StandardCharsets.UTF_8).trim();
            return text.isBlank() ? "not available" : text;
        } catch (Exception ignored) {
            return "not available";
        }
    }

    private void sendFinalSummary(boolean success, Path runDir, List<String> working, List<String> failed, String sparkUrl) {
        ChatUi.info("==================");
        ChatUi.info((success ? "Success: " : "Failed: ") + joinOrNone(working));
        ChatUi.info("failed: " + joinOrNone(failed));
        if (sparkUrl == null || sparkUrl.isBlank() || "not available".equalsIgnoreCase(sparkUrl)) {
            ChatUi.info("spark data: not available");
        } else {
            ChatUi.clickableUrl("spark data: " + sparkUrl, sparkUrl);
        }
        Path resultsFolder = runDir;
        ChatUi.clickableOpenFolderLabel("**OPEN RESULTS FOLDER**", resultsFolder);
        ChatUi.info("==================");
    }

    public synchronized boolean startPresentMonWithHelper(Path exe, List<String> args, Path workDir, Path stdout, Path stderr) {
        try {
            List<String> helperNotes = new ArrayList<>();
            ElevatedHelper helper = ensureElevatedHelper(workDir, helperNotes);
            if (helper == null || !helper.isReady()) {
                ChatUi.warn("Elevated helper unavailable for PresentMon. Falling back to direct UAC launch.");
                return false;
            }
            ElevatedHelper.CommandResult result = helper.runCommand(
                exe.toAbsolutePath().toString(),
                args,
                workDir.toAbsolutePath().toString(),
                stdout.toAbsolutePath().toString(),
                stderr.toAbsolutePath().toString(),
                30_000L,
                false
            );
            TaggerMod.LOGGER.info("[Diagnose][PresentMon] helper detached launch exitCode={} timedOut={} error={}",
                result.exitCode(), result.timedOut(), result.error());
            boolean ok = !result.timedOut() && result.exitCode() == 0;
            if (!ok) {
                ChatUi.warn("Elevated helper failed to launch PresentMon (exit=" + result.exitCode() + "). Falling back to direct UAC launch.");
            }
            return ok;
        } catch (Exception e) {
            TaggerMod.LOGGER.warn("[Diagnose][PresentMon] helper launch failed", e);
            ChatUi.warn("Elevated helper launch failed for PresentMon. Falling back to direct UAC launch.");
            return false;
        }
    }

    private String joinOrNone(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "none";
        }
        return String.join(", ", items);
    }

    private boolean requiresElevation(String stderrLower) {
        return stderrLower.contains("0xc5585011")
            || stderrLower.contains("administrative privileges")
            || stderrLower.contains("access is denied")
            || stderrLower.contains("elevation");
    }

    private synchronized ElevatedHelper ensureElevatedHelper(Path runDir, List<String> notes) {
        if (elevatedHelper != null && elevatedHelper.isReady()) {
            return elevatedHelper;
        }
        try {
            Path helperDir = runDir.resolve("logs").resolve("elevated-helper");
            Path stdout = helperDir.resolve("helper-launch.stdout.log");
            Path stderr = helperDir.resolve("helper-launch.stderr.log");
            ElevatedHelper helper = ElevatedHelper.start(helperDir, stdout, stderr);
            if (helper != null && helper.isReady()) {
                elevatedHelper = helper;
                notes.add("Elevated helper started.");
                return helper;
            }
            notes.add("Elevated helper failed to start.");
        } catch (Exception e) {
            notes.add("Elevated helper start failed: " + e.getMessage());
        }
        return null;
    }

    private boolean startWprElevated(ToolDetection.DetectionReport report, Path runDir, List<String> notes) {
        try {
            Path stdout = runDir.resolve("logs").resolve("wpr-start-uac.stdout.log");
            Path stderr = runDir.resolve("logs").resolve("wpr-start-uac.stderr.log");
            ProcessRunner.ProcessResult result = runWprElevatedStartCommand(report, runDir, stdout, stderr);
            notes.add("WPR UAC fallback exitCode=" + result.exitCode() + " timedOut=" + result.timedOut());
            if (result.timedOut()) {
                return false;
            }
            if (result.exitCode() != 0) {
                // 0xC5583001: WPR profile already running; treat as recoverable if status confirms active recording.
                if (result.exitCode() == -984076287) {
                    boolean alreadyRunning = isWprRecordingInProgress(report, runDir);
                    notes.add("WPR UAC fallback reported already-running profile. recording=" + alreadyRunning);
                    if (alreadyRunning) {
                        return true;
                    }
                    // stale profile state: try elevated cancel + one elevated restart.
                    if (runWprCancel(report, runDir, notes, true)) {
                        Path retryStdout = runDir.resolve("logs").resolve("wpr-start-uac-retry.stdout.log");
                        Path retryStderr = runDir.resolve("logs").resolve("wpr-start-uac-retry.stderr.log");
                        ProcessRunner.ProcessResult retry = runWprElevatedStartCommand(report, runDir, retryStdout, retryStderr);
                        notes.add("WPR UAC retry exitCode=" + retry.exitCode() + " timedOut=" + retry.timedOut());
                        if (!retry.timedOut() && retry.exitCode() == 0) {
                            // Trust exit code; non-elevated wpr -status cannot see elevated sessions.
                            notes.add("WPR UAC retry after already-running succeeded; trusting exit code.");
                            return true;
                        }
                    }
                    return false;
                }
                return false;
            }
            // Elevated start succeeded (exit 0). Non-elevated wpr -status cannot see elevated
            // sessions, so skip the status check and trust the exit code directly.
            notes.add("WPR elevated start succeeded; trusting exit code.");
            return true;
        } catch (Exception e) {
            notes.add("WPR UAC fallback failed: " + e.getMessage());
            return false;
        }
    }

    private ProcessRunner.ProcessResult runWprElevatedStartCommand(ToolDetection.DetectionReport report, Path runDir, Path stdout, Path stderr) throws Exception {
        ElevatedHelper helper = elevatedHelper;
        if (helper != null && helper.isReady()) {
            ElevatedHelper.CommandResult helperResult = helper.runCommand(
                report.wpr.path.toAbsolutePath().toString(),
                List.of("-start", "generalprofile", "-filemode"),
                runDir.toAbsolutePath().toString(),
                stdout.toAbsolutePath().toString(),
                stderr.toAbsolutePath().toString(),
                30_000L
            );
            return new ProcessRunner.ProcessResult(
                helperResult.exitCode(),
                helperResult.timedOut(),
                Duration.ofMillis(helperResult.elapsedMs())
            );
        }
        String exe = report.wpr.path.toAbsolutePath().toString().replace("'", "''");
        String script =
            quickEditDisableScript() +
            "$ErrorActionPreference='Stop'; " +
            "$p=Start-Process -FilePath '" + exe + "' -ArgumentList @('-start','generalprofile','-filemode') -Verb RunAs -WindowStyle Minimized -PassThru; " +
            "if ($null -eq $p) { exit 1 }; " +
            "$p.WaitForExit(); exit $p.ExitCode";
        List<String> cmd = List.of("powershell.exe", "-NoProfile", "-Command", script);
        return ProcessRunner.run(cmd, runDir, stdout, stderr, Duration.ofSeconds(30), false);
    }

    private ExportCommandResult runWprStopElevated(ToolDetection.DetectionReport report, Path runDir, Path outEtl, Path stdout, Path stderr) {
        try {
            ElevatedHelper helper = elevatedHelper;
            if (helper != null && helper.isReady()) {
                ElevatedHelper.CommandResult helperResult = helper.runCommand(
                    report.wpr.path.toAbsolutePath().toString(),
                    List.of("-stop", outEtl.toAbsolutePath().toString()),
                    runDir.toAbsolutePath().toString(),
                    stdout.toAbsolutePath().toString(),
                    stderr.toAbsolutePath().toString(),
                    EXPORT_TIMEOUT_MS
                );
                return new ExportCommandResult(helperResult.exitCode() == 0 && !helperResult.timedOut(), helperResult.timedOut(), helperResult.exitCode());
            }
            String exe = report.wpr.path.toAbsolutePath().toString().replace("'", "''");
            String etl = outEtl.toAbsolutePath().toString().replace("'", "''");
            String script =
                quickEditDisableScript() +
                "$ErrorActionPreference='Stop'; " +
                "$p=Start-Process -FilePath '" + exe + "' -ArgumentList @('-stop','" + etl + "') -Verb RunAs -WindowStyle Minimized -PassThru; " +
                "if ($null -eq $p) { exit 1 }; " +
                "$p.WaitForExit(); exit $p.ExitCode";
            List<String> cmd = List.of("powershell.exe", "-NoProfile", "-Command", script);
            ProcessRunner.ProcessResult result = ProcessRunner.run(cmd, runDir, stdout, stderr, Duration.ofMillis(EXPORT_TIMEOUT_MS), false);
            return new ExportCommandResult(result.exitCode() == 0 && !result.timedOut(), result.timedOut(), result.exitCode());
        } catch (Exception e) {
            return new ExportCommandResult(false, false, -1);
        }
    }

    private ExportCommandResult runNsysElevated(ToolDetection.DetectionReport report, Path runDir, List<String> args, Path stdout, Path stderr) {
        try {
            ElevatedHelper helper = elevatedHelper;
            if (helper != null && helper.isReady()) {
                ElevatedHelper.CommandResult helperResult = helper.runCommand(
                    report.nsys.path.toAbsolutePath().toString(),
                    args,
                    runDir.toAbsolutePath().toString(),
                    stdout.toAbsolutePath().toString(),
                    stderr.toAbsolutePath().toString(),
                    EXPORT_TIMEOUT_MS
                );
                return new ExportCommandResult(helperResult.exitCode() == 0 && !helperResult.timedOut(), helperResult.timedOut(), helperResult.exitCode());
            }
            String exe = report.nsys.path.toAbsolutePath().toString().replace("'", "''");
            StringBuilder scriptBuilder = new StringBuilder();
            scriptBuilder.append(quickEditDisableScript());
            scriptBuilder.append("$ErrorActionPreference='Stop'; ");
            scriptBuilder.append("$a = New-Object System.Collections.Generic.List[string]; ");
            for (String arg : args) {
                scriptBuilder.append("$a.Add('").append(arg.replace("'", "''")).append("'); ");
            }
            scriptBuilder.append("$p=Start-Process -FilePath '").append(exe)
                .append("' -ArgumentList $a -Verb RunAs -WindowStyle Minimized -PassThru; ");
            scriptBuilder.append("if ($null -eq $p) { exit 1 }; ");
            scriptBuilder.append("$p.WaitForExit(); exit $p.ExitCode");
            String script = scriptBuilder.toString();
            List<String> cmd = List.of("powershell.exe", "-NoProfile", "-Command", script);
            ProcessRunner.ProcessResult result = ProcessRunner.run(cmd, runDir, stdout, stderr, Duration.ofMillis(EXPORT_TIMEOUT_MS), false);
            return new ExportCommandResult(result.exitCode() == 0 && !result.timedOut(), result.timedOut(), result.exitCode());
        } catch (Exception e) {
            return new ExportCommandResult(false, false, -1);
        }
    }

    private String quickEditDisableScript() {
        return "$sig='using System;using System.Runtime.InteropServices;public static class InsigniaConsoleMode{"
            + "[DllImport(\"\"kernel32.dll\"\")] public static extern IntPtr GetStdHandle(int nStdHandle);"
            + "[DllImport(\"\"kernel32.dll\"\")] public static extern bool GetConsoleMode(IntPtr hConsoleHandle, out int lpMode);"
            + "[DllImport(\"\"kernel32.dll\"\")] public static extern bool SetConsoleMode(IntPtr hConsoleHandle, int dwMode);}'; "
            + "try { Add-Type -TypeDefinition $sig -ErrorAction SilentlyContinue | Out-Null } catch {}; "
            + "$h=[InsigniaConsoleMode]::GetStdHandle(-10); "
            + "$m=0; "
            + "if ([InsigniaConsoleMode]::GetConsoleMode($h,[ref]$m)) { "
            + "$m = ($m -band (-bnot 0x40)) -bor 0x80; "
            + "[InsigniaConsoleMode]::SetConsoleMode($h,$m) | Out-Null "
            + "}; ";
    }

    private ExportCommandResult runNsysStopWithFallback(ToolDetection.DetectionReport report, Path runDir, String sessionName, Path stdout, Path stderr) {
        List<String> stopCmd = List.of(report.nsys.path.toString(), "stop", "--session", sessionName);
        ExportCommandResult normal = runExportCommand(stopCmd, runDir, stdout, stderr);
        if (normal.timedOut()) {
            return normal;
        }
        if (normal.success() && exportArtifactExists("Nsight Systems", runDir)) {
            return normal;
        }
        Path uacStdout = stdout.getParent().resolve("nsys-stop-uac.stdout.log");
        Path uacStderr = stderr.getParent().resolve("nsys-stop-uac.stderr.log");
        ExportCommandResult elevated = runNsysElevated(report, runDir, List.of("stop", "--session", sessionName), uacStdout, uacStderr);
        if (elevated.success() && exportArtifactExists("Nsight Systems", runDir)) {
            return elevated;
        }
        return elevated.success() ? normal : elevated;
    }

    private boolean isWprRecordingInProgress(ToolDetection.DetectionReport report, Path runDir) {
        try {
            Path stdout = runDir.resolve("logs").resolve("wpr-status.stdout.log");
            Path stderr = runDir.resolve("logs").resolve("wpr-status.stderr.log");
            List<String> cmd = List.of(report.wpr.path.toString(), "-status");
            ProcessRunner.ProcessResult result = ProcessRunner.run(cmd, runDir, stdout, stderr, Duration.ofSeconds(8), false);
            if (result.timedOut() || result.exitCode() != 0) {
                return false;
            }
            String text = readText(stdout).toLowerCase(Locale.ROOT);
            return text.contains("recording is in progress");
        } catch (Exception e) {
            return false;
        }
    }

    private int fetchSparkFromUrl(Instant dispatchTime, Path runDir, List<String> notes) {
        try {
            String found = sparkUrlFromChat;
            if (found == null || found.isBlank()) {
                // Give chat-capture a short chance to arrive before falling back to log scraping.
                long waitUntil = System.currentTimeMillis() + 8_000L;
                while (System.currentTimeMillis() < waitUntil) {
                    found = sparkUrlFromChat;
                    if (found != null && !found.isBlank()) {
                        break;
                    }
                    try {
                        Thread.sleep(200L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (found != null && !found.isBlank()) {
                notes.add("spark URL captured from chat" + (sparkUrlCapturedAt != null ? " at " + sparkUrlCapturedAt : "") + ".");
            }

            Path gameLatestLog = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");
            if ((found == null || found.isBlank()) && !Files.exists(gameLatestLog)) {
                notes.add("spark URL fetch skipped: latest.log not found.");
                return 0;
            }
            if (found == null || found.isBlank()) {
                String logText = readTail(gameLatestLog, 512 * 1024);
                Matcher matcher = SPARK_URL_PATTERN.matcher(logText);
                while (matcher.find()) {
                    found = matcher.group(1);
                }
            }
            if (found == null || found.isBlank()) {
                notes.add("spark URL not found in latest.log.");
                return 0;
            }
            Files.writeString(runDir.resolve("spark-profile-url.txt"), found + System.lineSeparator(), StandardCharsets.UTF_8);
            String rawUrl = found.contains("?") ? found + "&raw=1&full=true" : found + "?raw=1&full=true";
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
            Exception lastException = null;
            int lastStatus = -1;
            for (int attempt = 1; attempt <= SPARK_FETCH_RETRIES; attempt++) {
                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(rawUrl))
                        .timeout(Duration.ofSeconds(20))
                        .GET()
                        .build();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    lastStatus = response.statusCode();
                    if (response.statusCode() >= 200 && response.statusCode() < 300 && response.body() != null && !response.body().isBlank()) {
                        Path out = runDir.resolve("spark-profiler-full.json");
                        Files.writeString(out, response.body(), StandardCharsets.UTF_8);
                        notes.add("Fetched spark profile from URL on attempt " + attempt + ".");
                        return 1;
                    }
                    notes.add("spark URL fetch attempt " + attempt + " failed: status=" + response.statusCode());
                } catch (Exception e) {
                    lastException = e;
                    notes.add("spark URL fetch attempt " + attempt + " failed: " + e.getMessage());
                }
                if (attempt < SPARK_FETCH_RETRIES) {
                    try {
                        Thread.sleep(SPARK_FETCH_RETRY_BACKOFF_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (lastException != null) {
                notes.add("spark URL fetch failed after retries: " + lastException.getMessage());
            } else {
                notes.add("spark URL fetch failed after retries: status=" + lastStatus);
            }
            return 0;
        } catch (Exception e) {
            notes.add("spark URL fetch failed: " + e.getMessage());
            return 0;
        }
    }

    private void announceExportIfExists(String profilerName, Path artifactPath) {
        try {
            if (artifactPath != null && Files.exists(artifactPath)) {
                ChatUi.success(profilerName + " Successfully Exported");
            }
        } catch (Exception ignored) {
        }
    }

    private ExportCommandResult runExportCommandWithProgress(String profilerName, List<String> command, Path runDir, Path stdout, Path stderr) throws Exception {
        Process process = ProcessRunner.start(command, runDir, stdout, stderr);
        long start = System.currentTimeMillis();
        boolean waitingNoticeSent = false;
        while (true) {
            if (!process.isAlive()) {
                int exit = process.exitValue();
                if (exit == 0) {
                    ChatUi.success(profilerName + " Successfully Exported");
                    return new ExportCommandResult(true, false, exit);
                }
                return new ExportCommandResult(false, false, exit);
            }
            long elapsed = System.currentTimeMillis() - start;
            if (!waitingNoticeSent && elapsed >= EXPORT_WAIT_NOTICE_MS) {
                ChatUi.warn("waiting on " + profilerName + " to finish exporting");
                waitingNoticeSent = true;
            }
            if (elapsed >= EXPORT_TIMEOUT_MS) {
                try {
                    process.destroy();
                    if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (Exception ignored) {
                }
                ChatUi.error(profilerName + " timed out while exporting");
                return new ExportCommandResult(false, true, -1);
            }
            Thread.sleep(250L);
        }
    }

    private ExportCommandResult runExportCommand(List<String> command, Path runDir, Path stdout, Path stderr) {
        try {
            ProcessRunner.ProcessResult result = ProcessRunner.run(command, runDir, stdout, stderr, Duration.ofMillis(EXPORT_TIMEOUT_MS), false);
            return new ExportCommandResult(result.exitCode() == 0 && !result.timedOut(), result.timedOut(), result.exitCode());
        } catch (Exception e) {
            return new ExportCommandResult(false, false, -1);
        }
    }

    private boolean exportArtifactExists(String profilerName, Path runDir) {
        try {
            String key = profilerName.toLowerCase(Locale.ROOT);
            if (key.contains("wpr")) {
                return Files.exists(runDir.resolve("trace.etl"));
            }
            if (key.contains("nsight")) {
                if (Files.exists(runDir.resolve("nsys-report.nsys-rep"))) {
                    return true;
                }
                try (var stream = Files.list(runDir)) {
                    return stream.anyMatch(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".nsys-rep") || name.endsWith(".qdrep");
                    });
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private record ExportCommandResult(boolean success, boolean timedOut, int exitCode) {
    }

    private record ExportTask(String name, CompletableFuture<ExportCommandResult> future) {
    }

    private static final class ElevatedHelper {
        private final Path rootDir;
        private final Path commandsDir;
        private final Path resultsDir;
        private final Path readyFile;
        private final Path shutdownFile;
        private final AtomicInteger nextId = new AtomicInteger(1);
        private volatile boolean ready;

        private ElevatedHelper(Path rootDir) {
            this.rootDir = rootDir;
            this.commandsDir = rootDir.resolve("commands");
            this.resultsDir = rootDir.resolve("results");
            this.readyFile = rootDir.resolve("ready.flag");
            this.shutdownFile = rootDir.resolve("shutdown.flag");
        }

        static ElevatedHelper start(Path helperDir, Path launchStdout, Path launchStderr) throws Exception {
            ElevatedHelper helper = new ElevatedHelper(helperDir);
            Files.createDirectories(helper.rootDir);
            Files.createDirectories(helper.commandsDir);
            Files.createDirectories(helper.resultsDir);
            Files.deleteIfExists(helper.readyFile);
            Files.deleteIfExists(helper.shutdownFile);

            Path helperScript = helper.rootDir.resolve("helper.ps1");
            Files.writeString(helperScript, helperScriptBody(helper.rootDir), StandardCharsets.UTF_8);

            String launcherScript =
                "$ErrorActionPreference='Stop'; " +
                "$p=Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File','" + psEscape(helperScript.toAbsolutePath().toString()) + "') -Verb RunAs -WindowStyle Hidden -PassThru; " +
                "if ($null -eq $p) { exit 1 }; exit 0";
            List<String> cmd = List.of("powershell.exe", "-NoProfile", "-Command", launcherScript);
            ProcessRunner.ProcessResult launch = ProcessRunner.run(cmd, helper.rootDir, launchStdout, launchStderr, Duration.ofSeconds(20), false);
            if (launch.timedOut() || launch.exitCode() != 0) {
                return null;
            }

            long deadline = System.currentTimeMillis() + 30_000L;
            while (System.currentTimeMillis() < deadline) {
                if (Files.exists(helper.readyFile)) {
                    helper.ready = true;
                    return helper;
                }
                Thread.sleep(200L);
            }
            return null;
        }

        boolean isReady() {
            return ready;
        }

        CommandResult runCommand(String exe, List<String> args, String workDir, String stdout, String stderr, long timeoutMs) {
            return runCommand(exe, args, workDir, stdout, stderr, timeoutMs, true);
        }

        CommandResult runCommand(String exe, List<String> args, String workDir, String stdout, String stderr, long timeoutMs, boolean waitForExit) {
            long start = System.currentTimeMillis();
            if (!ready) {
                return new CommandResult(-1, false, 0L, "helper_not_ready");
            }
            int id = nextId.getAndIncrement();
            Path commandFile = commandsDir.resolve(String.format("%06d.json", id));
            Path resultFile = resultsDir.resolve(String.format("%06d.json", id));
            try {
                Files.deleteIfExists(resultFile);
                String payload = commandJson(exe, args, workDir, stdout, stderr, timeoutMs, waitForExit);
                Files.writeString(commandFile, payload, StandardCharsets.UTF_8);

                long deadline = System.currentTimeMillis() + timeoutMs + 10_000L;
                while (System.currentTimeMillis() < deadline) {
                    if (Files.exists(resultFile)) {
                        String text = Files.readString(resultFile, StandardCharsets.UTF_8);
                        int exitCode = parseIntField(text, "exitCode", -1);
                        boolean timedOut = parseBoolField(text, "timedOut", false);
                        String error = parseStringField(text, "error", "");
                        return new CommandResult(exitCode, timedOut, System.currentTimeMillis() - start, error);
                    }
                    Thread.sleep(100L);
                }
                return new CommandResult(-1, true, System.currentTimeMillis() - start, "helper_result_timeout");
            } catch (Exception e) {
                return new CommandResult(-1, false, System.currentTimeMillis() - start, "helper_error:" + e.getMessage());
            } finally {
                try {
                    Files.deleteIfExists(commandFile);
                } catch (Exception ignored) {
                }
            }
        }

        void shutdown() {
            try {
                Files.writeString(shutdownFile, "shutdown", StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
            ready = false;
        }

        private static String commandJson(String exe, List<String> args, String workDir, String stdout, String stderr, long timeoutMs, boolean waitForExit) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"exe\":\"").append(jsonEscape(exe)).append("\",");
            sb.append("\"args\":[");
            for (int i = 0; i < args.size(); i++) {
                sb.append("\"").append(jsonEscape(args.get(i))).append("\"");
                if (i + 1 < args.size()) {
                    sb.append(",");
                }
            }
            sb.append("],");
            sb.append("\"workDir\":\"").append(jsonEscape(workDir)).append("\",");
            sb.append("\"stdout\":\"").append(jsonEscape(stdout)).append("\",");
            sb.append("\"stderr\":\"").append(jsonEscape(stderr)).append("\",");
            sb.append("\"timeoutMs\":").append(timeoutMs).append(",");
            sb.append("\"waitForExit\":").append(waitForExit);
            sb.append("}");
            return sb.toString();
        }

        /**
         * Generates the elevated helper PowerShell script. CRITICAL: In elevated Start-Process -PassThru
         * contexts, $p.ExitCode is often null even after WaitForExit. We must infer from stderr:
         * - Hex code (0xNNNNNNNN) → use that as signed exit code
         * - Empty/null stderr → success (0)
         * - Other text → generic failure (1)
         * NEVER call .Substring() or other methods on $stderrText directly: Get-Content -Raw returns
         * $null for empty files, which throws "You cannot call a method on a null-valued expression"
         * and corrupts the result. Use [string]::IsNullOrWhiteSpace() and -match only.
         */
        private static String helperScriptBody(Path rootDir) {
            String root = psEscape(rootDir.toAbsolutePath().toString());
            return
                "$ErrorActionPreference='Continue'\n" +
                "$root='" + root + "'\n" +
                "$cmdDir=Join-Path $root 'commands'\n" +
                "$resDir=Join-Path $root 'results'\n" +
                "$ready=Join-Path $root 'ready.flag'\n" +
                "$shutdown=Join-Path $root 'shutdown.flag'\n" +
                "$log=Join-Path $root 'helper.log'\n" +
                "New-Item -ItemType Directory -Force -Path $cmdDir,$resDir | Out-Null\n" +
                "$sig='using System;using System.Runtime.InteropServices;public static class InsigniaConsoleMode{[DllImport(\"\"kernel32.dll\"\")] public static extern IntPtr GetStdHandle(int nStdHandle);[DllImport(\"\"kernel32.dll\"\")] public static extern bool GetConsoleMode(IntPtr hConsoleHandle, out int lpMode);[DllImport(\"\"kernel32.dll\"\")] public static extern bool SetConsoleMode(IntPtr hConsoleHandle, int dwMode);}';\n" +
                "try { Add-Type -TypeDefinition $sig -ErrorAction SilentlyContinue | Out-Null } catch {}\n" +
                "try { $h=[InsigniaConsoleMode]::GetStdHandle(-10); $m=0; if([InsigniaConsoleMode]::GetConsoleMode($h,[ref]$m)) { $m = ($m -band (-bnot 0x40)) -bor 0x80; [InsigniaConsoleMode]::SetConsoleMode($h,$m) | Out-Null } } catch {}\n" +
                "Set-Content -Path $ready -Value 'ready' -Encoding UTF8\n" +
                "Add-Content -Path $log -Value (\"[{0}] helper ready\" -f (Get-Date).ToString('o'))\n" +
                "while (-not (Test-Path $shutdown)) {\n" +
                "  $files = Get-ChildItem -Path $cmdDir -Filter '*.json' -ErrorAction SilentlyContinue | Sort-Object Name\n" +
                "  foreach ($f in $files) {\n" +
                "    $exitCode = -1; $timedOut = $false; $err = ''\n" +
                "    try {\n" +
                "      $cmd = Get-Content -Raw -Path $f.FullName | ConvertFrom-Json\n" +
                "      $argList = @(); foreach ($a in $cmd.args) { $argList += [string]$a }\n" +
                "      Add-Content -Path $log -Value (\"[{0}] run {1} {2}\" -f (Get-Date).ToString('o'), [string]$cmd.exe, ($argList -join ' '))\n" +
                "      $p = Start-Process -FilePath ([string]$cmd.exe) -ArgumentList $argList -WorkingDirectory ([string]$cmd.workDir) -WindowStyle Minimized -RedirectStandardOutput ([string]$cmd.stdout) -RedirectStandardError ([string]$cmd.stderr) -PassThru\n" +
                "      if ($cmd.waitForExit -eq $false) { $exitCode = 0 } else { " +
                "      $ok = $p.WaitForExit([int]$cmd.timeoutMs)\n" +
                "      if (-not $ok) { try { $p.Kill() } catch {}; $timedOut = $true; $exitCode = -1 } else { " +
                "        $p.WaitForExit(); " +
                "        $p.Refresh(); " +
                "        $code = $p.ExitCode; " +
                "        if ($null -ne $code) { " +
                "          $exitCode = [int]$code; " +
                "        } else { " +
                "          $stderrText = ''; " +
                "          try { if (Test-Path ([string]$cmd.stderr)) { $stderrText = Get-Content -Raw -Path ([string]$cmd.stderr) } } catch {}; " +
                "          if ($stderrText -match '0x([0-9A-Fa-f]{8})') { " +
                "            $u = [Convert]::ToUInt32($matches[1], 16); " +
                "            $exitCode = [BitConverter]::ToInt32([BitConverter]::GetBytes($u), 0); " +
                "          } elseif ([string]::IsNullOrWhiteSpace($stderrText)) { " +
                "            $exitCode = 0; " +
                "          } else { " +
                "            $exitCode = 1; " +
                "          } " +
                "        } " +
                "      }\n" +
                "      }\n" +
                "    } catch { $err = $_.ToString() }\n" +
                "    Add-Content -Path $log -Value (\"[{0}] done {1} exit={2} timedOut={3} err={4}\" -f (Get-Date).ToString('o'), $f.BaseName, $exitCode, $timedOut, $err)\n" +
                "    $out = @{ exitCode = $exitCode; timedOut = $timedOut; error = $err } | ConvertTo-Json -Compress\n" +
                "    $resPath = Join-Path $resDir ($f.BaseName + '.json')\n" +
                "    Set-Content -Path $resPath -Value $out -Encoding UTF8\n" +
                "    Remove-Item -Path $f.FullName -Force -ErrorAction SilentlyContinue\n" +
                "  }\n" +
                "  Start-Sleep -Milliseconds 100\n" +
                "}\n";
        }

        private static int parseIntField(String json, String field, int fallback) {
            try {
                Pattern p = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(-?\\d+)");
                Matcher m = p.matcher(json);
                if (m.find()) {
                    return Integer.parseInt(m.group(1));
                }
            } catch (Exception ignored) {
            }
            return fallback;
        }

        private static boolean parseBoolField(String json, String field, boolean fallback) {
            try {
                Pattern p = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(true|false)");
                Matcher m = p.matcher(json);
                if (m.find()) {
                    return "true".equalsIgnoreCase(m.group(1));
                }
            } catch (Exception ignored) {
            }
            return fallback;
        }

        private static String parseStringField(String json, String field, String fallback) {
            try {
                Pattern p = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"(.*?)\"");
                Matcher m = p.matcher(json);
                if (m.find()) {
                    return m.group(1);
                }
            } catch (Exception ignored) {
            }
            return fallback;
        }

        private static String jsonEscape(String s) {
            if (s == null) {
                return "";
            }
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private static String psEscape(String value) {
            if (value == null) {
                return "";
            }
            return value.replace("'", "''");
        }

        private record CommandResult(int exitCode, boolean timedOut, long elapsedMs, String error) {
        }
    }

    private enum NsysStartMode {
        NOT_STARTED,
        SESSION,
        TIMED_PROFILE
    }

    private enum WprStartStatus {
        NOT_AVAILABLE,
        STARTED,
        STARTED_ELEVATED,
        ALREADY_RUNNING,
        STOPPED,
        FAILED
    }
}
