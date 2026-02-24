package net.infiniteimperm.fabric.tagger.mixin;

import net.infiniteimperm.fabric.tagger.TaggerMod;
import net.infiniteimperm.fabric.tagger.diagnose.DiagnoseOrchestrator;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientReloadMixin {
    @Unique
    private static final AtomicInteger INSIGNIA_RELOAD_DEPTH = new AtomicInteger(0);
    @Unique
    private static final AtomicLong INSIGNIA_RELOAD_START_NS = new AtomicLong(0L);

    @Inject(method = "reloadResources()Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), require = 0)
    private void insignia$reloadStartNoArgs(CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        insignia$markReloadStart();
    }

    @Inject(method = "reloadResources(Z)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), require = 0)
    private void insignia$reloadStartBoolean(boolean force, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        insignia$markReloadStart();
    }

    @Inject(method = "reloadResources()Ljava/util/concurrent/CompletableFuture;", at = @At("RETURN"), require = 0)
    private void insignia$reloadEndNoArgs(CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        insignia$attachReloadEnd(cir);
    }

    @Inject(method = "reloadResources(Z)Ljava/util/concurrent/CompletableFuture;", at = @At("RETURN"), require = 0)
    private void insignia$reloadEndBoolean(boolean force, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        insignia$attachReloadEnd(cir);
    }

    @Unique
    private void insignia$markReloadStart() {
        if (INSIGNIA_RELOAD_DEPTH.getAndIncrement() == 0) {
            long now = System.nanoTime();
            INSIGNIA_RELOAD_START_NS.set(now);
            DiagnoseOrchestrator.onResourceReloadStart();
            TaggerMod.LOGGER.info("[Diagnose][JFR] Resource reload START");
        }
    }

    @Unique
    private void insignia$attachReloadEnd(CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        CompletableFuture<Void> future = cir.getReturnValue();
        if (future == null) {
            insignia$completeReloadInvocation(null);
            return;
        }
        future.whenComplete((ignored, throwable) -> insignia$completeReloadInvocation(throwable));
    }

    @Unique
    private static void insignia$completeReloadInvocation(Throwable throwable) {
        int depthAfter = INSIGNIA_RELOAD_DEPTH.decrementAndGet();
        if (depthAfter > 0) {
            return;
        }
        INSIGNIA_RELOAD_DEPTH.set(0);
        long start = INSIGNIA_RELOAD_START_NS.getAndSet(0L);
        long durationNs = start > 0L ? Math.max(0L, System.nanoTime() - start) : 0L;
        DiagnoseOrchestrator.onResourceReloadEnd(durationNs);
        if (throwable == null) {
            TaggerMod.LOGGER.info("[Diagnose][JFR] Resource reload END durationNs={}", durationNs);
        } else {
            TaggerMod.LOGGER.warn("[Diagnose][JFR] Resource reload END with error durationNs={}", durationNs, throwable);
        }
    }
}
