package dev.aerolith.mixin;

import dev.aerolith.Plots;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Carries what happens to the blocks of a craft's plot over to the craft: changes, sounds, effects and block events. */
@Mixin(ServerLevel.class)
abstract class ServerLevelPlotMixin {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void aerolith$changed(BlockPos pos, BlockState old, BlockState now, int flags, CallbackInfo callback) {
        Plots.changed((ServerLevel) (Object) this, pos, old, now);
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("HEAD"), cancellable = true)
    private void aerolith$sound(Player player, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source,
            float volume, float pitch, long seed, CallbackInfo callback) {
        if (Plots.sound((ServerLevel) (Object) this, x, y, z, sound, source, volume, pitch, seed)) callback.cancel();
    }

    @Inject(method = "levelEvent", at = @At("HEAD"), cancellable = true)
    private void aerolith$levelEvent(Player player, int type, BlockPos pos, int data, CallbackInfo callback) {
        if (Plots.levelEvent((ServerLevel) (Object) this, player, type, pos, data)) callback.cancel();
    }

    @Inject(method = "doBlockEvent", at = @At("RETURN"))
    private void aerolith$blockEvent(BlockEventData event, CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValueZ()) Plots.blockEvent((ServerLevel) (Object) this, event.pos(), event.paramA(), event.paramB());
    }
}
