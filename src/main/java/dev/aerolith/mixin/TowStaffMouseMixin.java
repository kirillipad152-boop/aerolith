package dev.aerolith.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import dev.aerolith.TowStaffClient;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MouseHandler.class)
abstract class TowStaffMouseMixin {
    @WrapWithCondition(method = "turnPlayer(D)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private boolean aerolith$turnCamera(LocalPlayer player, double yaw, double pitch) {
        return !TowStaffClient.turn(yaw, pitch);
    }
}
