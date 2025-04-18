package net.infiniteimperm.fabric.tagger;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class StatsReader {
    private static final MinecraftClient client = MinecraftClient.getInstance();
    private static CompletableFuture<List<String>> currentTask = null;
    private static long taskStartTime = 0;
    private static final long TIMEOUT_MS = 500;
    private static final long GUI_LOADING_DELAY_MS = 300; // Increased wait time to 300ms
    private static long guiDetectedTime = 0;
    private static boolean guiDetected = false;
    
    // Track the current player we're checking stats for
    private static String currentPlayerName = null;

    /**
     * Executes the stats command for a player and attempts to read the resulting GUI
     * @param playerName The name of the player to get stats for
     * @return A future that will complete with the stats text or an empty list if timed out
     */
    public static CompletableFuture<List<String>> readPlayerStats(String playerName) {
        if (currentTask != null && !currentTask.isDone()) {
            TaggerMod.LOGGER.warn("Stats reader is already running a task, cancelling previous");
            currentTask.complete(new ArrayList<>());
        }

        // Create a new task
        currentTask = new CompletableFuture<>();
        taskStartTime = System.currentTimeMillis();
        guiDetected = false;
        
        // Store player name for checking private stats
        currentPlayerName = playerName;

        // Run the stats command
        client.getNetworkHandler().sendChatCommand("stats " + playerName);
        TaggerMod.LOGGER.info("Sent stats command for player: " + playerName);

        // Set timeout for the task
        CompletableFuture.delayedExecutor(TIMEOUT_MS, TimeUnit.MILLISECONDS).execute(() -> {
            if (!currentTask.isDone()) {
                TaggerMod.LOGGER.warn("Stats reading timed out after " + TIMEOUT_MS + "ms");
                currentTask.complete(new ArrayList<>());
            }
        });

        return currentTask;
    }

    /**
     * Handle private stats detection from chat message
     */
    public static void handlePrivateStats() {
        // If we're not currently checking stats, ignore
        if (currentTask == null || currentTask.isDone() || currentPlayerName == null) {
            return;
        }
        
        TaggerMod.LOGGER.info("Detected private stats for: {}", currentPlayerName);
        
        // Create a list with a "private stats" marker
        List<String> privateStatsList = new ArrayList<>();
        privateStatsList.add("Player's statistics are private");
        
        // Complete the task with this special list
        currentTask.complete(privateStatsList);
        
        // Reset current player name
        currentPlayerName = null;
    }

    /**
     * This method should be called every tick to check for GUI screens
     */
    public static void tick() {
        if (currentTask == null || currentTask.isDone()) return;
        
        // Check if we've exceeded the timeout
        if (System.currentTimeMillis() - taskStartTime > TIMEOUT_MS) {
            if (!currentTask.isDone()) {
                TaggerMod.LOGGER.warn("Stats reading timed out after " + TIMEOUT_MS + "ms");
                currentTask.complete(new ArrayList<>());
            }
            return;
        }

        // Check if a handled screen is open (like a chest/inventory)
        Screen currentScreen = client.currentScreen;
        if (currentScreen instanceof HandledScreen) {
            if (!guiDetected) {
                // First time detecting the GUI - record the time
                guiDetectedTime = System.currentTimeMillis();
                guiDetected = true;
                TaggerMod.LOGGER.info("Stats GUI detected, waiting {}ms before reading", GUI_LOADING_DELAY_MS);
                return;
            }
            
            // Check if we've waited long enough after GUI detection
            if (System.currentTimeMillis() - guiDetectedTime < GUI_LOADING_DELAY_MS) {
                return; // Still waiting for the delay
            }
            
            try {
                List<String> result = extractItemTexts((HandledScreen<?>) currentScreen);
                
                // Check if we found Crystal 1v1 stats
                boolean foundCrystalStats = false;
                for (String text : result) {
                    if (text.contains("Crystal 1v1")) {
                        foundCrystalStats = true;
                        break;
                    }
                }
                
                TaggerMod.LOGGER.info("Successfully read " + result.size() + " items from stats GUI");
                
                // Log stats finding result
                if (foundCrystalStats) {
                    TaggerMod.LOGGER.info("Found Crystal 1v1 stats in the GUI");
                } else {
                    TaggerMod.LOGGER.info("No Crystal 1v1 stats found");
                }
                
                // Only log if in debug mode
                if (TaggerMod.DEBUG_MODE) {
                    for (String text : result) {
                        TaggerMod.LOGGER.info("GUI Text: " + text);
                    }
                }
                
                // Complete the task with the results
                currentTask.complete(result);
                
                // Close the GUI automatically after reading, with a small delay
                // to ensure all data processing is complete
                final long closeDelay = 50;
                
                client.execute(() -> {
                    // Add a small delay before closing to ensure everything is processed
                    try {
                        Thread.sleep(closeDelay);
                    } catch (InterruptedException e) {
                        // Ignore interruption
                    }
                    
                    if (client.currentScreen instanceof HandledScreen) {
                        client.setScreen(null);
                        TaggerMod.LOGGER.info("Closed stats GUI after reading");
                    }
                });
            } catch (Exception e) {
                TaggerMod.LOGGER.error("Error reading stats GUI", e);
                currentTask.completeExceptionally(e);
            }
        }
    }

    /**
     * Checks if the stats reader is idle (not currently reading stats)
     * @return true if idle, false if currently reading stats
     */
    public static boolean isIdle() {
        return currentTask == null || currentTask.isDone();
    }

    /**
     * Extracts text from all items in a screen
     */
    private static List<String> extractItemTexts(HandledScreen<?> screen) {
        List<String> texts = new ArrayList<>();
        
        // This will need to be adapted based on how the screen stores its items
        if (screen.getScreenHandler() != null) {
            // Access slots in a more generic way that works with all screen handlers
            for (int i = 0; i < screen.getScreenHandler().slots.size(); i++) {
                Slot slot = screen.getScreenHandler().slots.get(i);
                ItemStack stack = slot.getStack();
                if (!stack.isEmpty()) {
                    // Get item name
                    Text name = stack.getName();
                    texts.add(name.getString());
                    
                    // Get item tooltip
                    List<Text> tooltip = Screen.getTooltipFromItem(client, stack);
                    for (Text text : tooltip) {
                        texts.add(text.getString());
                    }
                    
                    texts.add("-----------------"); // Separator between items
                }
            }
        }
        
        return texts;
    }
} 