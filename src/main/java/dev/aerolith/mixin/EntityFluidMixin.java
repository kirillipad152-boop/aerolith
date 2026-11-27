package dev.aerolith.mixin;

import dev.aerolith.CraftWater;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.fluids.FluidType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fluid in a craft's blocks counts for entities on the craft: swimming, floating, drowning, being pushed by flow. */
@Mixin(Entity.class)
abstract class EntityFluidMixin {
    @Shadow
    protected Object2DoubleMap<FluidType> forgeFluidTypeHeight;
    @Shadow
    private FluidType forgeFluidTypeOnEyes;

    @Inject(method = "updateFluidHeightAndDoFluidPushing()V", at = @At("RETURN"))
    private void aerolith$craftFluid(CallbackInfo callback) {
        Entity self = (Entity) (Object) this;
        for (CraftWater.Contact contact : CraftWater.touching(self)) {
            forgeFluidTypeHeight.put(contact.type(), Math.max(forgeFluidTypeHeight.getDouble(contact.type()), contact.height()));
            if (contact.push().lengthSqr() > 0.0) self.setDeltaMovement(self.getDeltaMovement().add(contact.push()));
        }
    }

    @Inject(method = "updateFluidOnEyes", at = @At("RETURN"))
    private void aerolith$craftFluidOnEyes(CallbackInfo callback) {
        if (!forgeFluidTypeOnEyes.isAir()) return;
        FluidType type = CraftWater.atEyes((Entity) (Object) this);
        if (type != null) forgeFluidTypeOnEyes = type;
    }
}
