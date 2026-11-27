package dev.aerolith;

import java.util.List;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;

/** Lets entities feel the fluid inside a craft the way they feel fluid in the world: swim, float, drown, be pushed. */
public final class CraftWater {
    private CraftWater() {}

    public record Contact(Holder<Fluid> fluid, double height, Vec3 push) {}

    /** The fluid of every craft overlapping {@code entity}. */
    public static List<Contact> touching(Entity entity) {
        var crafts = HullCollisions.crafts(entity.level());
        if (crafts.isEmpty()) return List.of();
        var box = entity.getBoundingBox();
        List<Contact> out = null;
        for (CraftEntity craft : crafts) {
            if (craft.isRemoved() || craft == entity || !craft.getBoundingBox().intersects(box)) continue;
            for (CraftEntity.Touch touch : craft.touching(entity)) {
                if (out == null) out = new java.util.ArrayList<>();
                out.add(new Contact(touch.fluid(), touch.height(), touch.push()));
            }
        }
        return out == null ? List.of() : out;
    }

    /** The fluid the entity's eyes are in, in some craft, or null. */
    public static Holder<Fluid> atEyes(Entity entity) {
        var crafts = HullCollisions.crafts(entity.level());
        if (crafts.isEmpty()) return null;
        Vec3 eyes = new Vec3(entity.getX(), entity.getEyeY(), entity.getZ());
        for (CraftEntity craft : crafts) {
            if (craft.isRemoved() || craft == entity || !craft.getBoundingBox().inflate(0.1).contains(eyes)) continue;
            Holder<Fluid> type = craft.fluidAt(eyes);
            if (type != null) return type;
        }
        return null;
    }
}
