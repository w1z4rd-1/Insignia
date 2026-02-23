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
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class BundleWriter {
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
        sb.append("- logs/*.log\n\n");
        sb.append("How to inspect recording.jfr:\n");
        sb.append("- Open with JDK Mission Control (JMC), then inspect Method Profiling / Memory / Lock Instances.\n\n");
        sb.append("Nsight Systems docs:\n");
        sb.append("- https://docs.nvidia.com/nsight-systems/AnalysisGuide/index.html\n\n");
        sb.append("Frame boundary notes:\n");
        sb.append("- HUD render callback frame boundaries were recorded as a stable frame proxy.\n\n");
        sb.append("CapFrameX note:\n");
        sb.append("- CapFrameX is a popular PresentMon analysis tool: https://github.com/CXWorld/CapFrameX\n");
        sb.append("- Analysts/AI may use CapFrameX-style interpretation and Python to analyze this bundle.\n\n");
        sb.append("Optimized ChatGPT Analysis Prompt:\n");
        sb.append("- Analyze this diagnostics bundle for Minecraft stutter root causes. Use presentmon.csv for frame-time outliers,\n");
        sb.append("  recording.jfr for CPU/allocation/lock/GC correlation, and optional ETL/nsys outputs for system behavior.\n");
        sb.append("  Produce: (1) top likely root causes, (2) confidence-ranked evidence with timestamps, (3) specific mod/config/runtime fixes,\n");
        sb.append("  (4) prioritized next measurements to disambiguate uncertainty. If internet access is available, search for current mods or\n");
        sb.append("  settings known to mitigate the identified issue patterns and cite them.\n\n");
        if (!notes.isEmpty()) {
            sb.append("Notes:\n");
            for (String note : notes) {
                sb.append("- ").append(note).append("\n");
            }
        }
        sb.append("\nPrivacy reminder:\n");
        sb.append("- Before sharing, review files for anything sensitive.\n");
        Files.writeString(outFile, sb.toString(), StandardCharsets.UTF_8);
        return outFile;
    }

    public static void includeFullModeFiles(Path runDir, List<String> notes) {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        copyIfExists(gameDir.resolve("logs").resolve("latest.log"), runDir.resolve("latest.log"), notes);
        copyIfExists(gameDir.resolve("options.txt"), runDir.resolve("options.txt"), notes);
        copyIfExists(FabricLoader.getInstance().getConfigDir().resolve("sodium-options.json"), runDir.resolve("sodium-options.json"), notes);
        copyIfExists(FabricLoader.getInstance().getConfigDir().resolve("sodium-extra-options.json"), runDir.resolve("sodium-extra-options.json"), notes);
        copyIfExists(FabricLoader.getInstance().getConfigDir().resolve("lithium.properties"), runDir.resolve("lithium.properties"), notes);
    }

    private static void copyIfExists(Path from, Path to, List<String> notes) {
        try {
            if (!Files.exists(from)) {
                return;
            }
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
            notes.add("Included file: " + from.getFileName());
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

    public static void redactTextFileInPlace(Path file, DiagnoseOrchestrator.Mode mode) throws IOException {
        if (mode != DiagnoseOrchestrator.Mode.PRIVACY || !Files.exists(file) || !Files.isRegularFile(file)) {
            return;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.equals("system_info.json")) {
            return;
        }
        String content = Files.readString(file, StandardCharsets.UTF_8);
        if (name.equals("options.txt") || name.contains("sodium") || name.contains("lithium")) {
            content = RedactionUtil.sanitizeKnownConfigContent(content);
        }
        // Redact Windows user paths (e.g. C:\Users\<name>\...) present in all text artifacts.
        content = RedactionUtil.redactUserPathOnly(content);
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static String escape(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
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
            List<GpuAdapter> gpus = new ArrayList<>();
            String rows = runPs("Get-CimInstance Win32_VideoController | ForEach-Object { \"$($_.Name)||$($_.DriverVersion)||$($_.AdapterRAM)\" }");
            if (rows == null || rows.isBlank() || rows.equals("unknown")) {
                return gpus;
            }
            for (String row : rows.split("\\R")) {
                if (row == null || row.isBlank()) {
                    continue;
                }
                String[] parts = row.split("\\|\\|", -1);
                String name = parts.length > 0 ? parts[0].trim() : "unknown";
                String driver = parts.length > 1 ? parts[1].trim() : "unknown";
                long vramMb = -1L;
                if (parts.length > 2) {
                    try {
                        vramMb = Long.parseLong(parts[2].trim()) / (1024L * 1024L);
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

        private static String runPs(String expr) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-Command",
                    expr);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                }
                return out.isBlank() ? "unknown" : out;
            } catch (Exception e) {
                return "unknown";
            }
        }
    }

    private record GpuAdapter(String name, String driverVersion, long vramMb) {
    }
}
