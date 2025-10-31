package net.infiniteimperm.fabric.tagger;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class QueueDurabilityChecker {
    
    // MIN_DURABILITY_PERCENT is now read from config
    private static boolean awaitingConfirmation = false;
    private static String pendingCommand = null;
    private static long lastWarningTime = 0;
    
    /**
     * Check if a command is a queue command
     */
    public static boolean isQueueCommand(String command) {
        String lowerCommand = command.toLowerCase().trim();
        return lowerCommand.equals("q") || 
               lowerCommand.equals("rtpqueue") || 
               lowerCommand.equals("queue");
    }
    
    /**
     * Process a queue command, checking armor durability first
     * @param command The command being sent
     * @return true if command should be blocked, false if it should proceed
     */
    public static boolean processQueueCommand(String command) {
        // Check if feature is enabled
        if (!InsigniaConfig.getInstance().queueDurabilityEnabled) {
            return false; // Let command proceed if feature is disabled
        }
        
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return false; // Let command proceed if no player
        }
        
        // If we're awaiting confirmation and this is the same command, let it through
        if (awaitingConfirmation && command.equals(pendingCommand)) {
            TaggerMod.LOGGER.info("[QueueDurabilityChecker] Confirmation received, allowing queue command: {}", command);
            awaitingConfirmation = false;
            pendingCommand = null;
            
            // Show confirmation message
            client.player.sendMessage(Text.literal("§aConfirmed! Queueing with damaged gear...").formatted(Formatting.GREEN), false);
            return false; // Allow command to proceed
        }
        
        // Check armor durability
        ArmorDurabilityResult result = checkArmorDurability(client.player);
        
        if (result.allArmorFresh) {
            // Armor is fresh, let the command through
            TaggerMod.LOGGER.info("[QueueDurabilityChecker] Armor is fresh ({}% avg), allowing queue command", 
                Math.round(result.averageDurabilityPercent * 100));
            awaitingConfirmation = false;
            pendingCommand = null;
            return false; // Allow command to proceed
        } else {
            // Armor is damaged, block the command and warn the user
            TaggerMod.LOGGER.info("[QueueDurabilityChecker] Damaged armor detected ({}% avg), blocking queue command", 
                Math.round(result.averageDurabilityPercent * 100));
            
            // Set up confirmation state
            awaitingConfirmation = true;
            pendingCommand = command;
            lastWarningTime = System.currentTimeMillis();
            
            // Send warning to player
            sendDurabilityWarning(client.player, result);
            
            return true; // Block the command
        }
    }
    
    /**
     * Check the durability of all armor pieces
     */
    private static ArmorDurabilityResult checkArmorDurability(PlayerEntity player) {
        ItemStack[] armorSlots = {
            player.getInventory().getArmorStack(0), // Boots
            player.getInventory().getArmorStack(1), // Leggings  
            player.getInventory().getArmorStack(2), // Chestplate
            player.getInventory().getArmorStack(3)  // Helmet
        };
        
        boolean allArmorFresh = true;
        float totalDurabilityPercent = 0;
        int armorPieces = 0;
        StringBuilder details = new StringBuilder();
        
        String[] slotNames = {"Boots", "Leggings", "Chestplate", "Helmet"};
        
        for (int i = 0; i < armorSlots.length; i++) {
            ItemStack armor = armorSlots[i];
            
            if (!armor.isEmpty() && armor.isDamageable()) {
                armorPieces++;
                int maxDamage = armor.getMaxDamage();
                int currentDamage = armor.getDamage();
                int remainingDurability = maxDamage - currentDamage;
                float durabilityPercent = (float) remainingDurability / maxDamage;
                
                totalDurabilityPercent += durabilityPercent;
                
                float minDurability = InsigniaConfig.getInstance().queueDurabilityThreshold;
                if (durabilityPercent < minDurability) {
                    allArmorFresh = false;
                }
                
                details.append(String.format("%s: %d%% ", 
                    slotNames[i], Math.round(durabilityPercent * 100)));
                
                TaggerMod.LOGGER.info("[QueueDurabilityChecker] {}: {}% durability ({}/{})", 
                    slotNames[i], Math.round(durabilityPercent * 100), remainingDurability, maxDamage);
            }
        }
        
        float averageDurabilityPercent = armorPieces > 0 ? totalDurabilityPercent / armorPieces : 1.0f;
        
        return new ArmorDurabilityResult(allArmorFresh, averageDurabilityPercent, details.toString().trim());
    }
    
    /**
     * Send durability warning to the player
     */
    private static void sendDurabilityWarning(PlayerEntity player, ArmorDurabilityResult result) {
        player.sendMessage(Text.literal("§cREKIT FIRST OR /q again to confirm").formatted(Formatting.RED, Formatting.BOLD), false);
    }
    
    /**
     * Reset confirmation state (called when player does something else)
     */
    public static void resetConfirmation() {
        if (awaitingConfirmation && (System.currentTimeMillis() - lastWarningTime) > 30000) { // 30 seconds timeout
            awaitingConfirmation = false;
            pendingCommand = null;
            TaggerMod.LOGGER.info("[QueueDurabilityChecker] Confirmation timeout, reset state");
        }
    }
    
    /**
     * Data class to hold armor durability check results
     */
    private static class ArmorDurabilityResult {
        final boolean allArmorFresh;
        final float averageDurabilityPercent;
        final String durabilityDetails;
        
        ArmorDurabilityResult(boolean allArmorFresh, float averageDurabilityPercent, String durabilityDetails) {
            this.allArmorFresh = allArmorFresh;
            this.averageDurabilityPercent = averageDurabilityPercent;
            this.durabilityDetails = durabilityDetails;
        }
    }
}
