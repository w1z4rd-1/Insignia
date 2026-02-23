package net.infiniteimperm.fabric.tagger.diagnose;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessContentionSamplerTest {
    @TempDir
    Path tempDir;

    @Test
    void writesCsvHeaderAndLogFile() throws Exception {
        Path csv = tempDir.resolve("process_contention.csv");
        Path log = tempDir.resolve("process_contention.log");
        ProcessContentionSampler sampler = new ProcessContentionSampler(csv, log, 200L, 5);

        sampler.start();
        Thread.sleep(650L);
        sampler.stop();

        assertTrue(Files.exists(csv));
        assertTrue(Files.exists(log));
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertTrue(!lines.isEmpty());
        assertTrue(lines.get(0).contains("timestamp,pid,process,cpu_delta_ms,total_cpu_ms"));
    }

    @Test
    void handlesZeroTopNWithoutCrashing() throws Exception {
        Path csv = tempDir.resolve("process_contention_zero.csv");
        Path log = tempDir.resolve("process_contention_zero.log");
        ProcessContentionSampler sampler = new ProcessContentionSampler(csv, log, 150L, 0);

        sampler.start();
        Thread.sleep(400L);
        sampler.stop();

        assertTrue(Files.exists(csv));
        assertTrue(Files.exists(log));
    }
}
