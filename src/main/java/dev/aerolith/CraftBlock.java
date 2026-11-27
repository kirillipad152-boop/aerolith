package dev.aerolith;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

record CraftBlock(BlockPos offset, BlockState state) {}
