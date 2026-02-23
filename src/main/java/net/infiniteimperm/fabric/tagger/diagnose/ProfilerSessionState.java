package net.infiniteimperm.fabric.tagger.diagnose;

import net.infiniteimperm.fabric.tagger.TaggerMod;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;

public final class ProfilerSessionState {
    private ProfilerSessionState() {
    }

    public static Optional<State> load(Path stateFile) {
        if (stateFile == null || !Files.exists(stateFile)) {
            return Optional.empty();
        }
        try {
            String raw = Files.readString(stateFile, StandardCharsets.UTF_8);
            Properties props = new Properties();
            props.load(new StringReader(raw));
            State state = new State();
            state.runDir = props.getProperty("runDir", "");
            state.nsysSession = props.getProperty("nsysSession", "");
            state.nsysStartedByUs = Boolean.parseBoolean(props.getProperty("nsysStartedByUs", "false"));
            state.wprStartedByUs = Boolean.parseBoolean(props.getProperty("wprStartedByUs", "false"));
            state.wprStartedElevated = Boolean.parseBoolean(props.getProperty("wprStartedElevated", "false"));
            state.createdAt = props.getProperty("createdAt", "");
            state.ownerPid = parseLong(props.getProperty("ownerPid", "-1"));
            return Optional.of(state);
        } catch (Exception e) {
            TaggerMod.LOGGER.warn("[Diagnose][State] Failed to read state file {}", stateFile, e);
            return Optional.empty();
        }
    }

    public static void save(Path stateFile, State state) {
        if (stateFile == null || state == null) {
            return;
        }
        try {
            Files.createDirectories(stateFile.getParent());
            Properties props = new Properties();
            props.setProperty("runDir", nullSafe(state.runDir));
            props.setProperty("nsysSession", nullSafe(state.nsysSession));
            props.setProperty("nsysStartedByUs", Boolean.toString(state.nsysStartedByUs));
            props.setProperty("wprStartedByUs", Boolean.toString(state.wprStartedByUs));
            props.setProperty("wprStartedElevated", Boolean.toString(state.wprStartedElevated));
            props.setProperty("createdAt", nullSafe(state.createdAt.isBlank() ? Instant.now().toString() : state.createdAt));
            props.setProperty("ownerPid", Long.toString(state.ownerPid));
            StringWriter writer = new StringWriter();
            props.store(writer, "insignia diagnose state");
            Files.writeString(stateFile, writer.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            TaggerMod.LOGGER.warn("[Diagnose][State] Failed to write state file {}", stateFile, e);
        }
    }

    public static void clear(Path stateFile) {
        if (stateFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(stateFile);
        } catch (IOException e) {
            TaggerMod.LOGGER.warn("[Diagnose][State] Failed to clear state file {}", stateFile, e);
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return -1L;
        }
    }

    public static final class State {
        public String runDir = "";
        public String nsysSession = "";
        public boolean nsysStartedByUs;
        public boolean wprStartedByUs;
        public boolean wprStartedElevated;
        public String createdAt = "";
        public long ownerPid = -1L;
    }
}
