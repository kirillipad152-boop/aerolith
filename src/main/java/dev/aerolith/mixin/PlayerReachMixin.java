package dev.aerolith.mixin;

import dev.aerolith.Plots;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Containers and menus keep asking the player whether a block is within reach. A block of a craft lies in the plot
 * dimension, far from where it is drawn, so for those the question is answered with the block's place in the world.
 */
@Mixin(Player.class)
abstract class PlayerReachMixin {
    @Inject(method = "canInteractWithBlock", at = @At("HEAD"), cancellable = true)
    private void aerolith$craftBlocks(BlockPos pos, double extra, CallbackInfoReturnable<Boolean> callback) {
        Boolean reach = Plots.reach((Player) (Object) this, pos, extra);
        if (reach != null) callback.setReturnValue(reach);
    }
}
