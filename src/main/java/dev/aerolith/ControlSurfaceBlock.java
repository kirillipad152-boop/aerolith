package dev.aerolith;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A wing that moves. Like a wing it is a flat metre of foil whose chord runs along the craft's Z axis; unlike one, the
 * pilot's controls turn its trailing edge: an <b>elevator</b> pitches the craft, a <b>rudder</b> yaws it, <b>ailerons</b>
 * roll it, and <b>flaps</b> all go down together at the pilot's lever for more lift (and drag). Which way each leans is worked
 * out from where it is, so a tail, a canard and a pair of ailerons on both wings all answer the same key correctly.
 * Placed against a side face it stands upright, as a rudder does; use it with an empty hand to turn it.
 */
final class ControlSurfaceBlock extends Block {
    /** The kinds of foil the physics engine knows, after the plain wing's 1. */
    static final int ELEVATOR = 3, RUDDER = 4, AILERON = 5, FLAP = 6;
    /** The axis its normal lies on: Y is a flat surface, X an upright one. The chord is always Z. */
    static final EnumProperty<Direction.Axis> NORMAL = EnumProperty.create("normal", Direction.Axis.class, Direction.Axis.X, Direction.Axis.Y);
    private static final MapCodec<ControlSurfaceBlock> CODEC = simpleCodec(properties -> new ControlSurfaceBlock(properties, FLAP));
    private static final VoxelShape FLAT = box(0, 7, 0, 16, 9, 16);
    private static final VoxelShape UPRIGHT = box(7, 0, 0, 9, 16, 16);

    private final int kind;

    ControlSurfaceBlock(Properties properties, int kind) {
        super(properties);
        this.kind = kind;
        registerDefaultState(stateDefinition.any().setValue(NORMAL, Direction.Axis.Y));
    }

    int kind() {
        return kind;
    }

    /** The direction the foil's lift points in when its trailing edge is down. */
    static Direction normal(BlockState state) {
        return state.getValue(NORMAL) == Direction.Axis.X ? Direction.EAST : Direction.UP;
    }

    @Override
    protected MapCodec<ControlSurfaceBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORMAL);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction.Axis face = context.getClickedFace().getAxis();
        return defaultBlockState().setValue(NORMAL, face == Direction.Axis.X ? Direction.Axis.X : Direction.Axis.Y);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(NORMAL) == Direction.Axis.X ? UPRIGHT : FLAT;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.getAbilities().mayBuild) return InteractionResult.PASS;
        if (!level.isClientSide) {
            Direction.Axis turned = state.getValue(NORMAL) == Direction.Axis.X ? Direction.Axis.Y : Direction.Axis.X;
            level.setBlock(pos, state.setValue(NORMAL, turned), Block.UPDATE_ALL);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
