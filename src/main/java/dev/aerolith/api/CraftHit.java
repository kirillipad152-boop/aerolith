package dev.aerolith.api;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Exact shape raycast intersection against a block inside a physical craft.
 */
public record CraftHit(
        CraftHandle craft,
        BlockPos localOffset,
        BlockState state,
        Direction face,
        Vec3 localHit,
        Vec3 worldHit,
        double distance
) {}
