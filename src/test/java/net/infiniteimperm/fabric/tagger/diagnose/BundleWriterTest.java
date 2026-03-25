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
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Test
    void gpuAdapterCsvParserHandlesQuotedNamesAndUnknownInput() {
        String rows = "\"Name\",\"DriverVersion\",\"AdapterRAM\"\n" +
            "\"NVIDIA GeForce RTX 4080, Laptop GPU\",\"31.0.15.1234\",\"12884901888\"\n" +
            "\"Intel(R) Iris(R) Xe Graphics\",\"31.0.101.5590\",\"1073741824\"\n";

        List<String[]> adapters = BundleWriter.parseGpuAdapterCsvRows(rows);
        assertEquals(2, adapters.size());
        assertEquals("NVIDIA GeForce RTX 4080, Laptop GPU", adapters.get(0)[0]);
        assertEquals("31.0.15.1234", adapters.get(0)[1]);
        assertEquals("12884901888", adapters.get(0)[2]);
        assertTrue(BundleWriter.parseGpuAdapterCsvRows("unknown").isEmpty());
    }

    @Test
    void namedCsvParserAndCompactSettingParsersHandleExpectedWindowsOutput() {
        String rows = "\"Name\",\"Value\"\n" +
            "\"minecraft.exe\",\"GpuPreference=2;\"\n" +
            "\"javaw.exe\",\"GpuPreference=1;\"\n";

        List<java.util.Map<String, String>> parsed = BundleWriter.parseNamedCsvRows(rows);
        assertEquals(2, parsed.size());
        assertEquals("minecraft.exe", parsed.get(0).get("Name"));
        assertEquals("high_performance", BundleWriter.parseGpuPreferenceValue(parsed.get(0).get("Value")));
        assertEquals("power_saving", BundleWriter.parseGpuPreferenceValue(parsed.get(1).get("Value")));
        assertTrue(BundleWriter.parseNamedCsvRows("unknown").isEmpty());
        assertEquals("enabled", BundleWriter.parseHagsMode("2"));
    }

    @Test
    void powerPlanParserExtractsGuidAndFriendlyName() {
        BundleWriter.PowerPlan plan = BundleWriter.parsePowerPlanOutput(
            "Power Scheme GUID: 381b4222-f694-41f0-9685-ff5bb260df2e  (Balanced)");

        assertEquals("381b4222-f694-41f0-9685-ff5bb260df2e", plan.guid());
        assertEquals("Balanced", plan.name());
    }
}
