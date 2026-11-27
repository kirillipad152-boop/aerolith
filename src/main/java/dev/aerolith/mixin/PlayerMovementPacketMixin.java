package dev.aerolith.mixin;

import dev.aerolith.HullCollisions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class PlayerMovementPacketMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "isPlayerCollidingWithAnythingNew", at = @At("RETURN"), cancellable = true)
    private void aerolith$validateHullMovement(LevelReader level, AABB start, double x, double y, double z,
            CallbackInfoReturnable<Boolean> callback) {
        // Vanilla can accept the requested absolute position after Entity.move already clipped it.
        if (!callback.getReturnValue() && HullCollisions.blocksPlayerMove(player, start, new Vec3(x, y, z))) {
            if (System.getProperty("aerolith.walkLog") != null) {
                com.mojang.logging.LogUtils.getLogger().warn("AEROLITH-REJECT hull move {} {} {}", x, y, z);
            }
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "noBlocksAround", at = @At("RETURN"), cancellable = true)
    private void aerolith$recognizeDeckSupport(Entity entity, CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValue() && HullCollisions.hasSupport(entity, entity.getBoundingBox(), 0.55)) {
            callback.setReturnValue(false);
        }
    }
}
