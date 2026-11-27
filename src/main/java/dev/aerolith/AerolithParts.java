package dev.aerolith;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class AerolithParts {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Aerolith.ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Aerolith.ID);

    public static final DeferredBlock<Block> SEAT = BLOCKS.registerSimpleBlock("pilot_seat",
            props -> props.mapColor(MapColor.COLOR_RED).strength(1.0f, 3.0f).sound(SoundType.WOOD));
    public static final DeferredBlock<DirectionalBlock> THRUSTER = BLOCKS.registerBlock("thruster", ThrusterBlock::new,
            props -> props.mapColor(MapColor.COLOR_ORANGE).strength(1.5f, 6.0f).sound(SoundType.COPPER));
    public static final DeferredBlock<Block> WING = BLOCKS.registerBlock("wing", WingBlock::new,
            props -> props.mapColor(MapColor.SAND).strength(0.8f, 3.0f).sound(SoundType.WOOD));

    /** The pilot's stick moves these: pitch, yaw and roll surfaces, and the flaps that one lever sets. */
    public static final DeferredBlock<ControlSurfaceBlock> ELEVATOR = controlSurface("elevator", ControlSurfaceBlock.ELEVATOR);
    public static final DeferredBlock<ControlSurfaceBlock> RUDDER = controlSurface("rudder", ControlSurfaceBlock.RUDDER);
    public static final DeferredBlock<ControlSurfaceBlock> AILERON = controlSurface("aileron", ControlSurfaceBlock.AILERON);
    public static final DeferredBlock<ControlSurfaceBlock> FLAP = controlSurface("flap", ControlSurfaceBlock.FLAP);

    public static final DeferredBlock<PhysicsAssemblerBlock> PHYSICS_ASSEMBLER = BLOCKS.registerBlock("physics_assembler",
            PhysicsAssemblerBlock::new, props -> props.mapColor(MapColor.METAL).strength(3.0f, 6.0f).sound(SoundType.METAL));

    private static DeferredBlock<ControlSurfaceBlock> controlSurface(String name, int kind) {
        return BLOCKS.registerBlock(name, properties -> new ControlSurfaceBlock(properties, kind),
                props -> props.mapColor(MapColor.SAND).strength(0.8f, 3.0f).sound(SoundType.WOOD).noOcclusion());
    }

    static {
        ITEMS.registerSimpleBlockItem(ELEVATOR);
        ITEMS.registerSimpleBlockItem(RUDDER);
        ITEMS.registerSimpleBlockItem(AILERON);
        ITEMS.registerSimpleBlockItem(FLAP);
        ITEMS.registerSimpleBlockItem(PHYSICS_ASSEMBLER);
        ITEMS.registerSimpleBlockItem(SEAT);
        ITEMS.registerSimpleBlockItem(THRUSTER);
        ITEMS.registerSimpleBlockItem(WING);
    }

    private AerolithParts() {}

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
    }

    public static void creativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(PHYSICS_ASSEMBLER.get());
            event.accept(SEAT.get());
            event.accept(THRUSTER.get());
            event.accept(WING.get());
            event.accept(ELEVATOR.get());
            event.accept(RUDDER.get());
            event.accept(AILERON.get());
            event.accept(FLAP.get());
        }
    }

    private static final class ThrusterBlock extends DirectionalBlock {

        private ThrusterBlock(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING);
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            return defaultBlockState().setValue(FACING, context.getClickedFace());
        }

        @Override
        protected BlockState rotate(BlockState state, Rotation rotation) {
            return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
        }

        @Override
        protected BlockState mirror(BlockState state, Mirror mirror) {
            return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
        }
    }

    private static final class WingBlock extends Block {
        private static final VoxelShape PLATE = box(0, 7, 0, 16, 9, 16);

        private WingBlock(Properties properties) {
            super(properties);
        }

        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return PLATE;
        }
    }
}
