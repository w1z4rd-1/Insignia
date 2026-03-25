package net.infiniteimperm.fabric.tagger.mixin;

import net.infiniteimperm.fabric.tagger.diagnose.JfrController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.shader.GlProgram$Builder")
abstract class SodiumShaderProgramMixin {
    @Unique
    private long insigniaSodiumShaderStartNs;

    @Inject(method = "link", at = @At("HEAD"), require = 0)
    private void insignia$beforeSodiumShaderLink(CallbackInfoReturnable<?> cir) {
        JfrController.onSodiumShaderHookSeen();
        insigniaSodiumShaderStartNs = System.nanoTime();
    }

    @Inject(method = "link", at = @At("RETURN"), require = 0)
    private void insignia$afterSodiumShaderLink(CallbackInfoReturnable<?> cir) {
        if (insigniaSodiumShaderStartNs > 0L) {
            JfrController.onShaderCompileBatch(1, Math.max(0L, System.nanoTime() - insigniaSodiumShaderStartNs));
        }
        insigniaSodiumShaderStartNs = 0L;
    }
}
