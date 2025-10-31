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
    public boolean ghostTotemMacroMode = false;
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
    
    // Damage Flash Settings
    public boolean damageFlashEnabled = true;
    public int damageFlashColor = 0xFFFF0000; // Bright red
    public int damageFlashDuration = 400; // milliseconds
    
    // Totem Status Highlighting Settings
    public boolean totemHighlightEnabled = true;
    public boolean noTotemHighlightEnabled = true;
    public int hasTotemColor = 0xFF00FF00; // Green
    public int noTotemColor = 0xFFFF0000; // Red
    public int noTotemFadeDuration = 5000; // 5 seconds
    
    // Color Override Settings
    public ColorPriority colorPriority = ColorPriority.DAMAGE_OVERRIDES;
    public int damageNoTotemColor = 0xFFFF00FF; // Magenta
    
    public enum ColorPriority {
        DAMAGE_OVERRIDES("Damage Overrides No-Totem"),
        NO_TOTEM_OVERRIDES("No-Totem Overrides Damage"),
        COMBINED_COLOR("Use Combined Color");
        
        private final String displayName;
        
        ColorPriority(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
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
                    // Validate loaded values and apply defaults if null
                    if (INSTANCE.colorPriority == null) {
                        INSTANCE.colorPriority = ColorPriority.DAMAGE_OVERRIDES;
                    }
                    // Clamp values to valid ranges
                    INSTANCE.queueDurabilityThreshold = Math.max(0.5f, Math.min(1.0f, INSTANCE.queueDurabilityThreshold));
                    INSTANCE.damageFlashDuration = Math.max(100, Math.min(2000, INSTANCE.damageFlashDuration));
                    INSTANCE.noTotemFadeDuration = Math.max(1000, Math.min(30000, INSTANCE.noTotemFadeDuration));
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
                TaggerMod.LOGGER.info("Saved Insignia config to file");
            }
        } catch (IOException e) {
            TaggerMod.LOGGER.error("Failed to save config file", e);
        }
    }
}

