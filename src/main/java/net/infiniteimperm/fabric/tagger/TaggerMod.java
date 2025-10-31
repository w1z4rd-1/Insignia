package net.infiniteimperm.fabric.tagger;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class TaggerMod implements ClientModInitializer {
    public static final String MOD_ID = "insignia";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    public static final boolean DEBUG_MODE = false; // Debug logging enabled

    @Override
    public void onInitializeClient() {
        LOGGER.info("Insignia Mod initialized");
        
        // Load configuration
        InsigniaConfig.load();
        
        // Sync Ghost Totem Detector state with config
        GhostTotemDetector.syncWithConfig();

        // Register the tick event for various trackers
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            QueueDurabilityChecker.resetConfirmation(); // Reset confirmation timeout
            GhostTotemDetector.tick(client);
            KitDetector.tick(); // Add KitDetector tick
            PlayerHighlightTracker.tick(client); // Track player highlight states
        });
        
        // Register event for processing chat messages
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            // Convert message to string and process through detectors
            String messageStr = message.getString();
            
            KitDetector.onChatMessage(messageStr); // Add kit detection
            GhostTotemDetector.onChatMessage(messageStr); // Detect player death via chat
        });

        // Register HUD render callback for Totem Warning Overlay (in-game view)
        HudRenderCallback.EVENT.register((drawContext, renderTickCounter) -> {
            // Check condition and render directly here, avoid calling overlay class method
            if (TotemWarningOverlay.shouldShowWarning()) {
                int screenWidth = MinecraftClient.getInstance().getWindow().getScaledWidth();
                int screenHeight = MinecraftClient.getInstance().getWindow().getScaledHeight();
                drawContext.fill(0, 0, screenWidth, screenHeight, 0x40FF0000); // Draw overlay directly
            }
        });
        
        // Register the /gd commands
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // Register /insignia command to open config GUI
            dispatcher.register(ClientCommandManager.literal("insignia")
                .executes(context -> {
                    MinecraftClient client = context.getSource().getClient();
                    client.send(() -> client.setScreen(InsigniaConfigScreen.createConfigScreen(client.currentScreen)));
                    return 1;
                })
            );
            
            dispatcher.register(ClientCommandManager.literal("gd")
                .then(ClientCommandManager.literal("macro")
                    .executes(context -> {
                        GhostTotemDetector.toggleMacroMode();
                        return 1;
                    })
                )
                .then(ClientCommandManager.literal("clipboard")
                    .executes(context -> {
                        GhostTotemDetector.toggleClipboardMode();
                        return 1;
                    })
                )
                .executes(context -> {
                    context.getSource().sendFeedback(Text.literal(
                        "§6Ghost Detector Commands:\n" +
                        "§e/gd macro §7- Toggle chat macro mode (check your server's rules!)\n" +
                        "§e/gd clipboard §7- Toggle clipboard mode (copy to clipboard)"
                    ));
                    return 1;
                })
            );
        });
    }
}
