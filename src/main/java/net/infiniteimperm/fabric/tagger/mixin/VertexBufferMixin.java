package net.infiniteimperm.fabric.tagger.mixin;

import net.infiniteimperm.fabric.tagger.diagnose.JfrController;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BuiltBuffer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VertexBuffer.class)
abstract class VertexBufferMixin {
    @Unique
    private long insigniaUploadStartNs;
    @Unique
    private long insigniaUploadBytes;

    @Inject(method = "upload", at = @At("HEAD"))
    private void insignia$beforeVertexUpload(BuiltBuffer data, CallbackInfo ci) {
        JfrController.onVanillaBufferHookSeen();
        insigniaUploadStartNs = System.nanoTime();
        insigniaUploadBytes = 0L;
        if (data != null) {
            @Nullable java.nio.ByteBuffer vertex = data.getBuffer();
            @Nullable java.nio.ByteBuffer sorted = data.getSortedBuffer();
            if (vertex != null) {
                insigniaUploadBytes += vertex.remaining();
            }
            if (sorted != null) {
                insigniaUploadBytes += sorted.remaining();
            }
        }
    }

    @Inject(method = "upload", at = @At("RETURN"))
    private void insignia$afterVertexUpload(BuiltBuffer data, CallbackInfo ci) {
        long durationNs = insigniaUploadStartNs > 0L ? Math.max(0L, System.nanoTime() - insigniaUploadStartNs) : 0L;
        JfrController.onBufferUploadBatch(1, insigniaUploadBytes, durationNs);
        insigniaUploadStartNs = 0L;
        insigniaUploadBytes = 0L;
    }
}
