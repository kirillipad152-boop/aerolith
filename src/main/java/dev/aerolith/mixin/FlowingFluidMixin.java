package dev.aerolith.mixin;

import dev.aerolith.Plots;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fluid in a craft's plot spreads only inside the box its blocks span, not off the edges into the empty dimension. */
@Mixin(FlowingFluid.class)
abstract class FlowingFluidMixin {
    @Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true)
    private void aerolith$stayInTheCraft(LevelAccessor level, BlockPos pos, BlockState state, Direction direction, FluidState fluid,
            CallbackInfo callback) {
        if (!Plots.allowsFluid(level, pos)) callback.cancel();
    }

    /**
     * Cells outside the box count as solid, not as holes: otherwise water inside the craft would flow in a thin stream toward
     * the nearest edge, where a drop to nothing is "found", instead of filling the craft.
     */
    @Inject(method = "canHoldFluid", at = @At("HEAD"), cancellable = true)
    private static void aerolith$outsideIsWall(BlockGetter level, BlockPos pos, BlockState state, Fluid fluid, CallbackInfoReturnable<Boolean> callback) {
        if (!Plots.allowsFluid(level, pos)) callback.setReturnValue(false);
    }

    @Inject(method = "canPassThroughWall", at = @At("HEAD"), cancellable = true)
    private static void aerolith$outsideIsNotAFlowPath(Direction direction, BlockGetter level, BlockPos from, BlockState source,
            BlockPos to, BlockState target, CallbackInfoReturnable<Boolean> callback) {
        if (!Plots.allowsFluid(level, to)) callback.setReturnValue(false);
    }
}
