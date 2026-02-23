package net.infiniteimperm.fabric.tagger.diagnose;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundleWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void zipResultsHandlesRequiredOptionalDuplicates() throws Exception {
        Path runDir = tempDir.resolve("run");
        Files.createDirectories(runDir);
        Path file = runDir.resolve("counters.csv");
        Files.writeString(file, "a,b\n1,2\n", StandardCharsets.UTF_8);

        Path zip = tempDir.resolve("bundle.zip");
        BundleWriter.zipResults(runDir, zip, List.of(file), List.of(file), List.of());

        assertTrue(Files.exists(zip));
        assertTrue(Files.size(zip) > 0L);
    }

    @Test
    void manifestIncludesTypeperfAsRequiredAndNvidiaAsOptional() throws Exception {
        ToolDetection.DetectionReport report = ToolDetection.detectAll();
        report.jfrAvailable = true;
        if (report.typeperf != null) {
            report.typeperf.found = true;
        }
        if (report.nvidiaSmi != null) {
            report.nvidiaSmi.found = true;
        }

        Path manifest = tempDir.resolve("index.json");
        Path req = tempDir.resolve("windows_hardware_counters.csv");
        Path opt = tempDir.resolve("nvidia_smi.csv");
        Files.writeString(req, "h1,h2\n", StandardCharsets.UTF_8);
        Files.writeString(opt, "gpu\n", StandardCharsets.UTF_8);

        BundleWriter.writeManifest(manifest, report, List.of(req), List.of(opt), List.of("ok"));
        String json = Files.readString(manifest, StandardCharsets.UTF_8);

        assertTrue(json.contains("\"typeperf\": true"));
        assertTrue(json.contains("\"nvidia_smi\": true"));
    }

    @Test
    void zipResultsFailsWhenRequiredArtifactIsMissing() throws Exception {
        Path runDir = tempDir.resolve("run-missing");
        Files.createDirectories(runDir);
        Path missing = runDir.resolve("missing-required.csv");
        Path zip = tempDir.resolve("bundle-missing.zip");

        assertThrows(IOException.class, () ->
            BundleWriter.zipResults(runDir, zip, List.of(missing), List.of(), new ArrayList<>()));
    }
}
