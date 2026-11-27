package dev.aerolith;

import java.util.List;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidType;

/** Lets entities feel the fluid inside a craft the way they feel fluid in the world: swim, float, drown, be pushed. */
public final class CraftWater {
    private CraftWater() {}

    public record Contact(FluidType type, double height, Vec3 push) {}

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
                out.add(new Contact(touch.type(), touch.height(), touch.push()));
            }
        }
        return out == null ? List.of() : out;
    }

    /** The fluid the entity's eyes are in, in some craft, or null. */
    public static FluidType atEyes(Entity entity) {
        var crafts = HullCollisions.crafts(entity.level());
        if (crafts.isEmpty()) return null;
        Vec3 eyes = new Vec3(entity.getX(), entity.getEyeY(), entity.getZ());
        for (CraftEntity craft : crafts) {
            if (craft.isRemoved() || craft == entity || !craft.getBoundingBox().inflate(0.1).contains(eyes)) continue;
            FluidType type = craft.fluidAt(eyes);
            if (type != null) return type;
        }
        return null;
    }
}
