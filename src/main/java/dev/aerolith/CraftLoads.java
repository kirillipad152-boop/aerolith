package dev.aerolith;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Mobs press down and transfer their footsteps at the support point. Players are carried but never load the hull.
 */
final class CraftLoads {
    private static final double DT = 0.05;
    private static final double GRAVITY = 9.81;
    private static final double LIVING_DENSITY = 108.0;
    // Walking is bounded to a few m/s per tick of relative acceleration so one lost packet cannot kick the hull.
    private static final double MAX_RELATIVE_DV = 2.0;
    private static final double SMOOTHING = 0.5;
    /** Less than this per tick, in metres, is not moving. */
    private static final double STILL = 0.002;

    private record Track(Vec3 local, Vec3 velocity) {}

    private final Map<UUID, Track> tracks = new HashMap<>();

    /** Largest impulse per tick that something can push sideways with: its weight times a friction-like 0.8. */
    static double pushImpulse(Entity entity) {
        return 0.8 * mass(entity) * GRAVITY * DT;
    }

    static double mass(Entity entity) {
        return LIVING_DENSITY * entity.getBbWidth() * entity.getBbWidth() * entity.getBbHeight();
    }

    /** Standing still on a craft at rest changes nothing: its weight was already carried when the craft came to rest. */
    void apply(CraftEntity craft, long world, long body, boolean asleep) {
        if (asleep && tracks.isEmpty() && craft.touchedAt < craft.level().getGameTime() - 2) return;
        var supported = HullCollisions.supportedMobs(craft);
        if (supported.isEmpty() && tracks.isEmpty()) return;
        Set<UUID> present = new HashSet<>();
        for (HullCollisions.Support support : supported) {
            Entity entity = support.entity();
            present.add(entity.getUUID());
            double mass = mass(entity);
            Vec3 impulse = new Vec3(0, -mass * GRAVITY * DT, 0);

            Vec3 local = HullCollisions.localPoint(craft, entity.position());
            Track before = tracks.get(entity.getUUID());
            boolean still = before != null && local.distanceToSqr(before.local) < STILL * STILL;
            Vec3 velocity = Vec3.ZERO;
            if (before != null) {
                // Local coordinates ignore the deck's own motion, so only the entity's walking shows up here.
                Vec3 raw = local.subtract(before.local).scale(1.0 / DT);
                velocity = before.velocity.scale(SMOOTHING).add(raw.scale(1.0 - SMOOTHING));
                Vec3 change = velocity.subtract(before.velocity);
                if (change.length() > MAX_RELATIVE_DV) change = change.scale(MAX_RELATIVE_DV / change.length());
                impulse = impulse.add(Hull.rotate(craft.orientation(1), change.scale(-mass)));
            }
            tracks.put(entity.getUUID(), new Track(local, velocity));
            NativePhysics.loadAt(world, body, impulse.x, impulse.y, impulse.z,
                    support.point().x, support.point().y, support.point().z, still);
        }
        if (tracks.keySet().retainAll(present) && asleep) NativePhysics.wake(world, body);
    }
}
