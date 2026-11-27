package dev.aerolith.mixin;

import dev.aerolith.HullCollisions;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
abstract class PlayerCollisionMixin {
    @Inject(method = "canFallAtLeast", at = @At("RETURN"), cancellable = true)
    private void aerolith$sneakOnDeck(double dx, double dz, double distance, CallbackInfoReturnable<Boolean> callback) {
        Player player = (Player) (Object) this;
        if (callback.getReturnValue() && HullCollisions.hasSupport(player, player.getBoundingBox().move(dx, 0, dz), distance)) {
            callback.setReturnValue(false);
        }
    }
}
