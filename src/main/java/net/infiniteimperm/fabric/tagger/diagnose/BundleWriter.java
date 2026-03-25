package net.infiniteimperm.fabric.tagger.diagnose;

import net.fabricmc.loader.api.FabricLoader;
import net.infiniteimperm.fabric.tagger.TaggerMod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class BundleWriter {
    private static final long TRACE_ETL_SHARE_MAX_BYTES = 3_435_973_836L; // 3.2 GiB
    private static final long LARGE_PROFILE_SPLIT_THRESHOLD_BYTES = 128L * 1024L * 1024L;
    private static final Pattern POWER_PLAN_PATTERN = Pattern.compile("GUID:\\s*([0-9a-fA-F\\-]+)\\s*\\((.+)\\)");

    public static Path writeSystemInfo(
        Path outFile,
        DiagnoseOrchestrator.Mode mode,
        ToolDetection.DetectionReport detection,
        long mcPid,
        Instant captureStart,
        Instant captureEnd) throws IOException {
        WindowsInfo windowsInfo = WindowsInfo.query(mode == DiagnoseOrchestrator.Mode.FULL);
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"timestamp\": \"").append(Instant.now()).append("\",\n");
        sb.append("  \"capture_start\": \"").append(captureStart).append("\",\n");
        sb.append("  \"capture_end\": \"").append(captureEnd).append("\",\n");
        sb.append("  \"minecraft_pid\": ").append(mcPid).append(",\n");
        sb.append("  \"os\": \"").append(escape(System.getProperty("os.name"))).append("\",\n");
        sb.append("  \"os_version\": \"").append(escape(System.getProperty("os.version"))).append("\",\n");
        sb.append("  \"windows_caption\": \"").append(escape(windowsInfo.caption)).append("\",\n");
        sb.append("  \"windows_build\": \"").append(escape(windowsInfo.build)).append("\",\n");
        sb.append("  \"java_version\": \"").append(escape(System.getProperty("java.version"))).append("\",\n");
        sb.append("  \"java_vendor\": \"").append(escape(System.getProperty("java.vendor"))).append("\",\n");
        sb.append("  \"jvm_name\": \"").append(escape(System.getProperty("java.vm.name"))).append("\",\n");
        sb.append("  \"cpu_model\": \"").append(escape(windowsInfo.cpuModel)).append("\",\n");
        sb.append("  \"gpu_model\": \"").append(escape(windowsInfo.gpuModel)).append("\",\n");
        if (mode == DiagnoseOrchestrator.Mode.FULL) {
            sb.append("  \"gpu_driver_version\": \"").append(escape(windowsInfo.gpuDriver)).append("\",\n");
        }
        sb.append("  \"gpu_adapters\": [\n");
        for (int i = 0; i < windowsInfo.gpuAdapters.size(); i++) {
            GpuAdapter gpu = windowsInfo.gpuAdapters.get(i);
            sb.append("    {")
                .append("\"name\": \"").append(escape(gpu.name)).append("\", ")
                .append("\"driver_version\": \"").append(escape(gpu.driverVersion)).append("\", ")
                .append("\"vram_mb\": ").append(gpu.vramMb)
                .append("}");
            if (i + 1 < windowsInfo.gpuAdapters.size()) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  ],\n");
        sb.append("  \"total_ram_mb\": ").append(windowsInfo.totalRamMb).append(",\n");
        sb.append("  \"available_processors\": ").append(Runtime.getRuntime().availableProcessors()).append(",\n");
        sb.append("  \"max_memory_mb\": ").append(Runtime.getRuntime().maxMemory() / (1024L * 1024L)).append(",\n");
        sb.append("  \"total_memory_mb\": ").append(Runtime.getRuntime().totalMemory() / (1024L * 1024L)).append(",\n");
        sb.append("  \"free_memory_mb\": ").append(Runtime.getRuntime().freeMemory() / (1024L * 1024L)).append(",\n");
        String mcVersion = FabricLoader.getInstance().getModContainer("minecraft")
            .map(mc -> mc.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
        String loaderVersion = FabricLoader.getInstance().getModContainer("fabricloader")
            .map(loader -> loader.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
        sb.append("  \"minecraft_version\": \"").append(escape(mcVersion)).append("\",\n");
        sb.append("  \"loader_version\": \"").append(escape(loaderVersion)).append("\",\n");
        sb.append("  \"tool_detection\": {\n");
        sb.append("    \"presentmon\": ").append(detection.presentMon != null && detection.presentMon.found).append(",\n");
        sb.append("    \"jfr\": ").append(detection.jfrAvailable).append(",\n");
        sb.append("    \"spark\": ").append(detection.sparkPresent).append(",\n");
        sb.append("    \"nsys\": ").append(detection.nsys != null && detection.nsys.found).append(",\n");
        sb.append("    \"wpr\": ").append(detection.wpr != null && detection.wpr.found).append(",\n");
        sb.append("    \"wpaexporter\": ").append(detection.wpaExporter != null && detection.wpaExporter.found).append(",\n");
        sb.append("    \"tracerpt\": ").append(detection.tracerpt != null && detection.tracerpt.found).append(",\n");
        sb.append("    \"typeperf\": ").append(detection.typeperf != null && detection.typeperf.found).append(",\n");
        sb.append("    \"nvidia_smi\": ").append(detection.nvidiaSmi != null && detection.nvidiaSmi.found).append("\n");
        sb.append("  },\n");
        sb.append("  \"diagnose_capabilities\": {\n");
        sb.append("    \"gpu_metadata_source\": \"powershell_cim_csv\",\n");
        sb.append("    \"execution_samples_requested\": ").append(detection.jfrAvailable).append(",\n");
        sb.append("    \"renderer_batch_hooks_wired\": true\n");
        sb.append("  },\n");
        sb.append("  \"mode\": \"").append(mode.name().toLowerCase(Locale.ROOT)).append("\",\n");
        sb.append("  \"mods\": [\n");
        List<String> mods = new ArrayList<>();
        FabricLoader.getInstance().getAllMods().forEach(mod -> mods.add(mod.getMetadata().getId() + ":" + mod.getMetadata().getVersion().getFriendlyString()));
        mods.sort(String::compareTo);
        for (int i = 0; i < mods.size(); i++) {
            sb.append("    \"").append(escape(mods.get(i))).append("\"");
            if (i + 1 < mods.size()) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        Files.writeString(outFile, sb.toString(), StandardCharsets.UTF_8);
        return outFile;
    }

    public static Path writeDeviceDetails(Path outFile, Instant captureStart, Instant captureEnd) throws IOException {
        DeviceDetails details = DeviceDetails.query();
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"timestamp\": \"").append(Instant.now()).append("\",\n");
        sb.append("  \"capture_start\": \"").append(captureStart).append("\",\n");
        sb.append("  \"capture_end\": \"").append(captureEnd).append("\",\n");
        sb.append("  \"graphics\": {\n");
        sb.append("    \"adapters\": [\n");
        for (int i = 0; i < details.adapters.size(); i++) {
            DetailedGpuAdapter gpu = details.adapters.get(i);
            sb.append("      {")
                .append("\"name\": \"").append(escape(gpu.name)).append("\", ")
                .append("\"driver_version\": \"").append(escape(gpu.driverVersion)).append("\", ")
                .append("\"video_processor\": \"").append(escape(gpu.videoProcessor)).append("\", ")
                .append("\"driver_model\": \"").append(escape(gpu.driverModel)).append("\", ")
                .append("\"vram_mb\": ").append(gpu.vramMb)
                .append("}");
            if (i + 1 < details.adapters.size()) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("    ],\n");
        sb.append("    \"displays\": [\n");
        for (int i = 0; i < details.displays.size(); i++) {
            DisplayDevice display = details.displays.get(i);
            sb.append("      {")
                .append("\"name\": \"").append(escape(display.name)).append("\", ")
                .append("\"width\": ").append(display.width).append(", ")
                .append("\"height\": ").append(display.height)
                .append("}");
            if (i + 1 < details.displays.size()) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("    ],\n");
        sb.append("    \"power_plan\": ");
        appendPowerPlanJson(sb, details.powerPlan);
        sb.append(",\n");
        sb.append("    \"user_gpu_preferences\": [\n");
        for (int i = 0; i < details.gpuPreferences.size(); i++) {
            GpuPreference pref = details.gpuPreferences.get(i);
            sb.append("      {")
                .append("\"application\": \"").append(escape(pref.application)).append("\", ")
                .append("\"preference\": \"").append(escape(pref.preference)).append("\"")
                .append("}");
            if (i + 1 < details.gpuPreferences.size()) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("    ],\n");
        sb.append("    \"settings\": ");
        appendGraphicsSettingsJson(sb, details.settings);
        sb.append("\n");
        sb.append("  },\n");
        sb.append("  \"devices\": {\n");
        sb.append("    \"counts_by_class\": {\n");
        int classIndex = 0;
        for (Map.Entry<String, Integer> entry : details.deviceCountsByClass.entrySet()) {
            sb.append("      \"").append(escape(entry.getKey())).append("\": ").append(entry.getValue());
            if (++classIndex < details.deviceCountsByClass.size()) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("    },\n");
        sb.append("    \"input_devices\": [\n");
        for (int i = 0; i < details.inputDevices.size(); i++) {
            CompactDevice device = details.inputDevices.get(i);
            sb.append("      {")
                .append("\"name\": \"").append(escape(device.name)).append("\", ")
                .append("\"class\": \"").append(escape(device.deviceClass)).append("\", ")
                .append("\"manufacturer\": \"").append(escape(device.manufacturer)).append("\", ")
                .append("\"status\": \"").append(escape(device.status)).append("\"")
                .append("}");
            if (i + 1 < details.inputDevices.size()) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("    ],\n");
        sb.append("    \"audio_devices\": [\n");
        for (int i = 0; i < details.audioDevices.size(); i++) {
            CompactDevice device = details.audioDevices.get(i);
            sb.append("      {")
                .append("\"name\": \"").append(escape(device.name)).append("\", ")
                .append("\"manufacturer\": \"").append(escape(device.manufacturer)).append("\", ")
                .append("\"status\": \"").append(escape(device.status)).append("\"")
                .append("}");
            if (i + 1 < details.audioDevices.size()) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("    ]\n");
        sb.append("  },\n");
        sb.append("  \"overlay_processes\": [\n");
        for (int i = 0; i < details.overlayProcesses.size(); i++) {
            OverlayProcess process = details.overlayProcesses.get(i);
            sb.append("    {")
                .append("\"name\": \"").append(escape(process.name)).append("\", ")
                .append("\"count\": ").append(process.count)
                .append("}");
            if (i + 1 < details.overlayProcesses.size()) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        Files.writeString(outFile, sb.toString(), StandardCharsets.UTF_8);
        return outFile;
    }

    public static Path writeReadme(Path outFile, DiagnoseOrchestrator.Mode mode, boolean sparkPresent, List<String> notes) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("Insignia Diagnose Bundle\n");
        sb.append("=======================\n\n");
        sb.append("Mode: ").append(mode.name()).append("\n");
        sb.append("Spark detected: ").append(sparkPresent).append("\n\n");
        sb.append("Artifacts:\n");
        sb.append("- presentmon.csv\n");
        sb.append("- recording.jfr\n");
        sb.append("- bad_frames.json\n");
        sb.append("- windows_hardware_counters.csv\n");
        sb.append("- process_contention.csv\n");
        sb.append("- system_info.json\n");
        sb.append("- device-details.json\n");
        sb.append("- logs/*.log\n\n");
        sb.append("Custom JFR events emitted by this mod (category: Insignia):\n");
        sb.append("- insignia.HudFrameStart(frameId, nanoTime, wallMillis)\n");
        sb.append("- insignia.HudFrameEnd(frameId, nanoTime, wallMillis, frameDurationNs)\n");
        sb.append("- insignia.FramePhase(frameId, phase, durationNs, nanoTime, wallMillis)\n");
        sb.append("  phase enum: 1=WORLD, 2=ENTITIES, 3=BLOCK_ENTITIES, 4=PARTICLES, 5=GUI, 6=POST, 7=UPLOADS\n");
        sb.append("- insignia.FrameSummary(frameId, totalFrameDurationNs + aggregated per-frame counters)\n");
        sb.append("- insignia.FrameBoundary(phase, frameIndex, nanoTime, wallMillis) [legacy HUD boundary marker]\n");
        sb.append("- insignia.WorldTransition(transitionType, durationNs, nanoTime, wallMillis)\n");
        sb.append("  transitionType enum: 1=JOIN, 2=LEAVE, 3=DIM_CHANGE\n");
        sb.append("- insignia.UserStutterMark(frameId, label, nanoTime, wallMillis)\n");
        sb.append("- insignia.ResourceReload(phase, durationNs, nanoTime, wallMillis)\n");
        sb.append("  phase enum: 1=START, 2=END\n");
        sb.append("- insignia.ChunkBuildBatch(frameId, chunkCount, buildDurationNs, uploadDurationNs)\n");
        sb.append("- insignia.BufferUploadBatch(frameId, uploadCount, bytes, durationNs)\n");
        sb.append("- insignia.TextureUploadBatch(frameId, uploadCount, bytes, durationNs)\n");
        sb.append("- insignia.ShaderCompileBatch(frameId, compileCount, durationNs)\n");
        sb.append("Note: renderer batch hooks aggregate uploads per frame; zero counts usually mean the path was not exercised during capture.\n\n");
        sb.append("Reference parsing behavior:\n");
        sb.append("- PresentMon interpretation should follow CapFrameX-style frame-time analysis patterns.\n");
        sb.append("- Reference implementation: https://github.com/CXWorld/CapFrameX\n");
        sb.append("- Nsight export/reference docs: https://docs.nvidia.com/nsight-systems/AnalysisGuide/index.html\n\n");
        sb.append("Optimized AI analysis prompt:\n");
        sb.append("- Analyze this diagnostics bundle for Minecraft stutter root causes.\n");
        sb.append("- Use presentmon.csv for frame-time spikes and pacing anomalies.\n");
        sb.append("- Use recording.jfr + insignia.FrameBoundary events to correlate bad frames with CPU samples, allocations, locks, parks, and GC pauses.\n");
        sb.append("- Use ETL/nsys outputs when present to validate GPU/driver/scheduler hypotheses.\n");
        sb.append("- Provide:\n");
        sb.append("  1) Top likely root causes ranked by confidence.\n");
        sb.append("  2) Timestamped evidence for each cause.\n");
        sb.append("  3) Concrete fixes the user can apply now (mods/settings/JVM/driver/runtime), with rationale.\n");
        sb.append("  4) External research on relevant mod implementations and source code (especially GitHub) that addresses similar symptoms, and whether that approach matches this capture.\n\n");
        if (!notes.isEmpty()) {
            sb.append("Notes:\n");
            for (String note : notes) {
                sb.append("- ").append(note).append("\n");
            }
        }
        Files.writeString(outFile, sb.toString(), StandardCharsets.UTF_8);
        return outFile;
    }

    public static void includeFullModeFiles(Path runDir, List<String> notes) {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        copyIfExists(gameDir.resolve("options.txt"), runDir.resolve("options.txt"), notes);
        copyIfExists(FabricLoader.getInstance().getConfigDir().resolve("sodium-options.json"), runDir.resolve("sodium-options.json"), notes);
        copyIfExists(FabricLoader.getInstance().getConfigDir().resolve("sodium-extra-options.json"), runDir.resolve("sodium-extra-options.json"), notes);
        copyIfExists(FabricLoader.getInstance().getConfigDir().resolve("lithium.properties"), runDir.resolve("lithium.properties"), notes);
        try {
            Thread.sleep(250L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        // Copy latest.log last so it includes the newest diagnose status lines.
        copyIfExists(gameDir.resolve("logs").resolve("latest.log"), runDir.resolve("latest.log"), notes);
    }

    private static void copyIfExists(Path from, Path to, List<String> notes) {
        try {
            if (!Files.exists(from)) {
                return;
            }
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            notes.add("Included file: " + from.getFileName());
            TaggerMod.LOGGER.info("[Diagnose][Bundle] Included file {}", from.getFileName());
        } catch (Exception e) {
            notes.add("Failed to include " + from + ": " + e.getMessage());
            TaggerMod.LOGGER.warn("[Diagnose][Bundle] Failed to include {}", from, e);
        }
    }

    public static Path zipResults(Path runDir, Path zipPath, List<Path> required, List<Path> optional, List<String> notes) throws IOException {
        Set<Path> selectedSet = new LinkedHashSet<>();
        for (Path req : required) {
            if (!Files.exists(req)) {
                throw new IOException("Missing required artifact: " + req.toAbsolutePath());
            }
            selectedSet.add(req);
        }
        for (Path opt : optional) {
            if (!Files.exists(opt)) {
                continue;
            }
            selectedSet.add(opt);
        }

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (Path file : selectedSet) {
                if (!Files.exists(file)) {
                    continue;
                }
                Path relative = runDir.relativize(file);
                zos.putNextEntry(new ZipEntry(relative.toString().replace('\\', '/')));
                Files.copy(file, zos);
                zos.closeEntry();
            }
        }
        return zipPath;
    }

    public static List<Path> zipResultsPartitioned(
        Path runDir,
        String zipBaseName,
        List<Path> required,
        List<Path> optional,
        List<String> notes,
        long maxZipBytes,
        int maxParts) throws IOException {
        Set<Path> requiredSet = new LinkedHashSet<>();
        for (Path req : required) {
            if (!Files.exists(req)) {
                throw new IOException("Missing required artifact: " + req.toAbsolutePath());
            }
            requiredSet.add(req);
        }

        Set<Path> optionalSet = new LinkedHashSet<>();
        for (Path opt : optional) {
            if (!Files.exists(opt)) {
                continue;
            }
            Path rel = runDir.relativize(opt);
            String relText = rel.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            boolean isTraceEtl = relText.equals("results/trace.etl") || relText.equals("trace.etl");
            if (isTraceEtl && safeSize(opt) > TRACE_ETL_SHARE_MAX_BYTES) {
                continue;
            }
            // Skip huge symbol extraction folders in share zips; full folder still contains everything.
            if (relText.startsWith("results/trace.etl.ngenpdb/")
                || relText.startsWith("results/trace.etl.embeddedpdbs/")) {
                continue;
            }
            optionalSet.add(opt);
        }

        List<Path> requiredFiles = new ArrayList<>(requiredSet);
        requiredFiles.sort((a, b) -> Long.compare(safeSize(b), safeSize(a)));
        List<Path> optionalFiles = new ArrayList<>(optionalSet);
        optionalFiles.sort((a, b) -> Long.compare(safeSize(b), safeSize(a)));

        List<List<Path>> bins = new ArrayList<>();
        List<Path> current = new ArrayList<>();
        List<Path> skipped = new ArrayList<>();

        for (Path file : requiredFiles) {
            if (shouldForceLargeProfilerSplit(runDir, file) && !current.isEmpty()) {
                bins.add(new ArrayList<>(current));
                current.clear();
            }
            packByActualZipSize(runDir, file, true, bins, current, skipped, notes, maxZipBytes, maxParts);
            if (shouldForceLargeProfilerSplit(runDir, file) && !current.isEmpty()) {
                bins.add(new ArrayList<>(current));
                current.clear();
            }
        }
        for (Path file : optionalFiles) {
            packByActualZipSize(runDir, file, false, bins, current, skipped, notes, maxZipBytes, maxParts);
        }
        if (!current.isEmpty()) {
            bins.add(new ArrayList<>(current));
        }

        List<Path> out = new ArrayList<>();
        for (int i = 0; i < bins.size(); i++) {
            String name = bins.size() == 1
                ? zipBaseName + ".zip"
                : zipBaseName + "-part" + (i + 1) + ".zip";
            Path zipPath = runDir.resolve(name);
            writeZip(runDir, zipPath, bins.get(i));
            out.add(zipPath);
            long zipSize = safeSize(zipPath);
            notes.add("Created share zip: " + zipPath.getFileName() + " (" + zipSize + " bytes)");
            if (zipSize > maxZipBytes) {
                notes.add("Share zip exceeds target size cap: " + zipPath.getFileName() + " > " + maxZipBytes + " bytes.");
            }
        }

        if (!skipped.isEmpty()) {
            notes.add("Skipped " + skipped.size() + " file(s) from share zips due size cap/part limit.");
        }
        return out;
    }

    private static void packByActualZipSize(
        Path runDir,
        Path file,
        boolean required,
        List<List<Path>> bins,
        List<Path> current,
        List<Path> skipped,
        List<String> notes,
        long maxZipBytes,
        int maxParts) throws IOException {
        if (current.isEmpty()) {
            if (!required && maxParts > 0 && bins.size() >= maxParts) {
                skipped.add(file);
                return;
            }
            current.add(file);
            long onlySize = estimateZipSize(runDir, current);
            if (onlySize > maxZipBytes) {
                notes.add("Single-file zip exceeds size cap: " + runDir.relativize(file) + " => " + onlySize + " bytes.");
            }
            return;
        }

        List<Path> trial = new ArrayList<>(current);
        trial.add(file);
        long trialSize = estimateZipSize(runDir, trial);
        if (trialSize <= maxZipBytes) {
            current.add(file);
            return;
        }

        if (!required && maxParts > 0 && bins.size() + 1 >= maxParts) {
            skipped.add(file);
            return;
        }

        bins.add(new ArrayList<>(current));
        current.clear();
        current.add(file);
        long onlySize = estimateZipSize(runDir, current);
        if (onlySize > maxZipBytes) {
            notes.add("Single-file zip exceeds size cap: " + runDir.relativize(file) + " => " + onlySize + " bytes.");
        }
    }

    private static boolean shouldForceLargeProfilerSplit(Path runDir, Path file) {
        String rel = runDir.relativize(file).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        boolean target = rel.endsWith("/recording.jfr") || rel.equals("recording.jfr")
            || rel.endsWith("/presentmon.csv") || rel.equals("presentmon.csv");
        return target && safeSize(file) >= LARGE_PROFILE_SPLIT_THRESHOLD_BYTES;
    }

    private static long estimateZipSize(Path runDir, List<Path> files) throws IOException {
        Path tmp = Files.createTempFile("insignia-zip-estimate-", ".zip");
        try {
            writeZip(runDir, tmp, files);
            return Files.size(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void writeZip(Path runDir, Path zipPath, List<Path> files) throws IOException {
        // Preserve insertion order while de-duplicating.
        Map<Path, Boolean> unique = new LinkedHashMap<>();
        for (Path file : files) {
            unique.put(file, Boolean.TRUE);
        }
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (Path file : unique.keySet()) {
                if (!Files.exists(file)) {
                    continue;
                }
                Path relative = runDir.relativize(file);
                zos.putNextEntry(new ZipEntry(relative.toString().replace('\\', '/')));
                Files.copy(file, zos);
                zos.closeEntry();
            }
        }
    }

    private static long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String escape(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void appendPowerPlanJson(StringBuilder sb, PowerPlan powerPlan) {
        if (powerPlan == null) {
            sb.append("null");
            return;
        }
        sb.append("{")
            .append("\"guid\": \"").append(escape(powerPlan.guid)).append("\", ")
            .append("\"name\": \"").append(escape(powerPlan.name)).append("\"")
            .append("}");
    }

    private static void appendGraphicsSettingsJson(StringBuilder sb, GraphicsSettings settings) {
        sb.append("{\n");
        sb.append("      \"auto_game_mode_enabled\": ");
        appendBooleanOrNull(sb, settings.autoGameModeEnabled);
        sb.append(",\n");
        sb.append("      \"game_dvr_enabled\": ");
        appendBooleanOrNull(sb, settings.gameDvrEnabled);
        sb.append(",\n");
        sb.append("      \"game_dvr_fse_behavior_mode\": ");
        appendIntegerOrNull(sb, settings.gameDvrFseBehaviorMode);
        sb.append(",\n");
        sb.append("      \"vrr_enabled\": ");
        appendBooleanOrNull(sb, settings.vrrEnabled);
        sb.append(",\n");
        sb.append("      \"hardware_accelerated_gpu_scheduling\": \"").append(escape(settings.hagsMode)).append("\",\n");
        sb.append("      \"tdr_level\": ");
        appendIntegerOrNull(sb, settings.tdrLevel);
        sb.append(",\n");
        sb.append("      \"tdr_delay_s\": ");
        appendIntegerOrNull(sb, settings.tdrDelaySeconds);
        sb.append(",\n");
        sb.append("      \"tdr_ddi_delay_s\": ");
        appendIntegerOrNull(sb, settings.tdrDdiDelaySeconds);
        sb.append("\n");
        sb.append("    }");
    }

    private static void appendBooleanOrNull(StringBuilder sb, Boolean value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        sb.append(value);
    }

    private static void appendIntegerOrNull(StringBuilder sb, Integer value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        sb.append(value);
    }

    private static String runPs(String expr) {
        try {
            String script = "$ErrorActionPreference='Stop'; [Console]::OutputEncoding=[System.Text.Encoding]::UTF8; " + expr;
            ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                script);
            Process process = pb.start();
            boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "unknown";
            }
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                TaggerMod.LOGGER.warn("[Diagnose][Bundle] PowerShell query failed: expr={} stderr={}", expr, err);
                return "unknown";
            }
            return out.isBlank() ? "unknown" : out;
        } catch (Exception e) {
            return "unknown";
        }
    }

    static List<String[]> parseGpuAdapterCsvRows(String rows) {
        List<String[]> parsed = new ArrayList<>();
        if (rows == null || rows.isBlank() || rows.equals("unknown")) {
            return parsed;
        }
        boolean headerSkipped = false;
        for (String row : rows.split("\\R")) {
            if (row == null || row.isBlank()) {
                continue;
            }
            if (!headerSkipped) {
                headerSkipped = true;
                continue;
            }
            List<String> parts = JfrParser.parseCsvLine(row);
            String name = parts.size() > 0 ? parts.get(0).trim() : "unknown";
            if (name.isBlank() || name.equalsIgnoreCase("name")) {
                continue;
            }
            String driver = parts.size() > 1 ? parts.get(1).trim() : "unknown";
            String ram = parts.size() > 2 ? parts.get(2).trim() : "";
            parsed.add(new String[]{name, driver, ram});
        }
        return parsed;
    }

    static List<Map<String, String>> parseNamedCsvRows(String rows) {
        List<Map<String, String>> parsed = new ArrayList<>();
        if (rows == null || rows.isBlank() || rows.equals("unknown")) {
            return parsed;
        }
        String[] lines = rows.split("\\R");
        if (lines.length == 0) {
            return parsed;
        }
        List<String> headers = JfrParser.parseCsvLine(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.isBlank()) {
                continue;
            }
            List<String> cols = JfrParser.parseCsvLine(line);
            Map<String, String> row = new LinkedHashMap<>();
            boolean anyValue = false;
            for (int j = 0; j < headers.size(); j++) {
                String header = headers.get(j).trim();
                String value = j < cols.size() ? cols.get(j).trim() : "";
                if (!value.isBlank()) {
                    anyValue = true;
                }
                row.put(header, value);
            }
            if (anyValue) {
                parsed.add(row);
            }
        }
        return parsed;
    }

    static PowerPlan parsePowerPlanOutput(String output) {
        if (output == null || output.isBlank() || output.equals("unknown")) {
            return null;
        }
        Matcher matcher = POWER_PLAN_PATTERN.matcher(output.trim());
        if (!matcher.find()) {
            return null;
        }
        return new PowerPlan(matcher.group(1).trim(), matcher.group(2).trim());
    }

    static String parseGpuPreferenceValue(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return "unknown";
        }
        Matcher matcher = Pattern.compile("GpuPreference=(\\d+)").matcher(rawValue);
        if (!matcher.find()) {
            return rawValue.trim();
        }
        return switch (matcher.group(1)) {
            case "0" -> "system_default";
            case "1" -> "power_saving";
            case "2" -> "high_performance";
            default -> "unknown(" + matcher.group(1) + ")";
        };
    }

    static String parseHagsMode(String rawValue) {
        Integer mode = parseInteger(rawValue);
        if (mode == null) {
            return "unknown";
        }
        return switch (mode) {
            case 1 -> "disabled";
            case 2 -> "enabled";
            default -> "default";
        };
    }

    private static Boolean parseBooleanFlag(String rawValue) {
        Integer value = parseInteger(rawValue);
        if (value == null) {
            return null;
        }
        return value != 0;
    }

    private static Integer parseInteger(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(rawValue.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static Path writeManifest(
        Path outFile,
        ToolDetection.DetectionReport detection,
        List<Path> required,
        List<Path> optional,
        List<String> notes) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"required\": {\n");
        sb.append("    \"presentmon\": ").append(detection.presentMon != null && detection.presentMon.found).append(",\n");
        sb.append("    \"jfr\": ").append(detection.jfrAvailable).append(",\n");
        sb.append("    \"typeperf\": ").append(detection.typeperf != null && detection.typeperf.found).append("\n");
        sb.append("  },\n");
        sb.append("  \"optional\": {\n");
        sb.append("    \"spark\": ").append(detection.sparkPresent).append(",\n");
        sb.append("    \"nsys\": ").append(detection.nsys != null && detection.nsys.found).append(",\n");
        sb.append("    \"wpr\": ").append(detection.wpr != null && detection.wpr.found).append(",\n");
        sb.append("    \"wpaexporter\": ").append(detection.wpaExporter != null && detection.wpaExporter.found).append(",\n");
        sb.append("    \"tracerpt\": ").append(detection.tracerpt != null && detection.tracerpt.found).append(",\n");
        sb.append("    \"nvidia_smi\": ").append(detection.nvidiaSmi != null && detection.nvidiaSmi.found).append("\n");
        sb.append("  },\n");
        sb.append("  \"files\": {\n");
        sb.append("    \"required\": [\n");
        for (int i = 0; i < required.size(); i++) {
            sb.append("      \"").append(escape(required.get(i).getFileName().toString())).append("\"");
            if (i + 1 < required.size()) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("    ],\n");
        sb.append("    \"optional\": [\n");
        for (int i = 0; i < optional.size(); i++) {
            sb.append("      \"").append(escape(optional.get(i).getFileName().toString())).append("\"");
            if (i + 1 < optional.size()) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("    ]\n");
        sb.append("  },\n");
        sb.append("  \"notes\": [\n");
        for (int i = 0; i < notes.size(); i++) {
            sb.append("    \"").append(escape(notes.get(i))).append("\"");
            if (i + 1 < notes.size()) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        Files.writeString(outFile, sb.toString(), StandardCharsets.UTF_8);
        return outFile;
    }

    private static final class WindowsInfo {
        private String caption = "unknown";
        private String build = "unknown";
        private String cpuModel = "unknown";
        private String gpuModel = "unknown";
        private String gpuDriver = "unknown";
        private long totalRamMb = -1L;
        private List<GpuAdapter> gpuAdapters = new ArrayList<>();

        private static WindowsInfo query(boolean includeDriver) {
            WindowsInfo info = new WindowsInfo();
            info.caption = runPs("(Get-CimInstance Win32_OperatingSystem).Caption");
            info.build = runPs("(Get-CimInstance Win32_OperatingSystem).BuildNumber");
            info.cpuModel = runPs("(Get-CimInstance Win32_Processor | Select-Object -First 1).Name");
            info.gpuAdapters = queryGpuAdapters();
            GpuAdapter primary = pickPrimaryAdapter(info.gpuAdapters);
            if (primary != null) {
                info.gpuModel = primary.name;
                if (includeDriver) {
                    info.gpuDriver = primary.driverVersion;
                }
            }
            String ramBytes = runPs("(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory");
            try {
                info.totalRamMb = Long.parseLong(ramBytes.trim()) / (1024L * 1024L);
            } catch (Exception ignored) {
                info.totalRamMb = -1L;
            }
            return info;
        }

        private static List<GpuAdapter> queryGpuAdapters() {
            String rows = runPs("Get-CimInstance Win32_VideoController | Select-Object Name,DriverVersion,AdapterRAM | ConvertTo-Csv -NoTypeInformation");
            return parseGpuAdapterCsv(rows);
        }

        static List<GpuAdapter> parseGpuAdapterCsv(String rows) {
            List<GpuAdapter> gpus = new ArrayList<>();
            for (String[] parts : BundleWriter.parseGpuAdapterCsvRows(rows)) {
                String name = parts[0];
                String driver = parts[1];
                long vramMb = -1L;
                if (!parts[2].isBlank()) {
                    try {
                        vramMb = Long.parseLong(parts[2]) / (1024L * 1024L);
                    } catch (Exception ignored) {
                        vramMb = -1L;
                    }
                }
                gpus.add(new GpuAdapter(name, driver, vramMb));
            }
            return gpus;
        }

        private static GpuAdapter pickPrimaryAdapter(List<GpuAdapter> adapters) {
            if (adapters == null || adapters.isEmpty()) {
                return null;
            }
            GpuAdapter best = null;
            for (GpuAdapter adapter : adapters) {
                if (best == null) {
                    best = adapter;
                    continue;
                }
                long a = adapter.vramMb;
                long b = best.vramMb;
                if (a > b) {
                    best = adapter;
                }
            }
            return best;
        }

    }

    private record GpuAdapter(String name, String driverVersion, long vramMb) {
    }

    static final class DeviceDetails {
        private final List<DetailedGpuAdapter> adapters;
        private final List<DisplayDevice> displays;
        private final Map<String, Integer> deviceCountsByClass;
        private final List<CompactDevice> inputDevices;
        private final List<CompactDevice> audioDevices;
        private final PowerPlan powerPlan;
        private final List<GpuPreference> gpuPreferences;
        private final GraphicsSettings settings;
        private final List<OverlayProcess> overlayProcesses;

        private DeviceDetails(
            List<DetailedGpuAdapter> adapters,
            List<DisplayDevice> displays,
            Map<String, Integer> deviceCountsByClass,
            List<CompactDevice> inputDevices,
            List<CompactDevice> audioDevices,
            PowerPlan powerPlan,
            List<GpuPreference> gpuPreferences,
            GraphicsSettings settings,
            List<OverlayProcess> overlayProcesses) {
            this.adapters = adapters;
            this.displays = displays;
            this.deviceCountsByClass = deviceCountsByClass;
            this.inputDevices = inputDevices;
            this.audioDevices = audioDevices;
            this.powerPlan = powerPlan;
            this.gpuPreferences = gpuPreferences;
            this.settings = settings;
            this.overlayProcesses = overlayProcesses;
        }

        private static DeviceDetails query() {
            List<DetailedGpuAdapter> adapters = queryDetailedGpuAdapters();
            List<DisplayDevice> displays = queryDisplays();
            List<CompactDevice> inputDevices = queryInputDevices();
            List<CompactDevice> audioDevices = queryAudioDevices();
            Map<String, Integer> countsByClass = countByClass(inputDevices);
            PowerPlan powerPlan = parsePowerPlanOutput(runPs("powercfg /GETACTIVESCHEME"));
            List<GpuPreference> gpuPreferences = queryGpuPreferences();
            GraphicsSettings settings = queryGraphicsSettings();
            List<OverlayProcess> overlayProcesses = queryOverlayProcesses();
            return new DeviceDetails(adapters, displays, countsByClass, inputDevices, audioDevices, powerPlan, gpuPreferences, settings, overlayProcesses);
        }

        private static List<DetailedGpuAdapter> queryDetailedGpuAdapters() {
            String rows = runPs("Get-CimInstance Win32_VideoController | Select-Object Name,DriverVersion,VideoProcessor,AdapterRAM,DriverModel | ConvertTo-Csv -NoTypeInformation");
            List<DetailedGpuAdapter> out = new ArrayList<>();
            for (Map<String, String> row : parseNamedCsvRows(rows)) {
                String name = valueOrUnknown(row.get("Name"));
                if (name.equals("unknown")) {
                    continue;
                }
                long vramMb = -1L;
                String rawRam = row.getOrDefault("AdapterRAM", "");
                if (!rawRam.isBlank()) {
                    try {
                        vramMb = Long.parseLong(rawRam) / (1024L * 1024L);
                    } catch (NumberFormatException ignored) {
                        vramMb = -1L;
                    }
                }
                out.add(new DetailedGpuAdapter(
                    name,
                    valueOrUnknown(row.get("DriverVersion")),
                    valueOrUnknown(row.get("VideoProcessor")),
                    valueOrUnknown(row.get("DriverModel")),
                    vramMb));
            }
            out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            return out;
        }

        private static List<DisplayDevice> queryDisplays() {
            String rows = runPs("Get-CimInstance Win32_DesktopMonitor | Select-Object Name,ScreenWidth,ScreenHeight | ConvertTo-Csv -NoTypeInformation");
            LinkedHashMap<String, DisplayDevice> unique = new LinkedHashMap<>();
            for (Map<String, String> row : parseNamedCsvRows(rows)) {
                String name = valueOrUnknown(row.get("Name"));
                Integer widthValue = parseInteger(row.get("ScreenWidth"));
                Integer heightValue = parseInteger(row.get("ScreenHeight"));
                int width = widthValue == null ? -1 : widthValue;
                int height = heightValue == null ? -1 : heightValue;
                String key = name + "|" + width + "|" + height;
                unique.putIfAbsent(key, new DisplayDevice(name, width, height));
            }
            List<DisplayDevice> out = new ArrayList<>(unique.values());
            out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            return out;
        }

        private static List<CompactDevice> queryInputDevices() {
            String rows = runPs("Get-CimInstance Win32_PnPEntity | Where-Object { $_.PNPClass -in 'Mouse','Keyboard','HIDClass','USB','AudioEndpoint' } | Select-Object Name,PNPClass,Manufacturer,Status | ConvertTo-Csv -NoTypeInformation");
            return parseCompactDevices(rows, true);
        }

        private static List<CompactDevice> queryAudioDevices() {
            LinkedHashMap<String, CompactDevice> unique = new LinkedHashMap<>();
            for (CompactDevice device : parseCompactDevices(
                runPs("Get-CimInstance Win32_SoundDevice | Select-Object Name,Manufacturer,Status | ConvertTo-Csv -NoTypeInformation"),
                false)) {
                unique.putIfAbsent(device.name + "|" + device.manufacturer + "|" + device.status, device);
            }
            for (CompactDevice device : parseCompactDevices(
                runPs("Get-CimInstance Win32_PnPEntity | Where-Object { $_.Name -match 'audio|headset|speaker|dac' } | Select-Object Name,Manufacturer,Status | ConvertTo-Csv -NoTypeInformation"),
                false)) {
                unique.putIfAbsent(device.name + "|" + device.manufacturer + "|" + device.status, device);
            }
            List<CompactDevice> out = new ArrayList<>(unique.values());
            out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            return out;
        }

        private static List<CompactDevice> parseCompactDevices(String rows, boolean includeClass) {
            LinkedHashMap<String, CompactDevice> unique = new LinkedHashMap<>();
            for (Map<String, String> row : parseNamedCsvRows(rows)) {
                String name = valueOrUnknown(row.get("Name"));
                if (name.equals("unknown")) {
                    continue;
                }
                String deviceClass = includeClass ? valueOrUnknown(row.get("PNPClass")) : "";
                String manufacturer = valueOrUnknown(row.get("Manufacturer"));
                String status = valueOrUnknown(row.get("Status"));
                CompactDevice device = new CompactDevice(name, deviceClass, manufacturer, status);
                unique.putIfAbsent(name + "|" + deviceClass + "|" + manufacturer + "|" + status, device);
            }
            List<CompactDevice> out = new ArrayList<>(unique.values());
            out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            return out;
        }

        private static Map<String, Integer> countByClass(List<CompactDevice> devices) {
            TreeMap<String, Integer> counts = new TreeMap<>();
            for (CompactDevice device : devices) {
                String key = device.deviceClass == null || device.deviceClass.isBlank()
                    ? "unknown"
                    : device.deviceClass.toLowerCase(Locale.ROOT);
                counts.merge(key, 1, Integer::sum);
            }
            return counts;
        }

        private static List<GpuPreference> queryGpuPreferences() {
            String rows = runPs("$prefs = Get-ItemProperty 'HKCU:\\Software\\Microsoft\\DirectX\\UserGpuPreferences' -ErrorAction SilentlyContinue; "
                + "if ($null -eq $prefs) { @() } else { "
                + "$prefs.PSObject.Properties | Where-Object { $_.MemberType -eq 'NoteProperty' } | "
                + "Select-Object Name,Value | ConvertTo-Csv -NoTypeInformation }");
            List<GpuPreference> out = new ArrayList<>();
            for (Map<String, String> row : parseNamedCsvRows(rows)) {
                String application = valueOrUnknown(row.get("Name"));
                if (application.equals("unknown")) {
                    continue;
                }
                out.add(new GpuPreference(application, parseGpuPreferenceValue(row.get("Value"))));
            }
            out.sort((a, b) -> a.application.compareToIgnoreCase(b.application));
            return out;
        }

        private static GraphicsSettings queryGraphicsSettings() {
            Map<String, String> gameBar = firstRow(runPs("Get-ItemProperty 'HKCU:\\Software\\Microsoft\\GameBar' -ErrorAction SilentlyContinue | Select-Object AutoGameModeEnabled | ConvertTo-Csv -NoTypeInformation"));
            Map<String, String> gameConfig = firstRow(runPs("Get-ItemProperty 'HKCU:\\System\\GameConfigStore' -ErrorAction SilentlyContinue | Select-Object GameDVR_Enabled,GameDVR_FSEBehaviorMode,VRREnabled | ConvertTo-Csv -NoTypeInformation"));
            Map<String, String> graphicsDrivers = firstRow(runPs("Get-ItemProperty 'HKLM:\\SYSTEM\\CurrentControlSet\\Control\\GraphicsDrivers' -ErrorAction SilentlyContinue | Select-Object TdrLevel,TdrDelay,TdrDdiDelay | ConvertTo-Csv -NoTypeInformation"));
            Map<String, String> scheduler = firstRow(runPs("Get-ItemProperty 'HKLM:\\SYSTEM\\CurrentControlSet\\Control\\GraphicsDrivers\\Scheduler' -ErrorAction SilentlyContinue | Select-Object HwSchMode | ConvertTo-Csv -NoTypeInformation"));
            return new GraphicsSettings(
                parseBooleanFlag(gameBar.get("AutoGameModeEnabled")),
                parseBooleanFlag(gameConfig.get("GameDVR_Enabled")),
                parseInteger(gameConfig.get("GameDVR_FSEBehaviorMode")),
                parseBooleanFlag(gameConfig.get("VRREnabled")),
                parseHagsMode(scheduler.get("HwSchMode")),
                parseInteger(graphicsDrivers.get("TdrLevel")),
                parseInteger(graphicsDrivers.get("TdrDelay")),
                parseInteger(graphicsDrivers.get("TdrDdiDelay")));
        }

        private static List<OverlayProcess> queryOverlayProcesses() {
            String rows = runPs("Get-Process | Where-Object { $_.ProcessName -match 'rtss|afterburner|obs|discord|overwolf|nvidia|amd|steelseries|razer|logi|ghub|sonar|voicemeeter|nahimic|armoury|icue|nzxt|asus|msi' } | Group-Object ProcessName | Select-Object Name,Count | ConvertTo-Csv -NoTypeInformation");
            List<OverlayProcess> out = new ArrayList<>();
            for (Map<String, String> row : parseNamedCsvRows(rows)) {
                String name = valueOrUnknown(row.get("Name"));
                if (name.equals("unknown")) {
                    continue;
                }
                Integer count = parseInteger(row.get("Count"));
                out.add(new OverlayProcess(name, count == null ? 1 : count));
            }
            out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            return out;
        }

        private static Map<String, String> firstRow(String rows) {
            List<Map<String, String>> parsed = parseNamedCsvRows(rows);
            return parsed.isEmpty() ? Map.of() : parsed.get(0);
        }

        private static String valueOrUnknown(String rawValue) {
            if (rawValue == null || rawValue.isBlank()) {
                return "unknown";
            }
            return rawValue.trim();
        }
    }

    private record DetailedGpuAdapter(String name, String driverVersion, String videoProcessor, String driverModel, long vramMb) {
    }

    private record DisplayDevice(String name, int width, int height) {
    }

    private record CompactDevice(String name, String deviceClass, String manufacturer, String status) {
    }

    static record PowerPlan(String guid, String name) {
    }

    private record GpuPreference(String application, String preference) {
    }

    private record GraphicsSettings(
        Boolean autoGameModeEnabled,
        Boolean gameDvrEnabled,
        Integer gameDvrFseBehaviorMode,
        Boolean vrrEnabled,
        String hagsMode,
        Integer tdrLevel,
        Integer tdrDelaySeconds,
        Integer tdrDdiDelaySeconds) {
    }

    private record OverlayProcess(String name, int count) {
    }
}
