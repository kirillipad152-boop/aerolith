package dev.aerolith.mixin;

import dev.aerolith.CraftEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.server.level.ServerLevel$EntityCallbacks")
abstract class CraftTrackingMixin {
    @Inject(method = "onTrackingStart(Lnet/minecraft/world/entity/Entity;)V", at = @At("TAIL"))
    private void aerolith$restoreCraft(Entity entity, CallbackInfo callback) {
        // Tracking can resume on the same instance after NeoForge called onRemovedFromLevel.
        if (entity instanceof CraftEntity craft && !craft.isRemoved()) craft.onAddedToLevel();
    }
}
