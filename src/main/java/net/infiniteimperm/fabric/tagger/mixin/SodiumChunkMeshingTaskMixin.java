package net.infiniteimperm.fabric.tagger.mixin;

import net.infiniteimperm.fabric.tagger.diagnose.JfrController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask")
abstract class SodiumChunkMeshingTaskMixin {
    @Unique
    private long insigniaSodiumChunkStartNs;

    @Inject(method = "execute", at = @At("HEAD"), require = 0)
    private void insignia$beforeSodiumChunkBuild(CallbackInfoReturnable<?> cir) {
        JfrController.onSodiumChunkHookSeen();
        insigniaSodiumChunkStartNs = System.nanoTime();
    }

    @Inject(method = "execute", at = @At("RETURN"), require = 0)
    private void insignia$afterSodiumChunkBuild(CallbackInfoReturnable<?> cir) {
        if (insigniaSodiumChunkStartNs > 0L) {
            JfrController.onChunkBuildBatch(1, Math.max(0L, System.nanoTime() - insigniaSodiumChunkStartNs), 0L);
        }
        insigniaSodiumChunkStartNs = 0L;
    }
}
