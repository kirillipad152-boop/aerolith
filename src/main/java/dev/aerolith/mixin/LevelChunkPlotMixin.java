package dev.aerolith.mixin;

import dev.aerolith.Plots;
import dev.aerolith.WorldPhysics;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every change of a block goes through here, whether or not its chunk is ticking yet. The level's own "block updated"
 * call is skipped for chunks that do not tick, and a craft's plot chunk does not for its first tick.
 */
@Mixin(LevelChunk.class)
abstract class LevelChunkPlotMixin {
    @Shadow
    @Final
    Level level;

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void aerolith$changed(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> callback) {
        BlockState old = callback.getReturnValue();
        if (old != null && level instanceof ServerLevel server) {
            Plots.changed(server, pos, old, state);
            WorldPhysics.blockChanged(server, pos, old, state);
        }
    }
}
