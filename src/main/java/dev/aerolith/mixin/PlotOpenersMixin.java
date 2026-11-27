package dev.aerolith.mixin;

import java.util.ArrayList;
import java.util.List;

import dev.aerolith.Plots;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A chest in a plot is opened by players who are not in the plot dimension; they still count as openers. */
@Mixin(ContainerOpenersCounter.class)
abstract class PlotOpenersMixin {
    @Shadow
    public abstract boolean isOwnContainer(Player player);

    @Inject(method = "getEntitiesWithContainerOpen", at = @At("RETURN"), cancellable = true)
    private void aerolith$remoteViewers(Level level, BlockPos pos, CallbackInfoReturnable<List<ContainerUser>> callback) {
        if (!(level instanceof ServerLevel server)) return;
        var remote = Plots.remoteOpeners(server, this::isOwnContainer);
        if (remote.isEmpty()) return;
        List<ContainerUser> players = new ArrayList<>(callback.getReturnValue());
        players.addAll(remote);
        callback.setReturnValue(players);
    }
}
