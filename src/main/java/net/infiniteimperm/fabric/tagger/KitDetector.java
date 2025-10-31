package net.infiniteimperm.fabric.tagger;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KitDetector {
    
    // Multiple patterns to match different possible kit loading message formats
    private static final Pattern[] KIT_LOAD_PATTERNS = {
        Pattern.compile("ðŸ—¡ ([a-zA-Z0-9_]+) loaded a kit"),          // Original pattern
        Pattern.compile("🗡 ([a-zA-Z0-9_]+) loaded a kit"),           // Unicode sword
        Pattern.compile("⚔ ([a-zA-Z0-9_]+) loaded a kit"),            // Crossed swords
        Pattern.compile(".*([a-zA-Z0-9_]+) loaded a kit.*"),          // Any sword character
        Pattern.compile("([a-zA-Z0-9_]+) loaded a kit"),              // No sword at all
    };
    private static final double RENDER_DISTANCE = 16.0; // Same as ghost detector
    
    /**
     * Process a chat message to detect kit loading
     */
    public static void onChatMessage(String message) {
        // Debug: Log all chat messages to see what we're receiving
        if (TaggerMod.DEBUG_MODE) {
            TaggerMod.LOGGER.info("[KitDetector] Received chat message: '{}'", message);
        }
        
        for (Pattern pattern : KIT_LOAD_PATTERNS) {
            Matcher kitMatcher = pattern.matcher(message);
            if (kitMatcher.find()) {
                String playerName = kitMatcher.group(1);
                
                TaggerMod.LOGGER.info("[KitDetector] Detected kit load by player: {}", playerName);
                
                // Check if the player is in render distance
                checkPlayerInRangeAndShowEffect(playerName);
                return; // Found a match, no need to try other patterns
            }
        }
        
        // Debug: Log when messages don't match any pattern
        if (TaggerMod.DEBUG_MODE && message.contains("kit")) {
            TaggerMod.LOGGER.info("[KitDetector] Message contains 'kit' but doesn't match any pattern: '{}'", message);
        }
    }
    
    /**
     * Check if the player who loaded a kit is in render distance and show effect
     */
    private static void checkPlayerInRangeAndShowEffect(String playerName) {
        MinecraftClient client = MinecraftClient.getInstance();
        
        if (client.player == null || client.world == null) {
            return;
        }
        
        ClientPlayerEntity localPlayer = client.player;
        World world = client.world;
        Vec3d localPlayerPos = localPlayer.getPos();
        
        // Create bounding box for render distance check
        Box renderArea = new Box(localPlayerPos.subtract(RENDER_DISTANCE, RENDER_DISTANCE, RENDER_DISTANCE),
                               localPlayerPos.add(RENDER_DISTANCE, RENDER_DISTANCE, RENDER_DISTANCE));
        
        // Get nearby players excluding ourselves
        List<PlayerEntity> nearbyPlayers = world.getEntitiesByClass(PlayerEntity.class, renderArea, entity -> 
            entity != localPlayer && entity.getName().getString().equals(playerName)
        );
        
        if (!nearbyPlayers.isEmpty()) {
            // Player is in render distance - show the effect!
            PlayerEntity targetPlayer = nearbyPlayers.get(0);
            TaggerMod.LOGGER.info("[KitDetector] Player {} is in render distance! Starting visual effect.", playerName);
            
            // Start the fancy visual effect using the renderer
            KitEffectRenderer.addEffect(targetPlayer);
        } else {
            TaggerMod.LOGGER.info("[KitDetector] Player {} not in render distance, ignoring.", playerName);
        }
    }
    
    /**
     * Called every tick to update visual effects
     */
    public static void tick() {
        // Update the visual effects renderer
        KitEffectRenderer.tick();
    }
}
