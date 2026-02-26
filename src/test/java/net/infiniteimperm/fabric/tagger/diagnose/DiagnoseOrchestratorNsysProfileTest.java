package net.infiniteimperm.fabric.tagger.diagnose;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnoseOrchestratorNsysProfileTest {
    @Test
    void buildsLowOverheadNsysArgsWithExpectedSignals() {
        List<String> args = DiagnoseOrchestrator.buildNsysLowOverheadProfileArgs("C:/tmp/nsys-report", 120);

        assertTrue(args.contains("--trace=wddm"));
        assertTrue(args.contains("--sample=none"));
        assertTrue(args.contains("--cpuctxsw=none"));
        assertTrue(args.contains("--force-overwrite=true"));
        int durationFlag = args.indexOf("--duration");
        assertTrue(durationFlag >= 0);
        assertEquals("135", args.get(durationFlag + 1));
        assertTrue(args.stream().anyMatch(s -> s.equals("cmd")));
    }

    @Test
    void nsysDurationAddsSmallBufferAndClampsMinimum() {
        assertEquals(135, DiagnoseOrchestrator.nsysDurationSeconds(120));
        assertEquals(15, DiagnoseOrchestrator.nsysDurationSeconds(0));
    }

    @Test
    void nsysDeadlineUsesActualLaunchWhenAvailable() {
        long captureStart = 1_000L;
        long nsysLaunch = 5_000L;
        long deadline = DiagnoseOrchestrator.computeNsysTimedDeadlineMs(captureStart, nsysLaunch, 10);
        assertEquals(30_000L, deadline);

        long fallbackDeadline = DiagnoseOrchestrator.computeNsysTimedDeadlineMs(captureStart, 0L, 10);
        assertEquals(26_000L, fallbackDeadline);
    }
}
