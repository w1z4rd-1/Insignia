package net.infiniteimperm.fabric.tagger.diagnose;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundleWriterZipPartitionTest {
    @TempDir
    Path tempDir;

    @Test
    void requiredFilesAreNeverDroppedDuringPartitioning() throws Exception {
        Path runDir = Files.createDirectories(tempDir.resolve("run"));
        Path reqJfr = writeRandom(runDir, "results/recording.jfr", 180_000, 7L);
        Path reqPresent = writeRandom(runDir, "results/presentmon.csv", 140_000, 8L);
        Path reqMeta = writeRandom(runDir, "results/system_info.json", 20_000, 9L);
        Path optionalBig = writeRandom(runDir, "results/optional-big.bin", 220_000, 10L);

        List<String> notes = new ArrayList<>();
        List<Path> zips = BundleWriter.zipResultsPartitioned(
            runDir,
            "bundle",
            List.of(reqJfr, reqPresent, reqMeta),
            List.of(optionalBig),
            notes,
            120L * 1024L,
            5
        );

        Set<String> entries = zipEntries(zips);
        assertTrue(entries.contains("results/recording.jfr"));
        assertTrue(entries.contains("results/presentmon.csv"));
        assertTrue(entries.contains("results/system_info.json"));
        assertFalse(zips.isEmpty());
    }

    @Test
    void optionalFilesAreSkippedWhenPartLimitWouldBeExceeded() throws Exception {
        Path runDir = Files.createDirectories(tempDir.resolve("run-limit"));
        Path required = writeRandom(runDir, "results/system_info.json", 80_000, 11L);
        Path optional = writeRandom(runDir, "results/optional-large.bin", 80_000, 12L);

        List<String> notes = new ArrayList<>();
        List<Path> zips = BundleWriter.zipResultsPartitioned(
            runDir,
            "bundle-limit",
            List.of(required),
            List.of(optional),
            notes,
            100L * 1024L,
            1
        );

        assertEquals(1, zips.size());
        Set<String> entries = zipEntries(zips);
        assertTrue(entries.contains("results/system_info.json"));
        assertFalse(entries.contains("results/optional-large.bin"));
        assertTrue(notes.stream().anyMatch(n -> n.contains("Skipped 1 file")));
    }

    @Test
    void oversizeSingleRequiredFileIsKeptAndNoted() throws Exception {
        Path runDir = Files.createDirectories(tempDir.resolve("run-oversize"));
        Path required = writeRandom(runDir, "results/recording.jfr", 90_000, 13L);

        List<String> notes = new ArrayList<>();
        List<Path> zips = BundleWriter.zipResultsPartitioned(
            runDir,
            "bundle-oversize",
            List.of(required),
            List.of(),
            notes,
            20L * 1024L,
            1
        );

        assertEquals(1, zips.size());
        Set<String> entries = zipEntries(zips);
        assertTrue(entries.contains("results/recording.jfr"));
        assertTrue(notes.stream().anyMatch(n -> n.contains("Single-file zip exceeds size cap")));
    }

    private static Path writeRandom(Path runDir, String relativePath, int size, long seed) throws Exception {
        Path out = runDir.resolve(relativePath);
        Files.createDirectories(out.getParent());
        byte[] data = new byte[size];
        new Random(seed).nextBytes(data);
        Files.write(out, data);
        return out;
    }

    private static Set<String> zipEntries(List<Path> zips) throws Exception {
        Set<String> out = new HashSet<>();
        for (Path zip : zips) {
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    out.add(entry.getName());
                    zis.closeEntry();
                }
            }
        }
        return out;
    }
}
