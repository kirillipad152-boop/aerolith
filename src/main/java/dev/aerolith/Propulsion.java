package dev.aerolith;

import java.util.List;

import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * Spinning machinery of other mods that pushes a craft: what Create fans and bearings do on board. A drive says, each
 * tick, how hard it pushes and where; the craft gets that as an impulse at that point. Nothing here refers to a class of
 * another mod, so a craft without any is as cheap as before; those that do are in {@link CreatePropulsion}.
 */
final class Propulsion {
    /** A thing that pushes, in the craft's own frame. */
    interface Drive {
        /** False once it is gone, which makes the craft look for drives again. */
        boolean alive();

        /** Force on the craft in newtons, or zero when idle. */
        Vec3 force();

        /** Where the force acts, as a craft block's centre is: at its offset from the craft's origin. */
        Vec3 at();
    }

    private static final double TICK = 0.05;
    /** A craft that has drives is looked through again this often, to find those that appeared without a block changing. */
    private static final int REFRESH = 200;
    private static Boolean create;

    private List<Drive> drives = List.of();
    private boolean dirty = true;
    private long scanAt;

    /** A block that has block entity data came or went: what is in the craft may be different. */
    void changed() {
        dirty = true;
    }

    static boolean createLoaded() {
        if (create == null) create = ModList.get().isLoaded("create");
        return create;
    }

    /** Pushes the craft for one tick. */
    void apply(CraftEntity craft, long world, long body, long now) {
        if (!createLoaded()) return;
        if (dirty || !drives.isEmpty() && now >= scanAt) {
            dirty = false;
            scanAt = now + REFRESH;
            drives = CreatePropulsion.scan(craft);
        }
        for (Drive drive : drives) {
            if (!drive.alive()) {
                dirty = true;
                continue;
            }
            Vec3 force = drive.force();
            if (force.lengthSqr() < 1.0e-6) continue;
            var rotation = craft.orientation(1.0f);
            Vec3 impulse = Hull.rotate(rotation, force).scale(TICK);
            Vec3 point = craft.position().add(Hull.rotate(rotation, drive.at()));
            NativePhysics.impulseAt(world, body, impulse.x, impulse.y, impulse.z, point.x, point.y, point.z);
        }
    }

    int count() {
        return drives.size();
    }
}
