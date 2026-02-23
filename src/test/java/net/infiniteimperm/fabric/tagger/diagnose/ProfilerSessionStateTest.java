package net.infiniteimperm.fabric.tagger.diagnose;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfilerSessionStateTest {
    @TempDir
    Path tempDir;

    @Test
    void savesLoadsAndClearsState() {
        Path stateFile = tempDir.resolve("active-session.properties");
        ProfilerSessionState.State state = new ProfilerSessionState.State();
        state.ownerPid = 1234L;
        state.runDir = "C:\\temp\\run";
        state.nsysSession = "insignia_abc";
        state.nsysStartedByUs = true;
        state.wprStartedByUs = true;

        ProfilerSessionState.save(stateFile, state);
        assertTrue(Files.exists(stateFile));

        var loaded = ProfilerSessionState.load(stateFile);
        assertTrue(loaded.isPresent());
        assertEquals(1234L, loaded.get().ownerPid);
        assertEquals("insignia_abc", loaded.get().nsysSession);
        assertTrue(loaded.get().nsysStartedByUs);
        assertTrue(loaded.get().wprStartedByUs);

        ProfilerSessionState.clear(stateFile);
        assertFalse(Files.exists(stateFile));
    }
}
