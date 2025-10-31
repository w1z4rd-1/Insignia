package net.infiniteimperm.fabric.tagger;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;


import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GhostTotemDetector {

    private static long totemEquipTimeNano = 0;
    private static int lastCheckedSlot = -1; // Track the last known selected slot
    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS");
    private static float lastHealth = -1; // Track last known health
    private static boolean wasAlive = true; // Track whether player was alive last tick
    private static GameMode lastGameMode = null; // Track the last game mode
    
    // Variables to track previously held items
    private static boolean wasHoldingTotemLastTick = false;
    private static long gameTickCounter = 0; // Count game ticks for detailed timing info
    private static long totemEquipGameTick = 0; // The game tick when totem was equipped
    
    // For diagnostic purposes - track if we've detected a ghost totem recently
    private static long lastGhostTotemTime = 0;
    private static long ghostTotemHoldTime = 0;
    private static long lastTotemPopTime = 0; // Timestamp of last legitimate totem pop
    private static long lastSelfDeathChatTime = 0; // When "<player> was killed" chat seen
    
    // Track which hand last held the totem ("Mainhand", "Offhand", or "Unknown")
    private static String lastTotemHand = "Unknown";
    
    // Mode tracking and delayed message tracking
    private static boolean macroMode = false;
    private static boolean clipboardMode = true; // Default to clipboard mode
    private static long lastGhostDetectionTime = 0;
    private static boolean macroReminderScheduled = false;
    
    // Constants
    private static final double SECONDS_PER_TICK = 0.05; // 50ms per tick (20 ticks per second)

    // Pattern to detect death chat messages like "<player> was killed" or "<player> was killed by <killer>" (with optional '!')
    private static final Pattern CHAT_DEATH_PATTERN = Pattern.compile("([a-zA-Z0-9_]+) was killed(?: by [a-zA-Z0-9_]+)?!?", Pattern.CASE_INSENSITIVE);

    // Toggle macro mode for chat macro functionality
    public static void toggleMacroMode() {
        InsigniaConfig config = InsigniaConfig.getInstance();
        config.ghostTotemMacroMode = !config.ghostTotemMacroMode;
        config.ghostTotemClipboardMode = !config.ghostTotemMacroMode;
        macroMode = config.ghostTotemMacroMode;
        clipboardMode = config.ghostTotemClipboardMode;
        InsigniaConfig.save();
        
        if (MinecraftClient.getInstance().player != null) {
            String statusMessage = macroMode ? 
                "§a[Ghost Detector] Chat macro mode ENABLED" : 
                "§c[Ghost Detector] Chat macro mode DISABLED";
            MinecraftClient.getInstance().player.sendMessage(Text.literal(statusMessage), false);
        }
        TaggerMod.LOGGER.info("[GhostTotem] Macro mode toggled to: {}", macroMode);
    }
    
    // Toggle clipboard mode for clipboard functionality
    public static void toggleClipboardMode() {
        InsigniaConfig config = InsigniaConfig.getInstance();
        config.ghostTotemClipboardMode = !config.ghostTotemClipboardMode;
        config.ghostTotemMacroMode = !config.ghostTotemClipboardMode;
        clipboardMode = config.ghostTotemClipboardMode;
        macroMode = config.ghostTotemMacroMode;
        InsigniaConfig.save();
        
        if (MinecraftClient.getInstance().player != null) {
            String statusMessage = clipboardMode ? 
                "§a[Ghost Detector] Clipboard mode ENABLED" : 
                "§c[Ghost Detector] Clipboard mode DISABLED";
            MinecraftClient.getInstance().player.sendMessage(Text.literal(statusMessage), false);
        }
        TaggerMod.LOGGER.info("[GhostTotem] Clipboard mode toggled to: {}", clipboardMode);
    }
    
    // Get current macro mode status
    public static boolean isMacroModeEnabled() {
        return macroMode;
    }
    
    // Get current clipboard mode status
    public static boolean isClipboardModeEnabled() {
        return clipboardMode;
    }
    
    // Sync internal state with config (called on init)
    public static void syncWithConfig() {
        InsigniaConfig config = InsigniaConfig.getInstance();
        macroMode = config.ghostTotemMacroMode;
        clipboardMode = config.ghostTotemClipboardMode;
        TaggerMod.LOGGER.info("[GhostTotem] Synced with config - Macro: {}, Clipboard: {}", macroMode, clipboardMode);
    }
    
    // Called every client tick
    public static void tick(MinecraftClient client) {
        // Check if feature is enabled
        if (!InsigniaConfig.getInstance().ghostTotemEnabled) {
            return;
        }
        
        gameTickCounter++; // Increment our own tick counter for precise timing
        
        // Handle delayed macro reminder message
        if (macroReminderScheduled && lastGhostDetectionTime > 0) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastGhostDetectionTime >= 3000) { // 3 seconds
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("§e[Ghost Detector] Do /gd macro for chat macro mode or /gd clipboard for clipboard mode (check your server's rules!)"), false);
                }
                macroReminderScheduled = false;
            }
        }
        
        if (client.player == null || client.player.getInventory() == null) {
            // Reset if player or inventory is not available (e.g., title screen)
            if (totemEquipTimeNano > 0 || lastCheckedSlot != -1 || lastHealth != -1) {
                if (TaggerMod.DEBUG_MODE) {
                    TaggerMod.LOGGER.info("[GhostTotem] Resetting state (player/inventory null)");
                }
                totemEquipTimeNano = 0;
                lastCheckedSlot = -1;
                lastHealth = -1;
                wasAlive = true;
                wasHoldingTotemLastTick = false;
                lastGameMode = null;
            }
            return;
        }

        ClientPlayerEntity player = client.player;
        int currentSlot = player.getInventory().selectedSlot;
        
        // Get current game mode - note this uses interactionManager which is client-side
        GameMode currentGameMode = null;
        if (client.interactionManager != null) {
            currentGameMode = client.interactionManager.getCurrentGameMode();
        }
        
        // Multiple ways to detect death
        float currentHealth = player.getHealth();
        boolean isDead = player.isDead(); // Direct method to check if player is dead
        boolean isAlive = !isDead && currentHealth > 0;
        
        // Check for transition to spectator mode (common server behavior for death)
        boolean spectatorTransition = false;
        if (lastGameMode != null && currentGameMode == GameMode.SPECTATOR && lastGameMode != GameMode.SPECTATOR) {
            spectatorTransition = true;
            TaggerMod.LOGGER.info("[GhostTotem] Player transitioned to SPECTATOR mode (potential death)");
        }
        
        // If we were alive before but now we're not, or we entered spectator mode, we died
        if ((wasAlive && !isAlive) || spectatorTransition) {
            TaggerMod.LOGGER.info("[GhostTotem] Death detected! isDead: {}, currentHealth: {}, spectatorTransition: {}, totem active: {}", 
                              isDead, currentHealth, spectatorTransition, totemEquipTimeNano > 0);
            
            // Log state of Death Screen as additional info
            if (client.currentScreen != null) {
                TaggerMod.LOGGER.info("[GhostTotem] Current screen: {}", client.currentScreen.getClass().getSimpleName());
            } else {
                TaggerMod.LOGGER.info("[GhostTotem] No current screen");
            }
            
            // Call our death handler, passing the spectator transition status
            onPlayerDeath(player, spectatorTransition);
        }
        wasAlive = isAlive;
        lastHealth = currentHealth;
        lastGameMode = currentGameMode;
        
        // Check if player is currently holding a totem (in either hand)
        boolean isHoldingTotemNow = isPlayerHoldingTotem(player);
        
        // If totem state changed
        if (isHoldingTotemNow != wasHoldingTotemLastTick) {
            if (isHoldingTotemNow) {
                // Player just started holding a totem
                handleTotemEquipped(player);
            } else {
                // Player just stopped holding a totem
                handleTotemUnequipped(player);
            }
        }
        
        // Log special debug for totem slot changes even if slot didn't change
        if (currentSlot != lastCheckedSlot) {
            ItemStack currentItem = player.getInventory().main.get(currentSlot);
            if (TaggerMod.DEBUG_MODE) {
                TaggerMod.LOGGER.info("[GhostTotem] Slot changed from {} to {}. New item: {}", 
                                    lastCheckedSlot, currentSlot, currentItem.getItem());
            }
            lastCheckedSlot = currentSlot;
        }
        
        // Store the current totem state for next tick
        wasHoldingTotemLastTick = isHoldingTotemNow;
    }
    
    // Utility method to check if player is holding a totem in either hand
    private static boolean isPlayerHoldingTotem(ClientPlayerEntity player) {
        // Check main hand (current selected slot)
        ItemStack mainHandItem = player.getInventory().main.get(player.getInventory().selectedSlot);
        // Check off hand
        ItemStack offHandItem = player.getOffHandStack();
        
        return mainHandItem.getItem() == Items.TOTEM_OF_UNDYING || 
               offHandItem.getItem() == Items.TOTEM_OF_UNDYING;
    }
    
    // Called when a totem is equipped
    private static void handleTotemEquipped(ClientPlayerEntity player) {
        // Only start the timer if it wasn't already running
        if (totemEquipTimeNano == 0) {
            totemEquipTimeNano = System.nanoTime();
            totemEquipGameTick = gameTickCounter;
            
            // Determine where the totem is (main or off hand)
            ItemStack mainHandItem = player.getInventory().main.get(player.getInventory().selectedSlot);
            ItemStack offHandItem = player.getOffHandStack();
            
            boolean inMainHand = mainHandItem.getItem() == Items.TOTEM_OF_UNDYING;
            boolean inOffHand = offHandItem.getItem() == Items.TOTEM_OF_UNDYING;
            
            String location = inMainHand ? 
                "main hand (slot " + player.getInventory().selectedSlot + ")" : 
                (inOffHand ? "off hand" : "unknown");
            
            // Remember which hand so we can reference it later if inventory suddenly clears
            lastTotemHand = inMainHand ? "Mainhand" : (inOffHand ? "Offhand" : "Unknown");
            
            // Always log this regardless of DEBUG_MODE
            TaggerMod.LOGGER.info("[GhostTotem] Totem equipped in {}. System time: {}, Game tick: {}", 
                               location, TIME_FORMAT.format(new Date()), totemEquipGameTick);
        }
    }
    
    // Called when a totem is unequipped
    private static void handleTotemUnequipped(ClientPlayerEntity player) {
        // Only handle if we were tracking a totem
        if (totemEquipTimeNano > 0) {
            long unequipTimeNano = System.nanoTime();
            long heldDurationNanos = unequipTimeNano - totemEquipTimeNano;
            long heldDurationMillis = heldDurationNanos / 1_000_000;
            long ticksHeld = gameTickCounter - totemEquipGameTick;
            
            // Always log regardless of DEBUG_MODE
            TaggerMod.LOGGER.info("[GhostTotem] Totem unequipped. Was held for {} ms ({} game ticks).", 
                               heldDurationMillis, ticksHeld);
            
            // ---------------------------------------------
            //  Ghost-via-Inventory-Clear detection
            // ---------------------------------------------
            // ONLY check for inventory clear if we were ACTUALLY holding a totem when it disappeared
            boolean wasActuallyHoldingTotem = wasHoldingTotemLastTick;
            
            if (wasActuallyHoldingTotem) {
                int emptySlots = countEmptyInventorySlots(player);
                boolean inventoryLikelyCleared = emptySlots >= 27; // 75%+ empty

                ItemStack mainNow = player.getInventory().main.get(player.getInventory().selectedSlot);
                ItemStack offNow  = player.getOffHandStack();
                boolean handNowEmpty = mainNow.isEmpty() || offNow.isEmpty();

                // Ignore if we actually popped a totem very recently (server sends status 35)
                long now = System.currentTimeMillis();
                boolean poppedRecently = (now - lastTotemPopTime) < 2000; // 2-s window
                boolean recentChatDeath = (now - lastSelfDeathChatTime) < 5000; // 5-second window

                if (TaggerMod.DEBUG_MODE) {
                    TaggerMod.LOGGER.info("[GhostTotem]   wasActuallyHoldingTotem = {}", wasActuallyHoldingTotem);
                    TaggerMod.LOGGER.info("[GhostTotem]   inventoryLikelyCleared = {} ({} empty)", inventoryLikelyCleared, emptySlots);
                    TaggerMod.LOGGER.info("[GhostTotem]   handNowEmpty         = {}", handNowEmpty);
                    TaggerMod.LOGGER.info("[GhostTotem]   poppedRecently       = {} ({} ms ago)", poppedRecently, now - lastTotemPopTime);
                    TaggerMod.LOGGER.info("[GhostTotem]   recentChatDeath      = {} ({} ms ago)", recentChatDeath, now - lastSelfDeathChatTime);
                }

                if (inventoryLikelyCleared && handNowEmpty && !poppedRecently && recentChatDeath) {
                    TaggerMod.LOGGER.info("[GhostTotem] Player was holding totem when inventory cleared ({} empty slots) — treating as ghost.", emptySlots);

                    // Use the regular onPlayerDeath pathway to reuse broadcast logic before we zero the timer.
                    onPlayerDeath(player, false);
                } else {
                    if (TaggerMod.DEBUG_MODE) {
                        TaggerMod.LOGGER.info("[GhostTotem] Inventory clear conditions not met - NOT treating as ghost");
                    }
                }
            } else {
                if (TaggerMod.DEBUG_MODE) {
                    TaggerMod.LOGGER.info("[GhostTotem] Player was NOT holding totem when unequipped - NOT checking inventory clear");
                }
            }
            
            totemEquipTimeNano = 0;
        }
    }

    // Counts how many main-inventory slots are empty (0-35). Ignores armor/offhand.
    private static int countEmptyInventorySlots(ClientPlayerEntity player) {
        int empty = 0;
        for (ItemStack stack : player.getInventory().main) {
            if (stack.isEmpty()) {
                empty++;
            }
        }
        return empty;
    }

    // This handles player death with ghost totem detection
    private static void onPlayerDeath(ClientPlayerEntity player, boolean spectatorTransition) {
        // Check if we were holding a totem when we died
        if (totemEquipTimeNano > 0) {
            long deathTimeNano = System.nanoTime();
            long durationNanos = deathTimeNano - totemEquipTimeNano;
            long durationMillis = durationNanos / 1_000_000; // Convert nanoseconds to milliseconds
            long deathGameTick = gameTickCounter;
            long ticksHeld = deathGameTick - totemEquipGameTick;

            // Determine if the totem was in main hand or off hand at time of death
            ItemStack mainHandItem = player.getInventory().main.get(player.getInventory().selectedSlot);
            ItemStack offHandItem = player.getOffHandStack();
            
            boolean inMainHand = mainHandItem.getItem() == Items.TOTEM_OF_UNDYING;
            boolean inOffHand = offHandItem.getItem() == Items.TOTEM_OF_UNDYING;
            
            String handType = inMainHand ? "Mainhand" : (inOffHand ? "Offhand" : "Unknown");

            // Save these values for diagnostics
            lastGhostTotemTime = System.currentTimeMillis();
            ghostTotemHoldTime = durationMillis;

            // Detailed game tick info for the consistent 48-49ms issue
            TaggerMod.LOGGER.info("[GhostTotem] ====== GHOST TOTEM DETECTED! ======");
            TaggerMod.LOGGER.info("[GhostTotem] Hand: {}", handType);
            TaggerMod.LOGGER.info("[GhostTotem] Totem equipped on game tick: {}", totemEquipGameTick);
            TaggerMod.LOGGER.info("[GhostTotem] Death occurred on game tick: {}", deathGameTick);
            TaggerMod.LOGGER.info("[GhostTotem] Ticks between equip and death: {}", ticksHeld);
            TaggerMod.LOGGER.info("[GhostTotem] Expected duration for {} ticks: {} ms", 
                               ticksHeld, (ticksHeld * 1000 * SECONDS_PER_TICK));
            TaggerMod.LOGGER.info("[GhostTotem] Actual measured duration: {} ms", durationMillis);
            TaggerMod.LOGGER.info("[GhostTotem] Difference from expected: {} ms", 
                              durationMillis - (ticksHeld * 1000 * SECONDS_PER_TICK));
            TaggerMod.LOGGER.info("[GhostTotem] ===============================");

            // Always log this critical event with precise timing info
            TaggerMod.LOGGER.info("[GhostTotem] Death with {} totem held! Equip time: {}, Death time: {}, Held for {} ms ({} game ticks)",
                             handType.toLowerCase(), TIME_FORMAT.format(new Date(System.currentTimeMillis() - durationMillis)), 
                             TIME_FORMAT.format(new Date()), durationMillis, ticksHeld);

            // Check for players in render distance and prepare message accordingly
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null && client.world != null) {
                String publicMessage;
                // Include timing info only when duration is <= 300ms; otherwise omit timing
                if (durationMillis <= 300) {
                    publicMessage = String.format("<%s Ghost Detected> totem held for %dms (%d ticks)",
                                                 handType, durationMillis, ticksHeld);
                } else {
                    publicMessage = String.format("<%s Ghost Detected>", handType);
                }
                
                // Get players in render distance (using a reasonable render distance of ~16 blocks)
                World world = client.world;
                Vec3d playerPos = player.getPos();
                double renderDistance = 16.0; // Standard chunk render distance
                Box renderArea = new Box(playerPos.subtract(renderDistance, renderDistance, renderDistance),
                                       playerPos.add(renderDistance, renderDistance, renderDistance));
                
                // Get nearby players excluding ourselves
                List<PlayerEntity> nearbyPlayers = world.getEntitiesByClass(PlayerEntity.class, renderArea, entity -> 
                    entity != player // Exclude ourselves
                );
                
                TaggerMod.LOGGER.info("[GhostTotem] Found {} players in render distance", nearbyPlayers.size());
                
                String commandToSend;
                if (nearbyPlayers.size() == 1) {
                    // Exactly one player in render distance - prepare private message
                    PlayerEntity targetPlayer = nearbyPlayers.get(0);
                    String targetPlayerName = targetPlayer.getName().getString();
                    commandToSend = "/w " + targetPlayerName + " " + publicMessage;
                    TaggerMod.LOGGER.info("[GhostTotem] Prepared private message to {}", targetPlayerName);
                } else {
                    // 0 or more than 1 player in render distance - prepare global chat message
                    commandToSend = publicMessage;
                    TaggerMod.LOGGER.info("[GhostTotem] Prepared global chat message");
                }
                
                                 // Send message based on mode
                 if (macroMode && client.getNetworkHandler() != null) {
                     // Macro mode enabled - send command directly
                     if (nearbyPlayers.size() == 1) {
                         client.getNetworkHandler().sendChatCommand("w " + nearbyPlayers.get(0).getName().getString() + " " + publicMessage);
                     } else {
                         client.getNetworkHandler().sendChatMessage(publicMessage);
                     }
                     TaggerMod.LOGGER.info("[GhostTotem] Sent command via macro mode");
                 } else if (clipboardMode) {
                     // Clipboard mode enabled - send big message and copy to clipboard
                     sendGhostDetectionMessage(commandToSend, handType, durationMillis, ticksHeld);
                 } else {
                     // Both modes disabled - just log the detection
                     TaggerMod.LOGGER.info("[GhostTotem] Ghost detected but both modes are disabled");
                 }
            }

            // Reset the timer immediately to prevent multiple messages for the same death event
            totemEquipTimeNano = 0;
            ghostTotemHoldTime = 0;
        } else {
            // Log that a death was detected, but no totem was active
            TaggerMod.LOGGER.info("[GhostTotem] Death detected, but no totem was being held.");
            // New logic: Some servers keep the player in SURVIVAL, clear inventory and broadcast the
            // death message before the client has a chance to equip a totem. In these cases the
            // player remains alive (health > 0, not dead, still in SURVIVAL) and we *still* want to
            // warn nearby players that a ghost has occurred even though we have no timing data.

            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.player != null) {
                // Consider it a ghost death if we are still alive *and* not in spectator mode.
                boolean stillAlive = client.player.getHealth() > 0 && !client.player.isDead();
                boolean inSpectator = false;
                if (client.interactionManager != null && client.interactionManager.getCurrentGameMode() != null) {
                    inSpectator = client.interactionManager.getCurrentGameMode() == GameMode.SPECTATOR;
                }

                if (stillAlive && !inSpectator) {
                    // Mark time so we do not double-process this death.
                    lastGhostTotemTime = System.currentTimeMillis();

                    // Calculate timing information if we have recent totem hold data
                    long durationMillis = 0;
                    long ticksHeld = 0;
                    String handType = "Unknown";
                    
                    if (ghostTotemHoldTime > 0) {
                        durationMillis = ghostTotemHoldTime;
                        ticksHeld = durationMillis / 50; // Convert ms to ticks (20 ticks per second = 50ms per tick)
                        handType = lastTotemHand;
                    }

                    String publicMessage;
                    // Include timing info only when duration is <= 300ms; otherwise omit timing
                    if (durationMillis > 0 && durationMillis <= 300) {
                        publicMessage = String.format("<%s Ghost Detected> totem held for %dms (%d ticks)",
                                                     handType, durationMillis, ticksHeld);
                    } else {
                        publicMessage = String.format("<%s Ghost Detected>", handType);
                    }

                    // Prepare message for exactly one nearby player if possible, otherwise to global chat
                    if (client.world != null) {
                        World world = client.world;
                        Vec3d playerPos = client.player.getPos();
                        double renderDistance = 16.0;
                        Box renderArea = new Box(playerPos.subtract(renderDistance, renderDistance, renderDistance),
                                                 playerPos.add(renderDistance, renderDistance, renderDistance));

                        List<PlayerEntity> nearbyPlayers = world.getEntitiesByClass(PlayerEntity.class, renderArea,
                                entity -> entity != client.player);

                        TaggerMod.LOGGER.info("[GhostTotem] (No-totem) Found {} players in render distance", nearbyPlayers.size());

                        String commandToSend;
                        if (nearbyPlayers.size() == 1) {
                            PlayerEntity targetPlayer = nearbyPlayers.get(0);
                            String targetPlayerName = targetPlayer.getName().getString();
                            commandToSend = "/w " + targetPlayerName + " " + publicMessage;
                            TaggerMod.LOGGER.info("[GhostTotem] (No-totem) Prepared private message to {}", targetPlayerName);
                        } else {
                            commandToSend = publicMessage;
                            TaggerMod.LOGGER.info("[GhostTotem] (No-totem) Prepared global chat message");
                        }
                        
                                                 // Send message based on mode
                         if (macroMode && client.getNetworkHandler() != null) {
                             // Macro mode enabled - send command directly
                             if (nearbyPlayers.size() == 1) {
                                 client.getNetworkHandler().sendChatCommand("w " + nearbyPlayers.get(0).getName().getString() + " " + publicMessage);
                             } else {
                                 client.getNetworkHandler().sendChatMessage(publicMessage);
                             }
                             TaggerMod.LOGGER.info("[GhostTotem] (No-totem) Sent command via macro mode");
                         } else if (clipboardMode) {
                             // Clipboard mode enabled - send big message and copy to clipboard
                             sendGhostDetectionMessage(commandToSend, handType, durationMillis, ticksHeld);
                         } else {
                             // Both modes disabled - just log the detection
                             TaggerMod.LOGGER.info("[GhostTotem] (No-totem) Ghost detected but both modes are disabled");
                         }
                         
                         // Reset the ghost totem hold time after use
                         ghostTotemHoldTime = 0;
                    }
                }
            }
        }
    }

    // This method is left for compatibility with the mixin, but we'll move the actual logic to onPlayerDeath
    // Deprecated in favor of the more reliable onPlayerDeath method
    public static void onPlayerHealthUpdate(ClientPlayerEntity player, float health) {
        // If health dropped to zero, call our unified death handler
        // Assuming spectator transition is false when triggered only by health update
        if (health <= 0 && wasAlive) { // Add wasAlive check to prevent multiple calls if health stays at 0
            TaggerMod.LOGGER.info("[GhostTotem] Death detected via health update method. Health: {}", health);
            onPlayerDeath(player, false); // Pass false for spectatorTransition
        }
    }
    
    // These methods are for diagnostics if needed
    public static long getLastGhostTotemTime() {
        return lastGhostTotemTime;
    }
    
    public static long getGhostTotemHoldTime() {
        return ghostTotemHoldTime;
    }

    /**
     * Called for every incoming chat message. If the message indicates that the local player was
     * just killed (according to the server-side death broadcast), we trigger the same ghost-totem
     * logic that would fire when we detect the death via health drop or spectator transition.
     */
    public static void onChatMessage(String message) {
        // Quick sanity check to avoid unnecessary regex work
        if (message == null || !message.toLowerCase().contains("was killed")) {
            return;
        }

        Matcher matcher = CHAT_DEATH_PATTERN.matcher(message);
        if (!matcher.find()) {
            return;
        }

        String victimName = matcher.group(1);

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }

        String selfName = client.player.getName().getString();
        if (!victimName.equalsIgnoreCase(selfName)) {
            // Not our own death – ignore.
            return;
        }

        // Avoid duplicate handling if we already processed a death very recently
        if (System.currentTimeMillis() - lastGhostTotemTime < 1000) {
            return;
        }

        if (TaggerMod.DEBUG_MODE) {
            TaggerMod.LOGGER.info("[GhostTotem] Death detected via chat message: '{}'", message);
        }

        // Trigger the same handler we use for health/spectator detections.
        onPlayerDeath(client.player, false);

        long now = System.currentTimeMillis();
        lastSelfDeathChatTime = now;

        if (TaggerMod.DEBUG_MODE) {
            TaggerMod.LOGGER.info("[GhostTotem] Self death chat detected at {}", TIME_FORMAT.format(new Date(now)));
        }

        // Only call onPlayerDeath immediately if a totem is currently tracked (timed ghost case).
        if (totemEquipTimeNano > 0) {
            onPlayerDeath(client.player, false);
        }
    }

    // Send big unmissable message and copy command to clipboard
    private static void sendGhostDetectionMessage(String command, String handType, long durationMillis, long ticksHeld) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;
        
        // Copy the command to clipboard using Minecraft's clipboard system
        try {
            if (client.keyboard != null) {
                client.keyboard.setClipboard(command);
                TaggerMod.LOGGER.info("[GhostTotem] Command copied to clipboard: {}", command);
            } else {
                TaggerMod.LOGGER.error("[GhostTotem] Failed to copy to clipboard: Minecraft keyboard is null");
            }
        } catch (Exception e) {
            TaggerMod.LOGGER.error("[GhostTotem] Failed to copy to clipboard: {}", e.getMessage());
        }
        
        // Send big unmissable multi-line message
        client.player.sendMessage(Text.literal("§c§l" + "=".repeat(50)), false);
        client.player.sendMessage(Text.literal("§c§l🚨 GHOST TOTEM DETECTED! 🚨"), false);
        client.player.sendMessage(Text.literal("§c§l" + "=".repeat(50)), false);
        client.player.sendMessage(Text.literal("§eHand: §f" + handType), false);
        client.player.sendMessage(Text.literal("§eDuration: §f" + durationMillis + "ms (" + ticksHeld + " ticks)"), false);
        client.player.sendMessage(Text.literal("§c§l" + "=".repeat(50)), false);
        client.player.sendMessage(Text.literal("§aCommand copied to clipboard!"), false);
        client.player.sendMessage(Text.literal("§aPaste it in chat to report the ghost:"), false);
        client.player.sendMessage(Text.literal("§7" + command), false);
        client.player.sendMessage(Text.literal("§c§l" + "=".repeat(50)), false);
        
        // Schedule the macro reminder
        lastGhostDetectionTime = System.currentTimeMillis();
        macroReminderScheduled = true;
    }
    
    // Called by TotemPopMixin when the server tells the client we used a totem (status 35)
    public static void onLocalPlayerTotemPop() {
        lastTotemPopTime = System.currentTimeMillis();
        if (TaggerMod.DEBUG_MODE) {
            TaggerMod.LOGGER.info("[GhostTotem] Local player totem pop detected via status packet");
        }
    }
}
