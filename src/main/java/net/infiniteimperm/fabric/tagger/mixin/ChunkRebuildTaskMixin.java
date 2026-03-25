package net.infiniteimperm.fabric.tagger.mixin;

import net.infiniteimperm.fabric.tagger.diagnose.JfrController;
import net.minecraft.client.render.chunk.BlockBufferAllocatorStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(targets = "net.minecraft.client.render.chunk.ChunkBuilder$BuiltChunk$RebuildTask")
abstract class ChunkRebuildTaskMixin {
    @Unique
    private long insigniaChunkBuildStartNs;
    @Unique
    private long insigniaChunkBuildDurationNs;
    @Unique
    private long insigniaChunkUploadStartNs;

    @Inject(method = "run", at = @At("HEAD"))
    private void insignia$resetChunkTimers(BlockBufferAllocatorStorage buffers, CallbackInfoReturnable<CompletableFuture<?>> cir) {
        JfrController.onVanillaChunkHookSeen();
        insigniaChunkBuildStartNs = 0L;
        insigniaChunkBuildDurationNs = 0L;
        insigniaChunkUploadStartNs = 0L;
    }

    @Inject(
        method = "run",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/chunk/SectionBuilder;build(Lnet/minecraft/util/math/ChunkSectionPos;Lnet/minecraft/client/render/chunk/ChunkRendererRegion;Lcom/mojang/blaze3d/systems/VertexSorter;Lnet/minecraft/client/render/chunk/BlockBufferAllocatorStorage;)Lnet/minecraft/client/render/chunk/SectionBuilder$RenderData;",
            shift = At.Shift.BEFORE
        )
    )
    private void insignia$beforeChunkBuild(BlockBufferAllocatorStorage buffers, CallbackInfoReturnable<CompletableFuture<?>> cir) {
        insigniaChunkBuildStartNs = System.nanoTime();
    }

    @Inject(
        method = "run",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/render/chunk/SectionBuilder;build(Lnet/minecraft/util/math/ChunkSectionPos;Lnet/minecraft/client/render/chunk/ChunkRendererRegion;Lcom/mojang/blaze3d/systems/VertexSorter;Lnet/minecraft/client/render/chunk/BlockBufferAllocatorStorage;)Lnet/minecraft/client/render/chunk/SectionBuilder$RenderData;",
            shift = At.Shift.AFTER
        )
    )
    private void insignia$afterChunkBuild(BlockBufferAllocatorStorage buffers, CallbackInfoReturnable<CompletableFuture<?>> cir) {
        if (insigniaChunkBuildStartNs > 0L) {
            insigniaChunkBuildDurationNs = Math.max(0L, System.nanoTime() - insigniaChunkBuildStartNs);
        }
    }

    @Inject(
        method = "run",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/util/Util;combine(Ljava/util/List;)Ljava/util/concurrent/CompletableFuture;",
            shift = At.Shift.BEFORE
        )
    )
    private void insignia$beforeChunkUploads(BlockBufferAllocatorStorage buffers, CallbackInfoReturnable<CompletableFuture<?>> cir) {
        insigniaChunkUploadStartNs = System.nanoTime();
    }

    @Inject(method = "run", at = @At("RETURN"), cancellable = true)
    private void insignia$wrapChunkCompletion(BlockBufferAllocatorStorage buffers, CallbackInfoReturnable<CompletableFuture<?>> cir) {
        CompletableFuture<?> original = cir.getReturnValue();
        if (original == null) {
            return;
        }
        cir.setReturnValue(original.whenComplete((result, throwable) -> {
            if (throwable == null && insigniaChunkBuildDurationNs > 0L) {
                long uploadNs = insigniaChunkUploadStartNs > 0L ? Math.max(0L, System.nanoTime() - insigniaChunkUploadStartNs) : 0L;
                JfrController.onChunkBuildBatch(1, insigniaChunkBuildDurationNs, uploadNs);
            }
            insigniaChunkBuildStartNs = 0L;
            insigniaChunkBuildDurationNs = 0L;
            insigniaChunkUploadStartNs = 0L;
        }));
    }
}
