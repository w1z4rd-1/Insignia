package net.infiniteimperm.fabric.tagger.mixin;

import net.infiniteimperm.fabric.tagger.GhostTotemDetector;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Listens for status code 35 (totem pop) packets sent from the server.
 * When the local player receives one we notify GhostTotemDetector so it can
 * distinguish genuine totem pops from ghost totems.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class TotemPopMixin {

    @Inject(method = "onEntityStatus", at = @At("TAIL"))
    private void tagger$onEntityStatus(EntityStatusS2CPacket packet, CallbackInfo ci) {
        // Status 35 = Totem used (byte value)
        byte status;
        try {
            status = (byte) packet.getClass().getMethod("getStatus").invoke(packet);
        } catch (Exception e) {
            // Fallback: some mappings use getStatusByte()
            try {
                status = (byte) packet.getClass().getMethod("getStatusByte").invoke(packet);
            } catch (Exception ex) {
                status = -1;
            }
        }
        if (status != 35) {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld world = mc.world;
        if (world == null) return;

        // Newer Yarn versions expose getEntity(ClientWorld)
        Entity entity;
        try {
            entity = packet.getEntity(world);
        } catch (Throwable t) {
            // Fallback for older mappings that expose getEntityId()
            try {
                int id = (int) packet.getClass().getMethod("getEntityId").invoke(packet);
                entity = world.getEntityById(id);
            } catch (Exception e) {
                entity = null;
            }
        }
        if (entity instanceof PlayerEntity && entity == mc.player) {
            GhostTotemDetector.onLocalPlayerTotemPop();
        }
    }
}
