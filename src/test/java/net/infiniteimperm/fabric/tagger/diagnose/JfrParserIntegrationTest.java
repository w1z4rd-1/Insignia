package net.infiniteimperm.fabric.tagger.diagnose;

import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class JfrParserIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void generatesBadFramesJsonFromInputs() throws Exception {
        Path jfr = tempDir.resolve("recording.jfr");
        Path csv = tempDir.resolve("presentmon.csv");
        Path out = tempDir.resolve("bad_frames.json");

        try (Recording recording = new Recording()) {
            recording.start();
            Thread.sleep(20L);
            recording.stop();
            recording.dump(jfr);
        }

        String csvText = "TimeInSeconds,MsBetweenPresents\n" +
            "0.001,10.0\n" +
            "0.020,22.5\n";
        Files.writeString(csv, csvText, StandardCharsets.UTF_8);

        Instant start = Instant.now().minusSeconds(1);
        Instant end = Instant.now();
        new JfrParser().parse(jfr, csv, out, start, end);

        String json = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"bad_frames\": 1"));
        assertTrue(json.contains("\"frame_ms\": 22.500"));
        assertTrue(json.contains("\"baseline\""));
    }

    @Test
    void throwsOnMissingFrameColumn() throws Exception {
        Path jfr = tempDir.resolve("recording.jfr");
        Path csv = tempDir.resolve("presentmon_invalid.csv");
        Path out = tempDir.resolve("bad_frames.json");

        try (Recording recording = new Recording()) {
            recording.start();
            recording.stop();
            recording.dump(jfr);
        }

        Files.writeString(csv, "TimeInSeconds,RandomColumn\n0.010,123\n", StandardCharsets.UTF_8);

        try {
            new JfrParser().parse(jfr, csv, out, Instant.now().minusSeconds(1), Instant.now());
            fail("Expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("missing frame time column"));
        }
    }

    @Test
    void ignoresPresentMonFramesOutsideCaptureWindow() throws Exception {
        Path jfr = tempDir.resolve("recording-windowed.jfr");
        Path csv = tempDir.resolve("presentmon-windowed.csv");
        Path out = tempDir.resolve("bad_frames-windowed.json");

        try (Recording recording = new Recording()) {
            recording.start();
            recording.stop();
            recording.dump(jfr);
        }

        Instant start = Instant.parse("2026-03-24T12:00:00Z");
        Instant end = start.plusMillis(100);
        String csvText = "TimeInSeconds,MsBetweenPresents\n" +
            "0.010,10.0\n" +
            "0.050,25.0\n" +
            "0.105,40.0\n" +
            "0.250,40.0\n";
        Files.writeString(csv, csvText, StandardCharsets.UTF_8);

        new JfrParser().parse(jfr, csv, out, start, end);

        String json = Files.readString(out, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"total_frames\": 2"));
        assertTrue(json.contains("\"bad_frames\": 1"));
        assertTrue(json.contains("\"ignored_out_of_window_frames\": 2"));
        assertTrue(!json.contains("\"frame_ms\": 40.000"));
    }
}
