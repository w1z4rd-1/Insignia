package net.infiniteimperm.fabric.tagger.mixin;

import net.infiniteimperm.fabric.tagger.PlayerHighlightTracker;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(LivingEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {
    
    @Unique
    private int insignia$currentHighlightColor = 0;
    
    @Inject(method = "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V", 
            at = @At("HEAD"))
    private void captureHighlightColor(LivingEntity entity, float yaw, float tickDelta, MatrixStack matrices, 
                                      VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        if (entity instanceof AbstractClientPlayerEntity) {
            UUID playerId = entity.getUuid();
            insignia$currentHighlightColor = PlayerHighlightTracker.getHighlightColor(playerId);
        } else {
            insignia$currentHighlightColor = 0;
        }
    }
    
    @ModifyVariable(method = "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
                   at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/model/EntityModel;render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;IIFFFF)V"),
                   ordinal = 0, argsOnly = false, index = 10)
    private float modifyBlue(float original) {
        if (insignia$currentHighlightColor != 0) {
            return ((insignia$currentHighlightColor) & 0xFF) / 255.0f;
        }
        return original;
    }
    
    @ModifyVariable(method = "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
                   at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/model/EntityModel;render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;IIFFFF)V"),
                   ordinal = 0, argsOnly = false, index = 9)
    private float modifyGreen(float original) {
        if (insignia$currentHighlightColor != 0) {
            return ((insignia$currentHighlightColor >> 8) & 0xFF) / 255.0f;
        }
        return original;
    }
    
    @ModifyVariable(method = "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
                   at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/model/EntityModel;render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;IIFFFF)V"),
                   ordinal = 0, argsOnly = false, index = 8)
    private float modifyRed(float original) {
        if (insignia$currentHighlightColor != 0) {
            return ((insignia$currentHighlightColor >> 16) & 0xFF) / 255.0f;
        }
        return original;
    }
    
    @Inject(method = "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V", 
            at = @At("RETURN"))
    private void cleanupHighlightColor(LivingEntity entity, float yaw, float tickDelta, MatrixStack matrices, 
                                      VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        insignia$currentHighlightColor = 0;
    }
}

