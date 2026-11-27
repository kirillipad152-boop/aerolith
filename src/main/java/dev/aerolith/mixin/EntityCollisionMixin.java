package dev.aerolith.mixin;

import dev.aerolith.HullCollisions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
abstract class EntityCollisionMixin {
    @Inject(method = "collide", at = @At("RETURN"), cancellable = true)
    private void aerolith$collideWithHull(Vec3 requested, CallbackInfoReturnable<Vec3> callback) {
        Vec3 vanilla = callback.getReturnValue();
        Vec3 clipped = HullCollisions.clipMovement((Entity) (Object) this, vanilla);
        if (clipped != vanilla) callback.setReturnValue(clipped);
    }
}
