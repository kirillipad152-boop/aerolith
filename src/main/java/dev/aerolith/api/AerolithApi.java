package dev.aerolith.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;
import dev.aerolith.CraftEntity;
import dev.aerolith.InternalApi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Primary public entry point for external mods integrating with Aerolith physics crafts.
 */
public final class AerolithApi {
    private AerolithApi() {}

    /** Wraps an existing {@link CraftEntity} into its public {@link CraftHandle}, if valid. */
    public static Optional<CraftHandle> asCraft(@Nullable CraftEntity entity) {
        return entity == null ? Optional.empty() : Optional.of(InternalApi.handle(entity));
    }

    /** Looks up a craft entity by entity ID in {@code level}. */
    public static Optional<CraftHandle> getCraft(Level level, int entityId) {
        Objects.requireNonNull(level, "level");
        return level.getEntity(entityId) instanceof CraftEntity craft
                ? Optional.of(InternalApi.handle(craft))
                : Optional.empty();
    }

    /** Returns all tracked crafts in {@code level}. */
    public static List<CraftHandle> getCrafts(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        return InternalApi.crafts(level);
    }

    /** Queries all active crafts intersecting {@code worldBox} using the 16x16x16 section spatial index. */
    public static List<CraftHandle> getCraftsInBox(Level level, AABB worldBox) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(worldBox, "worldBox");
        return InternalApi.craftsInBox(level, worldBox);
    }

    /** Casts an exact shape ray against all crafts along the path using the spatial section index. */
    public static Optional<CraftHit> raycast(
            Level level, Vec3 origin, Vec3 direction, double maxDistance, boolean includeFluids
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(direction, "direction");
        if (direction.lengthSqr() < 1.0e-12 || maxDistance <= 0.0) return Optional.empty();
        return Optional.ofNullable(InternalApi.raycast(level, origin, direction.normalize(), maxDistance, includeFluids));
    }

    /** Assembles all connected or boxed blocks inside {@code [from, to]} into a physical craft. */
    public static Optional<CraftHandle> assemble(
            ServerLevel level, BlockPos from, BlockPos to, @Nullable ServerPlayer actor
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        return Optional.ofNullable(InternalApi.assemble(level, from, to, actor));
    }

    /** Flood-assembles face-connected solid blocks around {@code seed} within {@code range} blocks per axis. */
    public static Optional<CraftHandle> assembleConnected(
            ServerLevel level, BlockPos seed, int range, @Nullable ServerPlayer actor
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(seed, "seed");
        if (range < 0) throw new IllegalArgumentException("range must be >= 0");
        return Optional.ofNullable(InternalApi.assembleConnected(level, seed, range, actor));
    }

    /** Programmatically creates a craft directly from local offset -> {@link BlockState} entries at {@code worldOrigin}. */
    public static Optional<CraftHandle> createFromBlocks(
            ServerLevel level, Map<BlockPos, BlockState> blocks, Vec3 worldOrigin, @Nullable UUID owner
    ) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(blocks, "blocks");
        Objects.requireNonNull(worldOrigin, "worldOrigin");
        if (blocks.isEmpty()) return Optional.empty();
        return Optional.ofNullable(InternalApi.createFromBlocks(level, blocks, worldOrigin, owner));
    }

    /** Docks a near-stationary craft back into world blocks. */
    public static boolean disassemble(ServerLevel level, CraftHandle craft, @Nullable ServerPlayer actor) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(craft, "craft");
        return InternalApi.disassemble(level, craft, actor);
    }

    /** Sets dimension wind velocity in m/s. */
    public static void setWind(ServerLevel level, Vec3 windMetersPerSecond) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(windMetersPerSecond, "windMetersPerSecond");
        InternalApi.setWind(level, windMetersPerSecond);
    }

    /** Returns the current dimension wind velocity in m/s. */
    public static Vec3 getWind(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        return InternalApi.getWind(level);
    }

    /** Number of loaded crafts tracked in {@code level}'s physics world. */
    public static int getCraftCount(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        return InternalApi.craftCount(level);
    }

    /** Remaining body slots available before reaching the dimension body cap. */
    public static int getRemainingCapacity(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        return InternalApi.remainingCapacity(level);
    }
}
