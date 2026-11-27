package dev.aerolith;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The survival way to make a craft. Use it to turn the structure it is part of into one: every block that touches it,
 * and the blocks touching those, within its range. Use it with an empty hand while sneaking to choose the range. Used
 * again once it is part of a craft, it turns the craft back into blocks.
 */
final class PhysicsAssemblerBlock extends Block {
    /** Blocks it reaches on each axis from itself, for each setting. 50 is a cube of just over a million blocks. */
    static final int[] RADII = {1, 2, 4, 8, 16, 32, 50};
    static final IntegerProperty RANGE = IntegerProperty.create("range", 0, RADII.length - 1);
    private static final VoxelShape SHAPE = Shapes.or(box(0, 0, 0, 16, 4, 16), box(4, 4, 4, 12, 13, 12),
            box(0, 4, 0, 3, 14, 3), box(13, 4, 0, 16, 14, 3), box(0, 4, 13, 3, 14, 16), box(13, 4, 13, 16, 14, 16));

    PhysicsAssemblerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(RANGE, 2));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(RANGE);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer who) || who.isSpectator()) return InteractionResult.PASS;
        if (Plots.isPlot(level) && (level.dimension() == Plots.DIMENSION || Plots.inGrid(pos))) {
            // Part of a craft: the craft's blocks are in the plot dimension, the craft itself is in the world.
            CraftEntity craft = Plots.craftAt(pos);
            if (craft == null || !(craft.level() instanceof ServerLevel world)) return InteractionResult.PASS;
            StructureTransfer.dock(world, who, craft);
            return InteractionResult.CONSUME;
        }
        if (player.isSecondaryUseActive()) {
            int next = (state.getValue(RANGE) + 1) % RADII.length;
            level.setBlock(pos, state.setValue(RANGE, next), Block.UPDATE_ALL);
            AssemblerItem.message(who, "assembler_range", RADII[next]);
            return InteractionResult.CONSUME;
        }
        StructureTransfer.assembleConnected((ServerLevel) level, who, pos, RADII[state.getValue(RANGE)]);
        return InteractionResult.CONSUME;
    }
}
