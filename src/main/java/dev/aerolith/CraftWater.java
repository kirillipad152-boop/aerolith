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
        CraftSections sections = HullCollisions.sectionsIfPresent(entity.level());
        if (sections == null) return List.of();
        var box = entity.getBoundingBox();
        @SuppressWarnings("unchecked")
        List<Contact>[] out = new List[] {null};
        sections.forEachNear(box, craft -> {
            if (craft.isRemoved() || craft == entity || !craft.getBoundingBox().intersects(box)) return;
            for (CraftEntity.Touch touch : craft.touching(entity)) {
                if (out[0] == null) out[0] = new java.util.ArrayList<>();
                out[0].add(new Contact(touch.fluid(), touch.height(), touch.push()));
            }
        });
        return out[0] == null ? List.of() : out[0];
    }

    /** The fluid the entity's eyes are in, in some craft, or null. */
    public static Holder<Fluid> atEyes(Entity entity) {
        CraftSections sections = HullCollisions.sectionsIfPresent(entity.level());
        if (sections == null) return null;
        Vec3 eyes = new Vec3(entity.getX(), entity.getEyeY(), entity.getZ());
        var probe = new net.minecraft.world.phys.AABB(eyes, eyes).inflate(0.1);
        Holder<Fluid>[] found = new Holder[1];
        sections.anyNear(probe, craft -> {
            if (craft.isRemoved() || craft == entity || !craft.getBoundingBox().inflate(0.1).contains(eyes)) return false;
            Holder<Fluid> type = craft.fluidAt(eyes);
            if (type != null) {
                found[0] = type;
                return true;
            }
            return false;
        });
        return found[0];
    }
}
