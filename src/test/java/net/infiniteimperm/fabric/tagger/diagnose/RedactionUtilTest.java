package net.infiniteimperm.fabric.tagger.diagnose;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactionUtilTest {
    @Test
    void redactsOnlyWindowsUserPathForGenericText() {
        String input = "User path C:\\Users\\Terac\\AppData host test.example.net:25565";
        String redacted = RedactionUtil.redactUserPathOnly(input);

        assertFalse(redacted.contains("Terac"));
        assertTrue(redacted.contains("test.example.net:25565"));
    }

    @Test
    void sanitizesKnownConfigKeysWithoutGlobalHostScrubbing() {
        String config = "server=play.example.net:25565\nother=keep-me\npath=C:\\Users\\Terac\\mods";
        String sanitized = RedactionUtil.sanitizeKnownConfigContent(config);

        assertTrue(sanitized.contains("server=<redacted>"));
        assertTrue(sanitized.contains("other=keep-me"));
        assertFalse(sanitized.contains("C:\\Users\\Terac"));
    }

    @Test
    void handlesNullInput() {
        assertNull(RedactionUtil.redactUserPathOnly(null));
        assertNull(RedactionUtil.sanitizeKnownConfigContent(null));
    }
}
