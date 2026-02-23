package net.infiniteimperm.fabric.tagger.diagnose;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EtlExportControllerTest {
    @TempDir
    Path tempDir;

    @Test
    void doesNotRunTracerptWhenWpaExporterSucceeds() throws Exception {
        Path runDir = tempDir.resolve("run");
        Path logsDir = runDir.resolve("logs");
        Files.createDirectories(logsDir);
        Files.writeString(runDir.resolve("trace.etl"), "etl", StandardCharsets.UTF_8);

        Path wpaMarker = runDir.resolve("wpa.called");
        Path tracerMarker = runDir.resolve("tracer.called");
        Path wpa = createScript("wpa-success.bat",
            "@echo off\r\n" +
                "echo called>\"" + wpaMarker + "\"\r\n" +
                "exit /b 0\r\n");
        Path tracer = createScript("tracer-not-called.bat",
            "@echo off\r\n" +
                "echo called>\"" + tracerMarker + "\"\r\n" +
                "exit /b 0\r\n");

        ToolDetection.DetectionReport report = fakeReport(wpa, tracer);
        List<String> notes = new ArrayList<>();
        new EtlExportController(report, runDir, logsDir, notes).exportIfPossible();

        assertTrue(Files.exists(wpaMarker));
        assertFalse(Files.exists(tracerMarker));
    }

    @Test
    void fallsBackToTracerptWhenWpaExporterFails() throws Exception {
        Path runDir = tempDir.resolve("run2");
        Path logsDir = runDir.resolve("logs");
        Files.createDirectories(logsDir);
        Files.writeString(runDir.resolve("trace.etl"), "etl", StandardCharsets.UTF_8);

        Path wpaMarker = runDir.resolve("wpa.failed.called");
        Path tracerMarker = runDir.resolve("tracer.called");
        Path wpa = createScript("wpa-fail.bat",
            "@echo off\r\n" +
                "echo called>\"" + wpaMarker + "\"\r\n" +
                "exit /b 1\r\n");
        Path tracer = createScript("tracer-fallback.bat",
            "@echo off\r\n" +
                "echo called>\"" + tracerMarker + "\"\r\n" +
                "exit /b 0\r\n");

        ToolDetection.DetectionReport report = fakeReport(wpa, tracer);
        List<String> notes = new ArrayList<>();
        new EtlExportController(report, runDir, logsDir, notes).exportIfPossible();

        assertTrue(Files.exists(wpaMarker));
        assertTrue(Files.exists(tracerMarker));
        assertTrue(notes.stream().anyMatch(s -> s.contains("tracerpt attempted")));
    }

    private ToolDetection.DetectionReport fakeReport(Path wpaExe, Path tracerExe) {
        ToolDetection.DetectionReport report = ToolDetection.detectAll();
        report.wpaExporter.found = true;
        report.wpaExporter.path = wpaExe;
        report.tracerpt.found = true;
        report.tracerpt.path = tracerExe;
        return report;
    }

    private Path createScript(String fileName, String content) throws Exception {
        Path script = tempDir.resolve(fileName);
        Files.writeString(script, content, StandardCharsets.UTF_8);
        return script;
    }
}
