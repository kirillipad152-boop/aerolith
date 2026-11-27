package dev.aerolith.mixin;

import dev.aerolith.PlotChunks;
import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Chunks of the plot grid have no tickets; they tick while {@link PlotChunks} keeps them. */
@Mixin(value = DistanceManager.class, priority = 2000)
abstract class DistanceManagerPlotMixin {
    @Inject(method = "inEntityTickingRange", at = @At("HEAD"), cancellable = true)
    private void aerolith$entities(long key, CallbackInfoReturnable<Boolean> callback) {
        if (PlotChunks.ticks((DistanceManager) (Object) this, key)) callback.setReturnValue(true);
    }

    @Inject(method = "inBlockTickingRange", at = @At("HEAD"), cancellable = true)
    private void aerolith$blocks(long key, CallbackInfoReturnable<Boolean> callback) {
        if (PlotChunks.ticks((DistanceManager) (Object) this, key)) callback.setReturnValue(true);
    }
}
