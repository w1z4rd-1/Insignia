package net.infiniteimperm.fabric.tagger.diagnose;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresentMonControllerTest {
    @Test
    void detectsAccessDeniedStartupText() {
        assertTrue(PresentMonController.isAccessDenied(
            "error: failed to start trace session: access denied."));
        assertTrue(PresentMonController.isAccessDenied(
            "PresentMon requires either administrative privileges or to be run by a user in the Performance Log Users user group."));
    }

    @Test
    void ignoresNonPrivilegeErrors() {
        assertFalse(PresentMonController.isAccessDenied(
            "error: unrecognized argument --foo"));
    }
}
