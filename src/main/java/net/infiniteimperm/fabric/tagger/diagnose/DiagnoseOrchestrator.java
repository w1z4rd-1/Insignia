package net.infiniteimperm.fabric.tagger.diagnose;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
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

public final class DiagnoseOrchestrator {
    public enum Mode {
        PRIVACY,
        FULL
    }

    private static final DiagnoseOrchestrator INSTANCE = new DiagnoseOrchestrator();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int DEFAULT_CAPTURE_SECONDS = 120;
    private static final long EXPORT_WAIT_NOTICE_MS = 30_000L;
    private static final long EXPORT_TIMEOUT_MS = 60_000L;
    private static final String ACTIVE_STATE_FILE = "active-session.properties";
    private static final Pattern SPARK_URL_PATTERN = Pattern.compile("(https://spark\\.lucko\\.me/[A-Za-z0-9]+)");

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "insignia-diagnose");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile Path activeRunDir;

    private DiagnoseOrchestrator() {
    }

    public static DiagnoseOrchestrator getInstance() {
        return INSTANCE;
    }

    public static void onHudFrameBoundary() {
        JfrController.onHudFrameBoundary();
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
        ToolDetection.DetectionReport report = ToolDetection.detectAll();
        if (!report.jfrAvailable) {
            ChatUi.error("Cannot run diagnose: JFR is unavailable in this Java runtime.");
            return;
        }
        if (report.presentMon == null || !report.presentMon.found) {
            ChatUi.error("Cannot run diagnose: PresentMon is missing.");
            ChatUi.hintGray("Install command: winget install --id=Intel.PresentMon.Console -e");
            showSetupHelp(source);
            return;
        }
        if (report.typeperf == null || !report.typeperf.found) {
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
        executor.submit(() -> runCapture(mode, report, effectiveCaptureSeconds));
    }

    private void runCapture(Mode mode, ToolDetection.DetectionReport report, int captureSeconds) {
        List<String> notes = new ArrayList<>();
        List<String> working = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        String sparkUrl = "not available";
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
            runDir = createRunDir();
            activeRunDir = runDir;
            Path logsDir = runDir.resolve("logs");
            Files.createDirectories(logsDir);
            latestLog = runDir.resolve("diagnose.log");
            stateFile = runDir.getParent().resolve(ACTIVE_STATE_FILE);
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

            log(latestLog, "Run started. mode=" + mode + " runDir=" + runDir);
            recoverStaleSessions(report, runDir, latestLog, stateFile, notes);

            Path presentCsv = runDir.resolve("presentmon.csv");
            Path recordingJfr = runDir.resolve("recording.jfr");
            Path badFrames = runDir.resolve("bad_frames.json");
            Path systemInfo = runDir.resolve("system_info.json");
            Path readme = runDir.resolve("README_ANALYZE.txt");

            presentMon = new PresentMonController(report.presentMon, runDir, presentCsv, presentStdout, presentStderr, captureSeconds);
            jfr = new JfrController(jfrLog);
            perfSampler = new PerfCounterSampler(countersCsv, countersLog, 100L);
            typeperf = new TypeperfController(report.typeperf, runDir, hardwareCountersCsv, typeperfStdout, typeperfStderr);
            nvidiaSmi = new NvidiaSmiController(report.nvidiaSmi, runDir, nvidiaSmiCsv, nvidiaSmiStderr);
            contentionSampler = new ProcessContentionSampler(contentionCsv, contentionLog, 1000L, 12);
            state = new ProfilerSessionState.State();
            state.ownerPid = ProcessHandle.current().pid();
            state.runDir = runDir.toString();
            state.createdAt = Instant.now().toString();
            ProfilerSessionState.save(stateFile, state);

            clearExistingWprIfRunning(report, runDir, notes);

            log(latestLog, "Starting JFR recording...");
            jfr.start();
            announceStarted("JFR");
            log(latestLog, "Starting PresentMon capture...");
            presentMon.start();
            announceStarted("PresentMon");
            log(latestLog, "Starting perf counter sampler...");
            perfSampler.start();
            announceStarted("perf counters");
            typeperfStarted = typeperf.start(notes);
            if (!typeperfStarted) {
                throw new IllegalStateException("typeperf failed to start (required profiler). Check typeperf.stderr.log");
            }
            announceStarted("typeperf");
            log(latestLog, "Starting process contention sampler...");
            contentionSampler.start();
            announceStarted("process contention");
            nvidiaSmiStarted = nvidiaSmi.start(notes);
            if (nvidiaSmiStarted) {
                announceStarted("nvidia-smi");
            }
            log(latestLog, "Mandatory captures started.");

            if (report.sparkPresent) {
                sparkDispatchTime = startSparkCapture(logsDir.resolve("spark.log"), notes, captureSeconds);
            } else {
                ChatUi.hintGray("to profile the game itself get the mod spark profiler");
            }
            nsysStartLog = logsDir.resolve("nsys-start.log");
            nsysStopLog = logsDir.resolve("nsys-stop.log");
            wprStartLog = logsDir.resolve("wpr-start.log");
            wprStopLog = logsDir.resolve("wpr-stop.log");
            nsysSession = "insignia_" + runDir.getFileName();
            log(latestLog, "Optional tool detection summary: spark=" + report.sparkPresent
                + ", nsys=" + (report.nsys != null && report.nsys.found)
                + ", wpr=" + (report.wpr != null && report.wpr.found)
                + ", wpaexporter=" + (report.wpaExporter != null && report.wpaExporter.found)
                + ", tracerpt=" + (report.tracerpt != null && report.tracerpt.found)
                + ", nvidia-smi=" + (report.nvidiaSmi != null && report.nvidiaSmi.found));
            nsysMode = startOptionalNsys(report, runDir, nsysStartLog, notes, nsysSession, captureSeconds);
            boolean nsysWasTimedProfile = (nsysMode == NsysStartMode.TIMED_PROFILE);
            wprStatus = startOptionalWpr(report, runDir, wprStartLog, notes);
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
            log(latestLog, "Stopping PresentMon...");
            presentMon.stop();
            announceExportIfExists("PresentMon", presentCsv);
            log(latestLog, "Stopping JFR and dumping recording...");
            jfr.stopAndDump(recordingJfr);
            announceExportIfExists("JFR", recordingJfr);
            log(latestLog, "Capture completed. Exporting.");

            ChatUi.info("Exporting profiler results...");
            log(latestLog, "Running JFR parser against recording.jfr + presentmon.csv...");
            new JfrParser().parse(recordingJfr, presentCsv, badFrames, jfr.startWall(), jfr.endWall());
            announceExportIfExists("JFR bad frame analysis", badFrames);
            log(latestLog, "Running ETL export pipeline...");
            new EtlExportController(report, runDir, logsDir, notes).exportIfPossible();
            announceExportIfExists("WPR trace", runDir.resolve("trace.etl"));
            announceExportIfExists("WPA export", runDir.resolve("wpa_exports"));
            announceExportIfExists("tracerpt export", runDir.resolve("trace_raw.csv"));
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
            NsysExternalCollector.collectPossibleReports(runDir, jfr.startWall(), notes);
            announceExportIfExists("Nsight Systems", runDir.resolve("nsys-report.nsys-rep"));
            announceExportIfExists("typeperf", hardwareCountersCsv);
            announceExportIfExists("nvidia-smi", nvidiaSmiCsv);
            announceExportIfExists("process contention", contentionCsv);

            log(latestLog, "Writing system_info.json and README_ANALYZE.txt...");
            BundleWriter.writeSystemInfo(systemInfo, mode, report, presentMon.targetPid(), jfr.startWall(), jfr.endWall());
            if (mode == Mode.FULL) {
                BundleWriter.includeFullModeFiles(runDir, notes);
            }
            BundleWriter.writeReadme(readme, mode, report.sparkPresent, notes);

            List<Path> required = List.of(
                presentCsv,
                recordingJfr,
                badFrames,
                systemInfo,
                readme,
                presentStdout,
                presentStderr,
                jfrLog,
                latestLog,
                countersCsv,
                hardwareCountersCsv
            );
            List<Path> optional = discoverOptionalFiles(runDir, required);
            if (mode == Mode.PRIVACY) {
                for (Path req : required) {
                    if (Files.exists(req) && isTextFile(req)) {
                        BundleWriter.redactTextFileInPlace(req, mode);
                    }
                }
                for (Path opt : optional) {
                    if (Files.exists(opt) && isTextFile(opt)) {
                        BundleWriter.redactTextFileInPlace(opt, mode);
                    }
                }
            }
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

            Path zip = runDir.resolveSibling("mc-diagnose-" + runDir.getFileName() + ".zip");
            BundleWriter.zipResults(runDir, zip, requiredWithManifest, optional, notes);
            log(latestLog, "Zip completed: " + zip);

            collectProfilerStatus(runDir, report, working, failed);
            sendFinalSummary(true, runDir, working, failed, sparkUrl);
        } catch (Exception e) {
            TaggerMod.LOGGER.error("[Diagnose] Capture failed", e);
            if (runDir != null) {
                failed.add("diagnostics: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                collectProfilerStatus(runDir, report, working, failed);
                sparkUrl = readSparkUrl(runDir);
                sendFinalSummary(false, runDir, working, failed, sparkUrl);
            } else {
                ChatUi.error("Diagnostics failed before run directory creation: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        } finally {
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
            ProcessRunner.ProcessResult result = ProcessRunner.run(start, runDir, stdout, stderr, Duration.ofSeconds(10));
            if (result.timedOut() || result.exitCode() != 0) {
                notes.add("nsys start failed with exitCode=" + result.exitCode() + " timedOut=" + result.timedOut());
                ChatUi.warn("Nsight Systems needs admin rights. Approve the UAC prompt if shown.");
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
                "$ErrorActionPreference='Stop'; " +
                "$a=@('profile','--trace=none','--sample=none','--cpuctxsw=none','--duration','" + nsysDuration + "','--force-overwrite=true','-o','" + out + "','cmd','/c','timeout /t " + (nsysDuration + 1) + " >nul'); " +
                "$p=Start-Process -FilePath '" + exe + "' -ArgumentList $a -WindowStyle Normal -PassThru; " +
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
            client.execute(() -> {
                if (client.getNetworkHandler() != null) {
                    client.getNetworkHandler().sendChatCommand("sparkc profiler --timeout " + timeout + " --interval 1");
                }
            });
            notes.add("Dispatched spark profiler command.");
            Files.writeString(sparkLog, "spark command dispatched at " + Instant.now() + "\n");
            return Instant.now();
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

    private boolean isTextFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".txt") || name.endsWith(".json") || name.endsWith(".log") || name.endsWith(".csv");
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

    private void collectProfilerStatus(Path runDir, ToolDetection.DetectionReport report, List<String> working, List<String> failed) {
        addArtifactStatus("PresentMon", runDir.resolve("presentmon.csv"), working, failed);
        addArtifactStatus("JFR", runDir.resolve("recording.jfr"), working, failed);
        addArtifactStatus("typeperf", runDir.resolve("windows_hardware_counters.csv"), working, failed);
        addArtifactStatus("process contention", runDir.resolve("process_contention.csv"), working, failed);
        if (report.nvidiaSmi != null && report.nvidiaSmi.found) {
            addArtifactStatus("nvidia-smi", runDir.resolve("nvidia_smi.csv"), working, failed);
        }
        if (report.nsys != null && report.nsys.found) {
            addArtifactStatus("Nsight Systems export", runDir.resolve("nsys-report.nsys-rep"), working, failed);
        }
        if (report.wpr != null && report.wpr.found) {
            addArtifactStatus("WPR export", runDir.resolve("trace.etl"), working, failed);
        }
        addArtifactStatus("manifest", runDir.resolve("index.json"), working, failed);
        if (Files.exists(runDir.resolveSibling("mc-diagnose-" + runDir.getFileName() + ".zip"))) {
            addUnique(working, "zip bundle");
        } else {
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
        ChatUi.info("spark data: " + (sparkUrl == null || sparkUrl.isBlank() ? "not available" : sparkUrl));
        ChatUi.clickableOpenFolderLabel("**OPEN RESULTS FOLDER**", runDir);
        ChatUi.info("==================");
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
        String exe = report.wpr.path.toAbsolutePath().toString().replace("'", "''");
        String script =
            "$ErrorActionPreference='Stop'; " +
            "$p=Start-Process -FilePath '" + exe + "' -ArgumentList @('-start','generalprofile','-filemode') -Verb RunAs -PassThru; " +
            "if ($null -eq $p) { exit 1 }; " +
            "$p.WaitForExit(); exit $p.ExitCode";
        List<String> cmd = List.of("powershell.exe", "-NoProfile", "-Command", script);
        return ProcessRunner.run(cmd, runDir, stdout, stderr, Duration.ofSeconds(30), false);
    }

    private ExportCommandResult runWprStopElevated(ToolDetection.DetectionReport report, Path runDir, Path outEtl, Path stdout, Path stderr) {
        try {
            String exe = report.wpr.path.toAbsolutePath().toString().replace("'", "''");
            String etl = outEtl.toAbsolutePath().toString().replace("'", "''");
            String script =
                "$ErrorActionPreference='Stop'; " +
                "$p=Start-Process -FilePath '" + exe + "' -ArgumentList @('-stop','" + etl + "') -Verb RunAs -PassThru; " +
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
            String exe = report.nsys.path.toAbsolutePath().toString().replace("'", "''");
            StringBuilder scriptBuilder = new StringBuilder();
            scriptBuilder.append("$ErrorActionPreference='Stop'; ");
            scriptBuilder.append("$a = New-Object System.Collections.Generic.List[string]; ");
            for (String arg : args) {
                scriptBuilder.append("$a.Add('").append(arg.replace("'", "''")).append("'); ");
            }
            scriptBuilder.append("$p=Start-Process -FilePath '").append(exe)
                .append("' -ArgumentList $a -Verb RunAs -PassThru; ");
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
            Path gameLatestLog = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");
            if (!Files.exists(gameLatestLog)) {
                notes.add("spark URL fetch skipped: latest.log not found.");
                return 0;
            }
            String logText = readTail(gameLatestLog, 512 * 1024);
            Matcher matcher = SPARK_URL_PATTERN.matcher(logText);
            String found = null;
            while (matcher.find()) {
                found = matcher.group(1);
            }
            if (found == null || found.isBlank()) {
                notes.add("spark URL not found in latest.log.");
                return 0;
            }
            String rawUrl = found.contains("?") ? found + "&raw=1&full=true" : found + "?raw=1&full=true";
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(rawUrl))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body() == null || response.body().isBlank()) {
                notes.add("spark URL fetch failed: status=" + response.statusCode());
                return 0;
            }
            Path out = runDir.resolve("spark-profiler-full.json");
            Files.writeString(out, response.body(), StandardCharsets.UTF_8);
            Files.writeString(runDir.resolve("spark-profile-url.txt"), found + System.lineSeparator(), StandardCharsets.UTF_8);
            notes.add("Fetched spark profile from URL.");
            return 1;
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
