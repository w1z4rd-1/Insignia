package net.infiniteimperm.fabric.tagger;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.infiniteimperm.fabric.tagger.diagnose.DiagnoseOrchestrator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class TaggerMod implements ClientModInitializer {
    public static final String MOD_ID = "insignia";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    public static final boolean DEBUG_MODE = false; // Debug logging enabled
    private KeyBinding userStutterMarkKey;
    private String lastWorldKey = "";
    private boolean hadWorld = false;
    private long lastWorldTransitionNs = 0L;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Insignia Mod initialized");
        
        // Load configuration
        InsigniaConfig.load();
        
        // Sync Ghost Totem Detector state with config
        GhostTotemDetector.syncWithConfig();

        userStutterMarkKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.insignia.user_stutter_mark",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            "category.insignia"
        ));

        // Register the tick event for various trackers
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long postStartNs = System.nanoTime();
            QueueDurabilityChecker.resetConfirmation(); // Reset confirmation timeout
            GhostTotemDetector.tick(client);
            KitDetector.tick(); // Add KitDetector tick
            DiagnoseOrchestrator.onPostPhaseDuration(System.nanoTime() - postStartNs);

            while (userStutterMarkKey.wasPressed()) {
                DiagnoseOrchestrator.onUserStutterMark("F8");
                LOGGER.info("[Diagnose][JFR] User stutter mark emitted via hotkey F8");
            }

            trackWorldTransitions(client);
        });
        
        // Register event for processing chat messages
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            // Convert message to string and process through detectors
            String messageStr = message.getString();
            
            KitDetector.onChatMessage(messageStr); // Add kit detection
            GhostTotemDetector.onChatMessage(messageStr); // Detect player death via chat
            DiagnoseOrchestrator.getInstance().onIncomingGameMessage(messageStr);
        });

        WorldRenderEvents.START.register(context -> DiagnoseOrchestrator.onWorldFrameStart());
        WorldRenderEvents.END.register(context -> DiagnoseOrchestrator.onWorldFrameEnd());

        // Register HUD render callback for Totem Warning Overlay (in-game view)
        HudRenderCallback.EVENT.register((drawContext, renderTickCounter) -> {
            DiagnoseOrchestrator.onHudFrameStart();
            // Check condition and render directly here, avoid calling overlay class method
            if (TotemWarningOverlay.shouldShowWarning()) {
                int screenWidth = MinecraftClient.getInstance().getWindow().getScaledWidth();
                int screenHeight = MinecraftClient.getInstance().getWindow().getScaledHeight();
                drawContext.fill(0, 0, screenWidth, screenHeight, 0x40FF0000); // Draw overlay directly
            }
            DiagnoseOrchestrator.onHudFrameBoundary();
            DiagnoseOrchestrator.onHudFrameEnd();
        });
        
        // Register client commands
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // Register /insignia command to open config GUI
            dispatcher.register(ClientCommandManager.literal("insignia")
                .executes(context -> {
                    MinecraftClient client = context.getSource().getClient();
                    client.send(() -> client.setScreen(InsigniaConfigScreen.createConfigScreen(client.currentScreen)));
                    return 1;
                })
            );

            DiagnoseCommand.register(dispatcher);
        });
    }

    private void trackWorldTransitions(MinecraftClient client) {
        String currentKey = client.world != null ? client.world.getRegistryKey().getValue().toString() : "";
        long nowNs = System.nanoTime();
        long durationNs = lastWorldTransitionNs > 0L ? Math.max(0L, nowNs - lastWorldTransitionNs) : 0L;

        if (!hadWorld && client.world != null) {
            DiagnoseOrchestrator.onWorldTransitionJoin(durationNs);
            LOGGER.info("[Diagnose][JFR] World transition emitted: JOIN durationNs={}", durationNs);
            lastWorldTransitionNs = nowNs;
        } else if (hadWorld && client.world == null) {
            DiagnoseOrchestrator.onWorldTransitionLeave(durationNs);
            LOGGER.info("[Diagnose][JFR] World transition emitted: LEAVE durationNs={}", durationNs);
            lastWorldTransitionNs = nowNs;
        } else if (hadWorld && client.world != null && !currentKey.equals(lastWorldKey)) {
            DiagnoseOrchestrator.onWorldTransitionDimChange(durationNs);
            LOGGER.info("[Diagnose][JFR] World transition emitted: DIM_CHANGE {} -> {} durationNs={}", lastWorldKey, currentKey, durationNs);
            lastWorldTransitionNs = nowNs;
        }

        hadWorld = client.world != null;
        lastWorldKey = currentKey;
    }
}
