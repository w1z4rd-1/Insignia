package net.infiniteimperm.fabric.tagger.diagnose;

import java.util.Locale;
import java.util.regex.Pattern;

public final class RedactionUtil {
    private static final Pattern USER_PATH = Pattern.compile("(?i)C:\\\\Users\\\\[^\\\\\\s]+");

    private RedactionUtil() {
    }

    public static String redactUserPathOnly(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return USER_PATH.matcher(input).replaceAll("C:\\\\Users\\\\<redacted>");
    }

    public static String sanitizeKnownConfigContent(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        StringBuilder out = new StringBuilder(content.length());
        String[] lines = content.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            out.append(sanitizeConfigLine(lines[i]));
            if (i + 1 < lines.length) {
                out.append(System.lineSeparator());
            }
        }
        return out.toString();
    }

    private static String sanitizeConfigLine(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "server", "address", "ip", "uuid", "username")) {
            int eq = line.indexOf('=');
            int colon = line.indexOf(':');
            int split = splitIndex(eq, colon);
            if (split >= 0) {
                return line.substring(0, split + 1) + "<redacted>";
            }
            return "<redacted>";
        }
        return redactUserPathOnly(line);
    }

    private static int splitIndex(int eq, int colon) {
        if (eq < 0) {
            return colon;
        }
        if (colon < 0) {
            return eq;
        }
        return Math.min(eq, colon);
    }

    private static boolean containsAny(String value, String... keys) {
        for (String key : keys) {
            if (value.contains(key)) {
                return true;
            }
        }
        return false;
    }
}
