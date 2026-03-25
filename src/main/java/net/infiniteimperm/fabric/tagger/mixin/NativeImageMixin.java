package net.infiniteimperm.fabric.tagger.mixin;

import net.infiniteimperm.fabric.tagger.diagnose.JfrController;
import net.minecraft.client.texture.NativeImage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NativeImage.class)
abstract class NativeImageMixin {
    @Shadow
    @Final
    private NativeImage.Format format;

    @Unique
    private long insigniaTextureUploadStartNs;

    @Inject(method = "uploadInternal", at = @At("HEAD"))
    private void insignia$beforeTextureUpload(
        int level,
        int offsetX,
        int offsetY,
        int unpackSkipPixels,
        int unpackSkipRows,
        int width,
        int height,
        boolean blur,
        boolean clamp,
        boolean mipmap,
        boolean close,
        CallbackInfo ci
    ) {
        insigniaTextureUploadStartNs = System.nanoTime();
    }

    @Inject(method = "uploadInternal", at = @At("RETURN"))
    private void insignia$afterTextureUpload(
        int level,
        int offsetX,
        int offsetY,
        int unpackSkipPixels,
        int unpackSkipRows,
        int width,
        int height,
        boolean blur,
        boolean clamp,
        boolean mipmap,
        boolean close,
        CallbackInfo ci
    ) {
        long durationNs = insigniaTextureUploadStartNs > 0L ? Math.max(0L, System.nanoTime() - insigniaTextureUploadStartNs) : 0L;
        long bytes = Math.max(0L, (long) width * height * format.getChannelCount());
        JfrController.onTextureUploadBatch(1, bytes, durationNs);
        insigniaTextureUploadStartNs = 0L;
    }
}
