package net.infiniteimperm.fabric.tagger.mixin;

import net.infiniteimperm.fabric.tagger.QueueDurabilityChecker;
import net.infiniteimperm.fabric.tagger.TaggerMod;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class CommandInterceptorMixin {
    
    @Inject(method = "sendChatCommand", at = @At("HEAD"), cancellable = true)
    private void interceptCommand(String command, CallbackInfo ci) {
        // Check if this is a queue command
        if (QueueDurabilityChecker.isQueueCommand(command)) {
            TaggerMod.LOGGER.info("[CommandInterceptor] Intercepted queue command: /{}", command);
            
            // Check armor durability and potentially block the command
            if (QueueDurabilityChecker.processQueueCommand(command)) {
                TaggerMod.LOGGER.info("[CommandInterceptor] Blocking queue command due to damaged armor");
                ci.cancel(); // Block the command from being sent
            } else {
                TaggerMod.LOGGER.info("[CommandInterceptor] Allowing queue command to proceed");
                // Command will proceed normally
            }
        }
    }
}
