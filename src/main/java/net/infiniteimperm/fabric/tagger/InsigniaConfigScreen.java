package net.infiniteimperm.fabric.tagger;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public class InsigniaConfigScreen {
    
    public static Screen createConfigScreen(Screen parent) {
        InsigniaConfig config = InsigniaConfig.getInstance();
        
        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Text.literal("Insignia Configuration"))
            .setSavingRunnable(InsigniaConfig::save);
        
        ConfigEntryBuilder entryBuilder = builder.entryBuilder();
        
        // Ghost Totem Detection Category
        ConfigCategory ghostTotem = builder.getOrCreateCategory(Text.literal("Ghost Totem Detection"));
        
        ghostTotem.addEntry(entryBuilder.startBooleanToggle(Text.literal("Enable Ghost Totem Detection"), config.ghostTotemEnabled)
            .setDefaultValue(true)
            .setTooltip(Text.literal("Detects and announces ghost totem events"))
            .setSaveConsumer(newValue -> config.ghostTotemEnabled = newValue)
            .build());
        
        ghostTotem.addEntry(entryBuilder.startBooleanToggle(Text.literal("Clipboard Mode"), config.ghostTotemClipboardMode)
            .setDefaultValue(true)
            .setTooltip(Text.literal("Copy ghost detection messages to clipboard"))
            .setSaveConsumer(newValue -> config.ghostTotemClipboardMode = newValue)
            .build());
        
        // Kit Detection Category
        ConfigCategory kitDetector = builder.getOrCreateCategory(Text.literal("Kit Detector"));
        
        kitDetector.addEntry(entryBuilder.startBooleanToggle(Text.literal("Enable Kit Detection"), config.kitDetectorEnabled)
            .setDefaultValue(true)
            .setTooltip(Text.literal("Detect when players load kits"))
            .setSaveConsumer(newValue -> config.kitDetectorEnabled = newValue)
            .build());
        
        kitDetector.addEntry(entryBuilder.startBooleanToggle(Text.literal("Lightning Effect"), config.kitLightningEnabled)
            .setDefaultValue(true)
            .setTooltip(Text.literal("Strike players with lightning when they load a kit"))
            .setSaveConsumer(newValue -> config.kitLightningEnabled = newValue)
            .build());
        
        // Queue Durability Category
        ConfigCategory queueDura = builder.getOrCreateCategory(Text.literal("Queue Durability Check"));
        
        queueDura.addEntry(entryBuilder.startBooleanToggle(Text.literal("Enable Durability Check"), config.queueDurabilityEnabled)
            .setDefaultValue(true)
            .setTooltip(Text.literal("Warn before queueing with damaged armor"))
            .setSaveConsumer(newValue -> config.queueDurabilityEnabled = newValue)
            .build());
        
        queueDura.addEntry(entryBuilder.startIntSlider(Text.literal("Durability Threshold %"), (int)(config.queueDurabilityThreshold * 100), 50, 100)
            .setDefaultValue(95)
            .setTooltip(Text.literal("Minimum durability % required to queue without warning"))
            .setSaveConsumer(newValue -> config.queueDurabilityThreshold = newValue / 100.0f)
            .build());
        
        // Totem Warning Overlay Category
        ConfigCategory totemWarning = builder.getOrCreateCategory(Text.literal("Totem Warning Overlay"));
        
        totemWarning.addEntry(entryBuilder.startBooleanToggle(Text.literal("Enable Totem Warning"), config.totemWarningEnabled)
            .setDefaultValue(true)
            .setTooltip(Text.literal("Show red overlay when you have totems but aren't holding one"))
            .setSaveConsumer(newValue -> config.totemWarningEnabled = newValue)
            .build());
        
        totemWarning.addEntry(entryBuilder.startAlphaColorField(Text.literal("Warning Color"), config.totemWarningColor)
            .setDefaultValue(0x40FF0000)
            .setTooltip(Text.literal("Color of the totem warning overlay"))
            .setSaveConsumer(newValue -> config.totemWarningColor = newValue)
            .build());

        // Profiler Category
        ConfigCategory profiler = builder.getOrCreateCategory(Text.literal("Profiler"));

        profiler.addEntry(entryBuilder.startBooleanToggle(Text.literal("Custom: PresentMon"), config.diagnoseCustomPresentMon)
            .setDefaultValue(true)
            .setTooltip(Text.literal("Enable PresentMon capture for /diagnose custom"))
            .setSaveConsumer(newValue -> config.diagnoseCustomPresentMon = newValue)
            .build());

        profiler.addEntry(entryBuilder.startBooleanToggle(Text.literal("Custom: JFR"), config.diagnoseCustomJfr)
            .setDefaultValue(true)
            .setTooltip(Text.literal("Enable Java Flight Recorder for /diagnose custom"))
            .setSaveConsumer(newValue -> config.diagnoseCustomJfr = newValue)
            .build());

        profiler.addEntry(entryBuilder.startBooleanToggle(Text.literal("Custom: Perf Counters"), config.diagnoseCustomPerfCounters)
            .setDefaultValue(true)
            .setTooltip(Text.literal("Enable internal JVM/OS counters sampler for /diagnose custom"))
            .setSaveConsumer(newValue -> config.diagnoseCustomPerfCounters = newValue)
            .build());

        profiler.addEntry(entryBuilder.startBooleanToggle(Text.literal("Custom: typeperf"), config.diagnoseCustomTypeperf)
            .setDefaultValue(true)
            .setTooltip(Text.literal("Enable Windows hardware counters (typeperf) for /diagnose custom"))
            .setSaveConsumer(newValue -> config.diagnoseCustomTypeperf = newValue)
            .build());

        profiler.addEntry(entryBuilder.startBooleanToggle(Text.literal("Custom: Process Contention"), config.diagnoseCustomProcessContention)
            .setDefaultValue(true)
            .setTooltip(Text.literal("Enable process contention sampler for /diagnose custom"))
            .setSaveConsumer(newValue -> config.diagnoseCustomProcessContention = newValue)
            .build());

        profiler.addEntry(entryBuilder.startBooleanToggle(Text.literal("Custom: nvidia-smi"), config.diagnoseCustomNvidiaSmi)
            .setDefaultValue(true)
            .setTooltip(Text.literal("Enable NVIDIA telemetry for /diagnose custom"))
            .setSaveConsumer(newValue -> config.diagnoseCustomNvidiaSmi = newValue)
            .build());

        profiler.addEntry(entryBuilder.startBooleanToggle(Text.literal("Custom: spark"), config.diagnoseCustomSpark)
            .setDefaultValue(true)
            .setTooltip(Text.literal("Enable spark profiler dispatch for /diagnose custom"))
            .setSaveConsumer(newValue -> config.diagnoseCustomSpark = newValue)
            .build());

        profiler.addEntry(entryBuilder.startBooleanToggle(Text.literal("Custom: Nsight Systems"), config.diagnoseCustomNsight)
            .setDefaultValue(false)
            .setTooltip(Text.literal("Enable Nsight capture for /diagnose custom"))
            .setSaveConsumer(newValue -> config.diagnoseCustomNsight = newValue)
            .build());

        profiler.addEntry(entryBuilder.startBooleanToggle(Text.literal("Custom: WPR"), config.diagnoseCustomWpr)
            .setDefaultValue(false)
            .setTooltip(Text.literal("Enable WPR trace capture for /diagnose custom"))
            .setSaveConsumer(newValue -> config.diagnoseCustomWpr = newValue)
            .build());
        
        return builder.build();
    }
}

