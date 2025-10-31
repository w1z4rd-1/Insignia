package net.infiniteimperm.fabric.tagger;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerHighlightTracker {
    
    // Track damage flash for each player
    private static final Map<UUID, DamageFlashData> damageFlashes = new HashMap<>();
    
    // Track totem status for each player
    private static final Map<UUID, TotemStatusData> totemStatus = new HashMap<>();
    
    public static class DamageFlashData {
        public long damageTime;
        public float lastHealth;
        
        public DamageFlashData(long damageTime, float lastHealth) {
            this.damageTime = damageTime;
            this.lastHealth = lastHealth;
        }
        
        public boolean isActive() {
            InsigniaConfig config = InsigniaConfig.getInstance();
            return System.currentTimeMillis() - damageTime < config.damageFlashDuration;
        }
        
        public float getAlpha() {
            InsigniaConfig config = InsigniaConfig.getInstance();
            long elapsed = System.currentTimeMillis() - damageTime;
            if (elapsed >= config.damageFlashDuration) return 0f;
            return 1f - (elapsed / (float) config.damageFlashDuration);
        }
    }
    
    public static class TotemStatusData {
        public boolean hasTotem;
        public boolean hadTotemLastTick;
        public long noTotemStartTime;
        
        public TotemStatusData() {
            this.hasTotem = false;
            this.hadTotemLastTick = false;
            this.noTotemStartTime = -1;
        }
        
        public void update(boolean currentlyHasTotem) {
            hadTotemLastTick = hasTotem;
            hasTotem = currentlyHasTotem;
            
            // If we just ran out of totems, start the fade timer
            if (hadTotemLastTick && !hasTotem) {
                noTotemStartTime = System.currentTimeMillis();
            }
            
            // If we got a totem back, clear the no-totem state
            if (hasTotem) {
                noTotemStartTime = -1;
            }
        }
        
        public boolean isNoTotemHighlightActive() {
            if (noTotemStartTime < 0) return false;
            InsigniaConfig config = InsigniaConfig.getInstance();
            return System.currentTimeMillis() - noTotemStartTime < config.noTotemFadeDuration;
        }
        
        public float getNoTotemAlpha() {
            if (noTotemStartTime < 0) return 0f;
            InsigniaConfig config = InsigniaConfig.getInstance();
            long elapsed = System.currentTimeMillis() - noTotemStartTime;
            if (elapsed >= config.noTotemFadeDuration) return 0f;
            return 1f - (elapsed / (float) config.noTotemFadeDuration);
        }
    }
    
    public static void tick(MinecraftClient client) {
        if (client.world == null) {
            // Clear all data if no world
            damageFlashes.clear();
            totemStatus.clear();
            return;
        }
        
        // Track which players are still in the world
        java.util.Set<UUID> currentPlayers = new java.util.HashSet<>();
        
        // Update all players in the world
        for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
            UUID playerId = player.getUuid();
            currentPlayers.add(playerId);
            
            // Track damage
            float currentHealth = player.getHealth();
            DamageFlashData damageData = damageFlashes.get(playerId);
            
            if (damageData != null && damageData.lastHealth > currentHealth && currentHealth > 0) {
                // Player took damage, update the flash
                damageData.damageTime = System.currentTimeMillis();
                damageData.lastHealth = currentHealth;
            } else if (damageData == null) {
                // First time tracking this player - don't flash immediately
                damageFlashes.put(playerId, new DamageFlashData(System.currentTimeMillis() - 10000, currentHealth));
            } else {
                // Update health but don't trigger flash
                damageData.lastHealth = currentHealth;
            }
            
            // Track totem status
            boolean hasTotem = hasTotemInInventory(player);
            TotemStatusData statusData = totemStatus.computeIfAbsent(playerId, k -> new TotemStatusData());
            statusData.update(hasTotem);
        }
        
        // Clean up entries for players who left
        damageFlashes.keySet().retainAll(currentPlayers);
        totemStatus.keySet().retainAll(currentPlayers);
        
        // Clean up old inactive entries
        damageFlashes.entrySet().removeIf(entry -> !entry.getValue().isActive());
        totemStatus.entrySet().removeIf(entry -> 
            !entry.getValue().hasTotem && !entry.getValue().isNoTotemHighlightActive());
    }
    
    private static boolean hasTotemInInventory(PlayerEntity player) {
        // Check main hand and offhand
        if (player.getMainHandStack().getItem() == Items.TOTEM_OF_UNDYING || 
            player.getOffHandStack().getItem() == Items.TOTEM_OF_UNDYING) {
            return true;
        }
        
        // Check inventory
        for (int i = 0; i < player.getInventory().size(); i++) {
            if (player.getInventory().getStack(i).getItem() == Items.TOTEM_OF_UNDYING) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Get the highlight color for a player, combining all active effects
     */
    public static int getHighlightColor(UUID playerId) {
        InsigniaConfig config = InsigniaConfig.getInstance();
        
        boolean hasDamageFlash = false;
        float damageAlpha = 0f;
        DamageFlashData damageData = damageFlashes.get(playerId);
        if (config.damageFlashEnabled && damageData != null && damageData.isActive()) {
            hasDamageFlash = true;
            damageAlpha = damageData.getAlpha();
        }
        
        boolean hasNoTotemHighlight = false;
        boolean hasTotemHighlight = false;
        float noTotemAlpha = 0f;
        TotemStatusData statusData = totemStatus.get(playerId);
        if (statusData != null) {
            if (config.noTotemHighlightEnabled && statusData.isNoTotemHighlightActive()) {
                hasNoTotemHighlight = true;
                noTotemAlpha = statusData.getNoTotemAlpha();
            } else if (config.totemHighlightEnabled && statusData.hasTotem) {
                hasTotemHighlight = true;
            }
        }
        
        // Determine final color based on priority settings
        if (hasDamageFlash && hasNoTotemHighlight) {
            switch (config.colorPriority) {
                case DAMAGE_OVERRIDES:
                    return applyAlpha(config.damageFlashColor, damageAlpha);
                case NO_TOTEM_OVERRIDES:
                    return applyAlpha(config.noTotemColor, noTotemAlpha);
                case COMBINED_COLOR:
                    float combinedAlpha = Math.max(damageAlpha, noTotemAlpha);
                    return applyAlpha(config.damageNoTotemColor, combinedAlpha);
            }
        } else if (hasDamageFlash) {
            return applyAlpha(config.damageFlashColor, damageAlpha);
        } else if (hasNoTotemHighlight) {
            return applyAlpha(config.noTotemColor, noTotemAlpha);
        } else if (hasTotemHighlight) {
            return config.hasTotemColor;
        }
        
        return 0; // No highlight
    }
    
    private static int applyAlpha(int color, float alpha) {
        // Ensure alpha is clamped to valid range
        alpha = Math.max(0f, Math.min(1f, alpha));
        
        // Extract original components
        int originalAlpha = (color >> 24) & 0xFF;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        
        // Apply fade alpha (multiply with original alpha)
        int finalAlpha = (int) (originalAlpha * alpha);
        
        return (finalAlpha << 24) | (r << 16) | (g << 8) | b;
    }
    
    /**
     * Check if a player should be highlighted
     */
    public static boolean shouldHighlight(UUID playerId) {
        return getHighlightColor(playerId) != 0;
    }
}

