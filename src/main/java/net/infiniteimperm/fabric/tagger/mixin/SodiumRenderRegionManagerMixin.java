package net.infiniteimperm.fabric.tagger.mixin;

import net.infiniteimperm.fabric.tagger.diagnose.JfrController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager")
abstract class SodiumRenderRegionManagerMixin {
    @Unique
    private long insigniaSodiumUploadStartNs;

    @Inject(
        method = {
            "uploadResults(Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;Ljava/util/Collection;)V",
            "uploadResults(Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;Ljava/util/Collection;)V"
        },
        at = @At("HEAD"),
        require = 0
    )
    private void insignia$beforeSodiumUpload(CallbackInfo ci) {
        JfrController.onSodiumBufferHookSeen();
        insigniaSodiumUploadStartNs = System.nanoTime();
    }

    @Inject(
        method = {
            "uploadResults(Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;Ljava/util/Collection;)V",
            "uploadResults(Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;Ljava/util/Collection;)V"
        },
        at = @At("RETURN"),
        require = 0
    )
    private void insignia$afterSodiumUpload(CallbackInfo ci) {
        if (insigniaSodiumUploadStartNs > 0L) {
            JfrController.onBufferUploadBatch(1, 0L, Math.max(0L, System.nanoTime() - insigniaSodiumUploadStartNs));
        }
        insigniaSodiumUploadStartNs = 0L;
    }
}
