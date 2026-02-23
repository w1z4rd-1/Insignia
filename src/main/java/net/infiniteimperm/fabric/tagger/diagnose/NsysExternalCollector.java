package net.infiniteimperm.fabric.tagger.diagnose;

import net.infiniteimperm.fabric.tagger.TaggerMod;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class NsysExternalCollector {
    private NsysExternalCollector() {
    }

    public static void collectPossibleReports(Path runDir, Instant captureStart, List<String> notes) {
        try {
            Instant cutoff = captureStart != null ? captureStart.minusSeconds(90L) : Instant.now().minusSeconds(600L);
            List<Path> candidates = findCandidates(cutoff);
            int copied = 0;
            for (Path src : candidates) {
                if (!Files.isRegularFile(src)) {
                    continue;
                }
                Path target = uniqueTarget(runDir, "external-" + src.getFileName());
                Files.copy(src, target);
                copied++;
            }
            notes.add("External Nsight report pickup copied files: " + copied);
        } catch (Exception e) {
            notes.add("External Nsight report pickup failed: " + e.getMessage());
            TaggerMod.LOGGER.warn("[Diagnose][Nsight] Failed external report pickup", e);
        }
    }

    static List<Path> findCandidates(Instant cutoff) {
        Set<Path> roots = new LinkedHashSet<>();
        roots.add(Path.of(System.getProperty("user.dir")));
        String userProfile = System.getenv("USERPROFILE");
        if (userProfile != null && !userProfile.isBlank()) {
            roots.add(Path.of(userProfile));
            roots.add(Path.of(userProfile, "Documents"));
            roots.add(Path.of(userProfile, "Documents", "Nsight Systems Projects"));
            roots.add(Path.of(userProfile, "Desktop"));
        }
        return findCandidatesFromRoots(cutoff, roots);
    }

    static List<Path> findCandidatesFromRoots(Instant cutoff, Set<Path> roots) {
        List<Path> out = new ArrayList<>();
        for (Path root : roots) {
            scanShallow(root, cutoff, out);
            try (DirectoryStream<Path> children = Files.newDirectoryStream(root, path -> Files.isDirectory(path))) {
                for (Path child : children) {
                    scanShallow(child, cutoff, out);
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static void scanShallow(Path dir, Instant cutoff, List<Path> out) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, path -> {
            if (!Files.isRegularFile(path)) {
                return false;
            }
            String name = path.getFileName().toString().toLowerCase();
            if (!(name.endsWith(".nsys-rep") || name.endsWith(".qdrep"))) {
                return false;
            }
            try {
                return Files.getLastModifiedTime(path).toInstant().isAfter(cutoff);
            } catch (Exception e) {
                return false;
            }
        })) {
            for (Path file : files) {
                out.add(file);
            }
        } catch (Exception ignored) {
        }
    }

    private static Path uniqueTarget(Path runDir, String fileName) throws Exception {
        Path target = runDir.resolve(fileName);
        if (!Files.exists(target)) {
            return target;
        }
        int index = 2;
        while (true) {
            int dot = fileName.lastIndexOf('.');
            String stem = dot >= 0 ? fileName.substring(0, dot) : fileName;
            String ext = dot >= 0 ? fileName.substring(dot) : "";
            Path candidate = runDir.resolve(stem + "-" + index + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
            index++;
        }
    }
}
