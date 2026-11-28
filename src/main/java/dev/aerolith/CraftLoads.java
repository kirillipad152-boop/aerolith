package dev.aerolith;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * What entities standing on a craft do to it. Each server tick a supported entity pushes down with its weight at its
 * feet, and its acceleration relative to the deck is returned to the craft as the opposite impulse. Both are applied at
 * the contact point, so standing near an edge tilts the craft.
 */
final class CraftLoads {
    private static final double DT = 0.05;
    private static final double GRAVITY = 9.81;
    private static final double PLAYER_MASS = 70.0;
    private static final double LIVING_DENSITY = 108.0;
    // Walking is bounded to a few m/s per tick of relative acceleration so one lost packet cannot kick the hull.
    private static final double MAX_RELATIVE_DV = 2.0;
    private static final double MAX_TRACTION_G = 0.55;
    private static final double SMOOTHING = 0.5;
    /** Less than this per tick, in metres, is not moving. */
    private static final double STILL = 0.004;

    private record Track(Vec3 local, Vec3 velocity) {}

    private final Map<UUID, Track> tracks = new HashMap<>();

    /** Largest impulse per tick that something can push sideways with: its weight times a friction-like 0.8. */
    static double pushImpulse(Entity entity) {
        return 0.8 * mass(entity) * GRAVITY * DT;
    }

    static double mass(Entity entity) {
        return entity instanceof Player ? PLAYER_MASS
                : LIVING_DENSITY * entity.getBbWidth() * entity.getBbWidth() * entity.getBbHeight();
    }

    boolean supports(Entity entity) {
        return tracks.containsKey(entity.getUUID());
    }

    /** Standing still on a craft at rest changes nothing: its weight was already carried when the craft came to rest. */
    void apply(CraftEntity craft, long world, long body, boolean asleep) {
        if (asleep && tracks.isEmpty() && !HullCollisions.mayBeOccupied(craft)) return;
        var supported = HullCollisions.supported(craft);
        if (supported.isEmpty() && tracks.isEmpty()) return;
        Quaternionf rot = craft.orientation(1);
        Quaternionf invRot = new Quaternionf(rot).conjugate();
        Vec3 worldOmega = craft.angularVelocity();
        Vec3 localOmega = Hull.rotate(invRot, worldOmega);
        Vec3 worldCom = craft.worldCenterOfMass();
        double spin = craft.spin();

        Set<UUID> present = new HashSet<>();
        for (HullCollisions.Support support : supported) {
            Entity entity = support.entity();
            present.add(entity.getUUID());
            double mass = mass(entity);
            Vec3 impulse = new Vec3(0, -mass * GRAVITY * DT, 0);

            Vec3 local = HullCollisions.localPoint(craft, entity.position());
            Track before = tracks.get(entity.getUUID());
            Vec3 lagExpectedVel = localOmega.cross(local);
            double lagSpeed = lagExpectedVel.length();
            // Client-reported player poses lag 1-2 ticks behind server craft rotation, which otherwise reads as
            // backward slip (-omega x r) and creates positive-feedback runaway spin.
            double stillThreshold = STILL + (entity instanceof Player ? lagSpeed * DT * 2.5 : lagSpeed * DT * 0.5);
            boolean still = before != null && local.distanceToSqr(before.local) <= stillThreshold * stillThreshold;

            Vec3 velocity = Vec3.ZERO;
            if (before != null) {
                Vec3 displacement = still ? Vec3.ZERO : local.subtract(before.local);
                if (!still && lagSpeed > 1.0e-6) {
                    Vec3 lagDir = lagExpectedVel.scale(-1.0 / lagSpeed);
                    double backwardAlongSpin = displacement.dot(lagDir);
                    if (backwardAlongSpin > 0.0) {
                        double suppress = Math.min(backwardAlongSpin, stillThreshold);
                        displacement = displacement.subtract(lagDir.scale(suppress));
                    }
                }
                Vec3 raw = displacement.scale(1.0 / DT);
                velocity = before.velocity.scale(SMOOTHING).add(raw.scale(1.0 - SMOOTHING));
                Vec3 change = velocity.subtract(before.velocity);
                if (still) {
                    // Gently decay stored walk velocity when still so stopping does not inject a residual rotational kick.
                    velocity = Vec3.ZERO;
                    change = Vec3.ZERO;
                } else {
                    if (change.length() > MAX_RELATIVE_DV) change = change.scale(MAX_RELATIVE_DV / change.length());
                    Vec3 reaction = Hull.rotate(rot, change.scale(-mass));
                    double maxTraction = MAX_TRACTION_G * mass * GRAVITY * DT;
                    if (reaction.length() > maxTraction) reaction = reaction.scale(maxTraction / reaction.length());
                    // Guard against pumping existing spin on a rotating deck due to client/server interpolation mismatch.
                    if (spin > 1.0e-4) {
                        Vec3 arm = support.point().subtract(worldCom);
                        Vec3 torqueAxis = arm.cross(reaction);
                        double angularPower = torqueAxis.dot(worldOmega);
                        if (angularPower > 0.0) {
                            Vec3 spinTangent = worldOmega.cross(arm);
                            double tangentSq = spinTangent.lengthSqr();
                            if (tangentSq > 1.0e-8) {
                                double pump = reaction.dot(spinTangent) / tangentSq;
                                if (pump > 0.0) reaction = reaction.subtract(spinTangent.scale(pump));
                            }
                        }
                    }
                    impulse = impulse.add(reaction);
                }
            }
            tracks.put(entity.getUUID(), new Track(local, velocity));
            NativePhysics.loadAt(world, body, impulse.x, impulse.y, impulse.z,
                    support.point().x, support.point().y, support.point().z, still);
        }
        if (tracks.keySet().retainAll(present) && asleep) NativePhysics.wake(world, body);
    }
}
