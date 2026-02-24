package net.infiniteimperm.fabric.tagger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class InsigniaConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("insignia.json").toFile();
    
    private static InsigniaConfig INSTANCE = new InsigniaConfig();
    
    // Ghost Totem Detection Settings
    public boolean ghostTotemEnabled = true;
    public boolean ghostTotemClipboardMode = true;
    
    // Kit Detection Settings
    public boolean kitDetectorEnabled = true;
    public boolean kitLightningEnabled = true;
    
    // Queue Durability Checker Settings
    public boolean queueDurabilityEnabled = true;
    public float queueDurabilityThreshold = 0.95f; // 95%
    
    // Totem Warning Overlay Settings
    public boolean totemWarningEnabled = true;
    public int totemWarningColor = 0x40FF0000; // Semi-transparent red

    // Diagnose Custom Mode Profiler Toggles
    public boolean diagnoseCustomPresentMon = true;
    public boolean diagnoseCustomJfr = true;
    public boolean diagnoseCustomPerfCounters = true;
    public boolean diagnoseCustomTypeperf = true;
    public boolean diagnoseCustomProcessContention = true;
    public boolean diagnoseCustomNvidiaSmi = true;
    public boolean diagnoseCustomSpark = true;
    public boolean diagnoseCustomNsight = false;
    public boolean diagnoseCustomWpr = false;
    
    public static InsigniaConfig getInstance() {
        return INSTANCE;
    }
    
    public static void load() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                InsigniaConfig loaded = GSON.fromJson(reader, InsigniaConfig.class);
                if (loaded == null) {
                    INSTANCE = new InsigniaConfig();
                } else {
                    INSTANCE = loaded;
                    // Clamp values to valid ranges
                    INSTANCE.queueDurabilityThreshold = Math.max(0.5f, Math.min(1.0f, INSTANCE.queueDurabilityThreshold));
                }
                TaggerMod.LOGGER.info("Loaded Insignia config from file");
            } catch (Exception e) {
                TaggerMod.LOGGER.error("Failed to load config file, using defaults", e);
                INSTANCE = new InsigniaConfig();
            }
        } else {
            INSTANCE = new InsigniaConfig();
            save();
        }
    }
    
    public static void save() {
        try {
            CONFIG_FILE.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(INSTANCE, writer);
                TaggerMod.LOGGER.info("[Insignia] Saved Insignia config to file");
            }
        } catch (IOException e) {
            TaggerMod.LOGGER.error("Failed to save config file", e);
        }
    }
}

