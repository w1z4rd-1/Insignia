package net.infiniteimperm.fabric.tagger;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.MathHelper;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Random;

public class KitEffectRenderer {
    
    private static final ConcurrentHashMap<String, EffectData> activeEffects = new ConcurrentHashMap<>();
    private static final Random random = new Random();
    
    /**
     * Strike a player with lightning for loading a kit
     */
    public static void addEffect(PlayerEntity player) {
        String playerName = player.getName().getString();
        TaggerMod.LOGGER.info("[KitEffectRenderer] Striking {} with lightning for loading a kit!", playerName);
        
        // Strike the player with 4 lightning bolts at the same time
        strikeMultipleLightning(player);
        
        // Add effect data for tracking
        EffectData effect = new EffectData(player, System.currentTimeMillis());
        activeEffects.put(playerName, effect);
    }
    
    /**
     * Strike a player with 4 lightning bolts at the same time
     */
    private static void strikeMultipleLightning(PlayerEntity player) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) {
            return;
        }
        
        Vec3d playerPos = player.getPos();
        
        // Play one thunder sound at the player's position
        client.world.playSound(client.player, playerPos.x, playerPos.y, playerPos.z, 
            SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, 1.0f, 1.0f);
        client.world.playSound(client.player, playerPos.x, playerPos.y, playerPos.z, 
            SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.WEATHER, 0.8f, 1.2f);
        
        // Strike with exactly 4 lightning bolts at the same time, all on the player
        for (int i = 0; i < 4; i++) {
            // Create lightning directly on the player
            Vec3d lightningPos = new Vec3d(
                playerPos.x,
                playerPos.y,
                playerPos.z
            );
            
            // Create a lightning bolt entity
            LightningEntity lightning = new LightningEntity(EntityType.LIGHTNING_BOLT, client.world);
            lightning.refreshPositionAfterTeleport(lightningPos.x, lightningPos.y, lightningPos.z);
            
            // Add the lightning to the world
            client.world.addEntity(lightning);
            
            TaggerMod.LOGGER.info("[KitEffectRenderer] Lightning strike {} spawned on player at {}, {}, {}", 
                i + 1, lightningPos.x, lightningPos.y, lightningPos.z);
        }
    }
    
    /**
     * Update effects (minimal - just cleanup)
     */
    public static void tick() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        
        // Clean up expired effects (after 1 second)
        activeEffects.entrySet().removeIf(entry -> {
            EffectData effect = entry.getValue();
            PlayerEntity player = effect.player;
            
            // Effect lasts 1 second
            if (currentTime - effect.startTime > 1000) {
                return true; // Remove expired effect
            }
            
            // Check if player is still valid and alive
            if (player == null || !player.isAlive()) {
                return true; // Remove invalid effect
            }
            
            return false; // Keep the effect
        });
    }
    
    /**
     * Data class to hold effect information
     */
    private static class EffectData {
        final PlayerEntity player;
        final long startTime;
        
        EffectData(PlayerEntity player, long startTime) {
            this.player = player;
            this.startTime = startTime;
        }
    }
}
