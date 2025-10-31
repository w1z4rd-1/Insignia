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
        
        ghostTotem.addEntry(entryBuilder.startBooleanToggle(Text.literal("Macro Mode"), config.ghostTotemMacroMode)
            .setDefaultValue(false)
            .setTooltip(Text.literal("Automatically send ghost detection messages to chat (check server rules!)"))
            .setSaveConsumer(newValue -> config.ghostTotemMacroMode = newValue)
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
        
        // Damage Flash Category
        ConfigCategory damageFlash = builder.getOrCreateCategory(Text.literal("Damage Flash"));
        
        damageFlash.addEntry(entryBuilder.startBooleanToggle(Text.literal("Enable Full Model Flash"), config.damageFlashEnabled)
            .setDefaultValue(true)
            .setTooltip(Text.literal("Flash entire player model (including armor and items) when damaged"))
            .setSaveConsumer(newValue -> config.damageFlashEnabled = newValue)
            .build());
        
        damageFlash.addEntry(entryBuilder.startAlphaColorField(Text.literal("Flash Color"), config.damageFlashColor)
            .setDefaultValue(0xFFFF0000)
            .setTooltip(Text.literal("Color of the damage flash"))
            .setSaveConsumer(newValue -> config.damageFlashColor = newValue)
            .build());
        
        damageFlash.addEntry(entryBuilder.startIntSlider(Text.literal("Flash Duration (ms)"), config.damageFlashDuration, 100, 1000)
            .setDefaultValue(400)
            .setTooltip(Text.literal("How long the damage flash lasts in milliseconds"))
            .setSaveConsumer(newValue -> config.damageFlashDuration = newValue)
            .build());
        
        // Totem Highlighting Category
        ConfigCategory totemHighlight = builder.getOrCreateCategory(Text.literal("Totem Highlighting"));
        
        totemHighlight.addEntry(entryBuilder.startBooleanToggle(Text.literal("Enable Totem Highlighting"), config.totemHighlightEnabled)
            .setDefaultValue(true)
            .setTooltip(Text.literal("Highlight players based on their totem status"))
            .setSaveConsumer(newValue -> config.totemHighlightEnabled = newValue)
            .build());
        
        totemHighlight.addEntry(entryBuilder.startBooleanToggle(Text.literal("Enable No-Totem Highlighting"), config.noTotemHighlightEnabled)
            .setDefaultValue(true)
            .setTooltip(Text.literal("Highlight players when they run out of totems"))
            .setSaveConsumer(newValue -> config.noTotemHighlightEnabled = newValue)
            .build());
        
        totemHighlight.addEntry(entryBuilder.startAlphaColorField(Text.literal("Has Totem Color"), config.hasTotemColor)
            .setDefaultValue(0xFF00FF00)
            .setTooltip(Text.literal("Color when player is holding totems"))
            .setSaveConsumer(newValue -> config.hasTotemColor = newValue)
            .build());
        
        totemHighlight.addEntry(entryBuilder.startAlphaColorField(Text.literal("No Totem Color"), config.noTotemColor)
            .setDefaultValue(0xFFFF0000)
            .setTooltip(Text.literal("Color when player runs out of totems"))
            .setSaveConsumer(newValue -> config.noTotemColor = newValue)
            .build());
        
        totemHighlight.addEntry(entryBuilder.startIntSlider(Text.literal("No-Totem Fade Duration (ms)"), config.noTotemFadeDuration, 1000, 10000)
            .setDefaultValue(5000)
            .setTooltip(Text.literal("How long the no-totem highlight lasts before fading"))
            .setSaveConsumer(newValue -> config.noTotemFadeDuration = newValue)
            .build());
        
        totemHighlight.addEntry(entryBuilder.startEnumSelector(Text.literal("Color Priority"), InsigniaConfig.ColorPriority.class, config.colorPriority)
            .setDefaultValue(InsigniaConfig.ColorPriority.DAMAGE_OVERRIDES)
            .setTooltip(Text.literal("How colors interact when both damage and no-totem are active"))
            .setEnumNameProvider(priority -> Text.literal(((InsigniaConfig.ColorPriority)priority).getDisplayName()))
            .setSaveConsumer(newValue -> config.colorPriority = newValue)
            .build());
        
        totemHighlight.addEntry(entryBuilder.startAlphaColorField(Text.literal("Damage + No-Totem Color"), config.damageNoTotemColor)
            .setDefaultValue(0xFFFF00FF)
            .setTooltip(Text.literal("Color when player is damaged AND has no totems (if using Combined Color mode)"))
            .setSaveConsumer(newValue -> config.damageNoTotemColor = newValue)
            .build());
        
        return builder.build();
    }
}

