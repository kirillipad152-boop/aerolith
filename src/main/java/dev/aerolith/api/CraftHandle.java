package dev.aerolith.api;

import java.util.UUID;

import javax.annotation.Nullable;
import dev.aerolith.CraftEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Public handle for inspecting, editing, and controlling an assembled Aerolith {@link CraftEntity}.
 */
public interface CraftHandle {
    int id();

    UUID uuid();

    @Nullable UUID owner();

    CraftEntity entity();

    Level level();

    boolean isValid();

    boolean isSimulated();

    Vec3 position();

    Vec3 centerOfMass();

    Quaternionf orientation();

    Vec3 linearVelocity();

    Vec3 angularVelocity();

    double speed();

    double spin();

    double mass();

    double boundingRadius();

    /** Total volume (m³) of sealed interior air pockets inside the hull. */
    double sealedCavityVolume();

    AABB localBounds();

    int blockCount();

    @Nullable BlockState getBlockState(BlockPos localOffset);

    Vec3 localToWorld(Vec3 localPoint);

    Vec3 worldToLocal(Vec3 worldPoint);

    boolean isAnchored();

    void setAnchored(boolean anchored);

    /** Wakes a sleeping body in the native physics engine (server-only). */
    boolean wake();

    /** Applies a world-space linear impulse (N·s) at the center of mass (server-only). */
    boolean applyImpulse(Vec3 impulse);

    /** Applies a world-space impulse (N·s) at a world-space point (server-only). */
    boolean applyImpulseAt(Vec3 impulse, Vec3 worldPoint);

    /** Queues a tick-distributed load impulse (N·s) at a world-space point (server-only). */
    boolean queueLoad(Vec3 impulse, Vec3 worldPoint);

    /** Sets engine throttle in {@code [0.0, 1.0]} (server-only). */
    boolean setThrottle(double throttle);

    /** Sets flight control surfaces pitch/yaw/roll targets in {@code [-1.0, 1.0]} (server-only). */
    boolean setControls(double pitch, double yaw, double roll);

    /** Sets flap extension in {@code [0.0, 1.0]} (server-only). */
    boolean setFlaps(double flaps);

    /** Replaces or removes a block at {@code localOffset} in the craft plot and rebuilds if needed (server-only). */
    boolean setBlock(BlockPos localOffset, BlockState state);

    /** Breaks all remaining blocks with drops and removes this craft (server-only). */
    void dissolveWithDrops();
}
