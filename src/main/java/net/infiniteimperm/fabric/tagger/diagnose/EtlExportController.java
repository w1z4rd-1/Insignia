package net.infiniteimperm.fabric.tagger.diagnose;

import net.infiniteimperm.fabric.tagger.TaggerMod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class EtlExportController {
    private final ToolDetection.DetectionReport detection;
    private final Path runDir;
    private final Path logsDir;
    private final List<String> notes;

    public EtlExportController(ToolDetection.DetectionReport detection, Path runDir, Path logsDir, List<String> notes) {
        this.detection = detection;
        this.runDir = runDir;
        this.logsDir = logsDir;
        this.notes = notes;
    }

    public void exportIfPossible() {
        Path etl = runDir.resolve("trace.etl");
        if (!Files.exists(etl)) {
            notes.add("No trace.etl produced; skipping ETL exports.");
            return;
        }

        boolean exported = false;
        if (detection.wpaExporter != null && detection.wpaExporter.found && detection.wpaExporter.path != null) {
            exported = runWpaExporter(etl);
        }
        if (!exported && detection.tracerpt != null && detection.tracerpt.found && detection.tracerpt.path != null) {
            runTracerpt(etl);
        }
    }

    private boolean runWpaExporter(Path etl) {
        try {
            Path profile = runDir.resolve("MinecraftStutter.wpaProfile");
            copyBundledProfile(profile);
            Path exportsDir = runDir.resolve("wpa_exports");
            Files.createDirectories(exportsDir);
            Path stdout = logsDir.resolve("wpaexporter.stdout.log");
            Path stderr = logsDir.resolve("wpaexporter.stderr.log");

            List<String> cmd = List.of(
                detection.wpaExporter.path.toString(),
                "-i", etl.toAbsolutePath().toString(),
                "-profile", profile.toAbsolutePath().toString(),
                "-o", exportsDir.toAbsolutePath().toString()
            );
            ProcessRunner.ProcessResult result = ProcessRunner.run(cmd, runDir, stdout, stderr, Duration.ofMinutes(2));
            notes.add("WPAExporter attempted: exit=" + result.exitCode() + " timeout=" + result.timedOut());
            return result.exitCode() == 0 && !result.timedOut();
        } catch (Exception e) {
            notes.add("WPAExporter failed: " + e.getMessage());
            TaggerMod.LOGGER.warn("[Diagnose][ETL] WPAExporter failed", e);
            return false;
        }
    }

    private void copyBundledProfile(Path target) {
        try {
            try (var in = EtlExportController.class.getClassLoader()
                .getResourceAsStream("assets/tagger/profiles/MinecraftStutter.wpaProfile")) {
                if (in == null) {
                    Files.writeString(target, "<?xml version=\"1.0\" encoding=\"utf-8\"?><Profile />");
                    notes.add("Bundled WPA profile missing from resources; wrote placeholder.");
                    return;
                }
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            notes.add("Failed to copy bundled WPA profile: " + e.getMessage());
            TaggerMod.LOGGER.warn("[Diagnose][ETL] Failed to copy bundled WPA profile", e);
        }
    }

    private void runTracerpt(Path etl) {
        try {
            Path csv = runDir.resolve("trace_raw.csv");
            Path stdout = logsDir.resolve("tracerpt.stdout.log");
            Path stderr = logsDir.resolve("tracerpt.stderr.log");
            List<String> cmd = List.of(
                detection.tracerpt.path.toString(),
                etl.toAbsolutePath().toString(),
                "-of", "CSV",
                "-o", csv.toAbsolutePath().toString()
            );
            ProcessRunner.ProcessResult result = ProcessRunner.run(cmd, runDir, stdout, stderr, Duration.ofMinutes(2));
            notes.add("tracerpt attempted: exit=" + result.exitCode() + " timeout=" + result.timedOut());
        } catch (Exception e) {
            notes.add("tracerpt failed: " + e.getMessage());
            TaggerMod.LOGGER.warn("[Diagnose][ETL] tracerpt failed", e);
        }
    }
}
