package net.infiniteimperm.fabric.tagger.diagnose;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NsysExternalCollectorTest {
    @TempDir
    Path tempDir;

    @Test
    void findsRecentNsightReportsFromConfiguredRoots() throws Exception {
        Path root = tempDir.resolve("root");
        Path child = root.resolve("sub");
        Files.createDirectories(child);
        Path recent = child.resolve("capture.nsys-rep");
        Path old = child.resolve("old.qdrep");
        Files.writeString(recent, "rep", StandardCharsets.UTF_8);
        Files.writeString(old, "rep-old", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(7200)));

        Set<Path> roots = new LinkedHashSet<>();
        roots.add(root);
        List<Path> found = NsysExternalCollector.findCandidatesFromRoots(Instant.now().minusSeconds(120), roots);

        assertEquals(1, found.size());
        assertTrue(found.get(0).getFileName().toString().equals("capture.nsys-rep"));
    }
}
