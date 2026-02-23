package net.infiniteimperm.fabric.tagger.diagnose;

import net.fabricmc.loader.api.FabricLoader;
import net.infiniteimperm.fabric.tagger.TaggerMod;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ToolDetection {
    private ToolDetection() {
    }

    public static DetectionReport detectAll() {
        DetectionReport report = new DetectionReport();
        report.windows = isWindows();
        report.jfrAvailable = isJfrAvailable();
        report.presentMon = detectExe("PresentMon", "presentmon.exe", commonPresentMonPaths());
        report.nsys = detectExe("Nsight Systems", "nsys.exe", commonNsysPaths());
        report.wpr = detectExe("WPR", "wpr.exe", commonWprPaths());
        report.wpaExporter = detectExe("WPAExporter", "wpaexporter.exe", commonWpaExporterPaths());
        report.tracerpt = detectExe("tracerpt", "tracerpt.exe", commonTracerptPaths());
        report.typeperf = detectExe("typeperf", "typeperf.exe", commonTypeperfPaths());
        report.nvidiaSmi = detectExe("nvidia-smi", "nvidia-smi.exe", commonNvidiaSmiPaths());
        report.sparkPresent = FabricLoader.getInstance().isModLoaded("spark");
        return report;
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isJfrAvailable() {
        try {
            Class.forName("jdk.jfr.Recording");
            Class.forName("jdk.jfr.consumer.RecordingFile");
            return true;
        } catch (Throwable t) {
            TaggerMod.LOGGER.warn("[Diagnose][Detect] JFR not available", t);
            return false;
        }
    }

    private static ToolInfo detectExe(String name, String exeName, List<Path> commonPaths) {
        ToolInfo info = new ToolInfo(name, exeName);
        Set<Path> candidates = new LinkedHashSet<>();
        candidates.addAll(pathCandidatesFromEnv(exeName));
        candidates.addAll(commonPaths);

        for (Path candidate : candidates) {
            info.checkedPaths.add(candidate.toAbsolutePath().toString());
            if (Files.isRegularFile(candidate)) {
                info.found = true;
                info.path = candidate.toAbsolutePath();
                break;
            }
        }
        if (info.found) {
            TaggerMod.LOGGER.info("[Diagnose][Detect] {} found at {}", name, info.path);
        } else {
            TaggerMod.LOGGER.warn("[Diagnose][Detect] {} missing. Checked {} paths", name, info.checkedPaths.size());
        }
        return info;
    }

    private static List<Path> pathCandidatesFromEnv(String exeName) {
        List<Path> paths = new ArrayList<>();
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return paths;
        }
        String[] entries = pathEnv.split(File.pathSeparator);
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            paths.add(Path.of(entry).resolve(exeName));
        }
        return paths;
    }

    private static List<Path> commonPresentMonPaths() {
        List<Path> paths = new ArrayList<>();
        String pf = System.getenv("ProgramFiles");
        String pfx86 = System.getenv("ProgramFiles(x86)");
        String local = System.getenv("LOCALAPPDATA");
        if (pf != null) {
            paths.add(Path.of(pf, "Intel", "PresentMon", "PresentMonConsoleApplication", "PresentMon-2.3.1-x64.exe"));
            paths.add(Path.of(pf, "Intel", "PresentMon", "PresentMonConsoleApplication", "PresentMon.exe"));
            paths.add(Path.of(pf, "Intel", "PresentMon", "PresentMonApplication", "PresentMon.exe"));
            paths.add(Path.of(pf, "NVIDIA Corporation", "FrameViewSDK", "bin", "PresentMon_x64.exe"));
            paths.add(Path.of(pf, "PresentMon", "PresentMon.exe"));
            paths.addAll(findMatchingExecutables(Path.of(pf, "Intel", "PresentMon", "PresentMonConsoleApplication"), "PresentMon*.exe"));
            paths.addAll(findMatchingExecutables(Path.of(pf, "Intel", "PresentMon"), "PresentMon*.exe"));
        }
        if (pfx86 != null) {
            paths.add(Path.of(pfx86, "PresentMon", "PresentMon.exe"));
            paths.addAll(findMatchingExecutables(Path.of(pfx86, "Intel", "PresentMon", "PresentMonConsoleApplication"), "PresentMon*.exe"));
        }
        if (local != null) {
            paths.add(Path.of(local, "Programs", "PresentMon", "PresentMon.exe"));
            paths.addAll(findMatchingExecutables(Path.of(local, "Programs", "PresentMon"), "PresentMon*.exe"));
        }
        return paths;
    }

    private static List<Path> commonNsysPaths() {
        List<Path> paths = new ArrayList<>();
        String pf = System.getenv("ProgramFiles");
        String pfx86 = System.getenv("ProgramFiles(x86)");
        if (pf != null) {
            paths.add(Path.of(pf, "NVIDIA Corporation", "Nsight Systems", "nsys.exe"));
            paths.add(Path.of(pf, "NVIDIA Corporation", "Nsight Systems", "target-windows-x64", "nsys.exe"));
            paths.addAll(findVersionedNsightCliPaths(Path.of(pf, "NVIDIA Corporation")));
            paths.addAll(wildcardSearch(Path.of(pf, "NVIDIA Corporation"), "nsys.exe"));
        }
        if (pfx86 != null) {
            paths.add(Path.of(pfx86, "NVIDIA Corporation", "Nsight Systems", "nsys.exe"));
            paths.add(Path.of(pfx86, "NVIDIA Corporation", "Nsight Systems", "target-windows-x64", "nsys.exe"));
            paths.addAll(findVersionedNsightCliPaths(Path.of(pfx86, "NVIDIA Corporation")));
            paths.addAll(wildcardSearch(Path.of(pfx86, "NVIDIA Corporation"), "nsys.exe"));
        }
        return paths;
    }

    private static List<Path> commonWprPaths() {
        List<Path> paths = new ArrayList<>();
        String windir = System.getenv("WINDIR");
        if (windir != null) {
            paths.add(Path.of(windir, "System32", "wpr.exe"));
        }
        return paths;
    }

    private static List<Path> commonWpaExporterPaths() {
        List<Path> paths = new ArrayList<>();
        String pf = System.getenv("ProgramFiles");
        String pfx86 = System.getenv("ProgramFiles(x86)");
        if (pf != null) {
            paths.addAll(findMatchingExecutables(Path.of(pf, "Windows Kits"), "wpaexporter.exe"));
            paths.addAll(findMatchingExecutables(Path.of(pf, "Windows Kits", "10", "Windows Performance Toolkit"), "wpaexporter.exe"));
            paths.addAll(findMatchingExecutables(Path.of(pf, "Windows Kits", "11", "Windows Performance Toolkit"), "wpaexporter.exe"));
        }
        if (pfx86 != null) {
            paths.addAll(findMatchingExecutables(Path.of(pfx86, "Windows Kits"), "wpaexporter.exe"));
            paths.addAll(findMatchingExecutables(Path.of(pfx86, "Windows Kits", "10", "Windows Performance Toolkit"), "wpaexporter.exe"));
            paths.addAll(findMatchingExecutables(Path.of(pfx86, "Windows Kits", "11", "Windows Performance Toolkit"), "wpaexporter.exe"));
        }
        return paths;
    }

    private static List<Path> commonTracerptPaths() {
        List<Path> paths = new ArrayList<>();
        String windir = System.getenv("WINDIR");
        if (windir != null) {
            paths.add(Path.of(windir, "System32", "tracerpt.exe"));
        }
        return paths;
    }

    private static List<Path> commonTypeperfPaths() {
        List<Path> paths = new ArrayList<>();
        String windir = System.getenv("WINDIR");
        if (windir != null) {
            paths.add(Path.of(windir, "System32", "typeperf.exe"));
        }
        return paths;
    }

    private static List<Path> commonNvidiaSmiPaths() {
        List<Path> paths = new ArrayList<>();
        String windir = System.getenv("WINDIR");
        String pf = System.getenv("ProgramFiles");
        if (windir != null) {
            paths.add(Path.of(windir, "System32", "nvidia-smi.exe"));
        }
        if (pf != null) {
            paths.add(Path.of(pf, "NVIDIA Corporation", "NVSMI", "nvidia-smi.exe"));
        }
        return paths;
    }

    private static List<Path> wildcardSearch(Path root, String exeName) {
        List<Path> results = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return results;
        }
        File[] list = root.toFile().listFiles();
        if (list == null) {
            return results;
        }
        Arrays.sort(list);
        for (File entry : list) {
            if (!entry.isDirectory()) {
                continue;
            }
            Path hit = entry.toPath().resolve("Nsight Systems").resolve(exeName);
            results.add(hit);
        }
        return results;
    }

    private static List<Path> findMatchingExecutables(Path dir, String glob) {
        List<Path> results = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return results;
        }
        try (var stream = Files.newDirectoryStream(dir, glob)) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)) {
                    results.add(path);
                }
            }
        } catch (Exception ignored) {
        }
        return results;
    }

    private static List<Path> findVersionedNsightCliPaths(Path nvidiaRoot) {
        List<Path> paths = new ArrayList<>();
        if (!Files.isDirectory(nvidiaRoot)) {
            return paths;
        }
        try (var stream = Files.newDirectoryStream(nvidiaRoot, "Nsight Systems*")) {
            for (Path dir : stream) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                paths.add(dir.resolve("target-windows-x64").resolve("nsys.exe"));
                paths.add(dir.resolve("host-windows-x64").resolve("nsys.exe"));
            }
        } catch (Exception ignored) {
        }
        return paths;
    }

    public static final class DetectionReport {
        public boolean windows;
        public boolean jfrAvailable;
        public boolean sparkPresent;
        public ToolInfo presentMon;
        public ToolInfo nsys;
        public ToolInfo wpr;
        public ToolInfo wpaExporter;
        public ToolInfo tracerpt;
        public ToolInfo typeperf;
        public ToolInfo nvidiaSmi;
    }

    public static final class ToolInfo {
        public final String name;
        public final String executable;
        public boolean found;
        public Path path;
        public final List<String> checkedPaths = new ArrayList<>();

        private ToolInfo(String name, String executable) {
            this.name = name;
            this.executable = executable;
        }
    }
}
