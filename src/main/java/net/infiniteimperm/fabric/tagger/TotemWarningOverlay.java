package net.infiniteimperm.fabric.tagger;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

public class TotemWarningOverlay {

    private static final MinecraftClient client = MinecraftClient.getInstance();
    private static final int OVERLAY_COLOR = 0x40FF0000; // Semi-transparent red (ARGB)

    /**
     * Checks if the player should see the totem warning.
     * @return true if the player is not holding a totem but has one in inventory, false otherwise.
     */
    public static boolean shouldShowWarning() {
        // Check if feature is enabled
        if (!InsigniaConfig.getInstance().totemWarningEnabled) {
            return false;
        }
        
        if (client.player == null) {
            return false;
        }
        ClientPlayerEntity player = client.player;
        boolean isHoldingTotem = false;
        boolean hasTotemInInventory = false;

        // Check main hand and offhand
        ItemStack mainHandStack = player.getMainHandStack();
        ItemStack offHandStack = player.getOffHandStack();

        if (mainHandStack.getItem() == Items.TOTEM_OF_UNDYING || offHandStack.getItem() == Items.TOTEM_OF_UNDYING) {
            isHoldingTotem = true;
        }

        // Check main inventory 
        for (int i = 0; i < player.getInventory().main.size(); i++) {
            // Skip checking the main hand slot if it contains a totem (already checked above)
            if (mainHandStack.getItem() == Items.TOTEM_OF_UNDYING && i == player.getInventory().selectedSlot) {
                 continue; 
            }
            ItemStack stack = player.getInventory().main.get(i);
            if (stack.getItem() == Items.TOTEM_OF_UNDYING) {
                hasTotemInInventory = true;
                break; // Found one, no need to check further
            }
        }
        
        return !isHoldingTotem && hasTotemInInventory;
    }

    /**
     * Renders the overlay if the warning condition is met.
     * Called by HudRenderCallback and ScreenEvents.AFTER_RENDER.
     */
    public static void render(DrawContext drawContext, RenderTickCounter renderTickCounter) { 
        if (shouldShowWarning()) {
             int screenWidth = client.getWindow().getScaledWidth();
             int screenHeight = client.getWindow().getScaledHeight();
            
             // Draw the overlay
             drawContext.fill(0, 0, screenWidth, screenHeight, OVERLAY_COLOR);
        }
    }
}
