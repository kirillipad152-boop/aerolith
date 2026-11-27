package dev.aerolith.mixin;

import dev.aerolith.CraftWater;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityFluidInteraction;
import net.neoforged.neoforge.fluids.FluidType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fluid in a craft's blocks counts for entities on the craft: swimming, floating, drowning, being pushed by flow. */
@Mixin(EntityFluidInteraction.class)
abstract class EntityFluidMixin {
    @Inject(method = "update(Lnet/minecraft/world/entity/Entity;Ljava/util/function/Predicate;)Z", at = @At("RETURN"), cancellable = true)
    private void aerolith$craftFluid(Entity entity, Predicate<FluidType> acceptsPush, CallbackInfoReturnable<Boolean> callback) {
        var interaction = (EntityFluidInteraction) (Object) this;
        boolean wet = false;
        for (CraftWater.Contact contact : CraftWater.touching(entity)) {
            var tracker = interaction.getOrCreateTrackerFor(contact.fluid());
            tracker.height = Math.max(tracker.height, contact.height());
            wet |= tracker.height > 0;
            if (contact.push().lengthSqr() > 0.0) entity.setDeltaMovement(entity.getDeltaMovement().add(contact.push()));
        }
        if (interaction.getFirstEyeInFluid().isAir()) {
            var fluid = CraftWater.atEyes(entity);
            if (fluid != null) interaction.getOrCreateTrackerFor(fluid).eyesInside = true;
        }
        if (wet) callback.setReturnValue(true);
    }
}
