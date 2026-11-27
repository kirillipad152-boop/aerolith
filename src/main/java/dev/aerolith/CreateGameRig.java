package dev.aerolith;

import java.util.List;

import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** The Create side of {@link CreateGameTests}: kept apart so that the tests load without Create. */
final class CreateGameRig {
    private CreateGameRig() {}

    /** A block of Create by name; the registry is used so that nothing of Create's registration code is needed to compile. */
    private static BlockState create(String name) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", name)).defaultBlockState();
    }

    private static void motor(GameTestHelper test, BlockPos at, int rpm) {
        test.setBlock(at, create("creative_motor").setValue(BlockStateProperties.FACING, Direction.UP));
        BlockEntityBehaviour.get(test.getLevel(), test.absolutePos(at), ScrollValueBehaviour.TYPE).setValue(rpm);
    }

    /** A motor with a fan on top of it, as a craft. */
    static CraftEntity fanCraft(GameTestHelper test, ServerPlayer player, BlockPos motor, int rpm) {
        motor(test, motor, rpm);
        test.setBlock(motor.above(), create("encased_fan").setValue(BlockStateProperties.FACING, Direction.UP));
        return StructureTransfer.assemble(test.getLevel(), player, test.absolutePos(motor), test.absolutePos(motor.above()));
    }

    /** A motor, a bearing on it, and a cross of slime on the bearing: a propeller turning about a vertical axis. */
    static CraftEntity propellerCraft(GameTestHelper test, ServerPlayer player, BlockPos motor, int rpm) {
        motor(test, motor, rpm);
        BlockPos bearing = motor.above();
        test.setBlock(bearing, create("mechanical_bearing").setValue(BlockStateProperties.FACING, Direction.UP));
        BlockPos hub = bearing.above();
        test.setBlock(hub, Blocks.OAK_PLANKS);
        for (int arm = 1; arm <= 2; arm++) {
            // Create does not carry neighbours along on its own; slime does.
            test.setBlock(hub.east(arm), Blocks.SLIME_BLOCK);
            test.setBlock(hub.west(arm), Blocks.SLIME_BLOCK);
        }
        return StructureTransfer.assemble(test.getLevel(), player, test.absolutePos(motor.west(2)), test.absolutePos(hub.east(2)));
    }

    /**
     * A windmill bearing carrying nine wool sails, held together by slime (Create needs at least eight sails, and does not carry
     * neighbours along on its own). The windmill has no motor: it turns by itself.
     */
    static CraftEntity windmillCraft(GameTestHelper test, ServerPlayer player, BlockPos bearing) {
        test.setBlock(bearing, create("windmill_bearing").setValue(BlockStateProperties.FACING, Direction.UP));
        BlockPos hub = bearing.above();
        int[][] arms = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] arm : arms) {
            test.setBlock(hub.offset(arm[0], 0, arm[1]), Blocks.SLIME_BLOCK);
            test.setBlock(hub.offset(arm[0], 1, arm[1]), Blocks.WHITE_WOOL);
        }
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) test.setBlock(hub.offset(dx, 0, dz), Blocks.WHITE_WOOL);
        }
        return StructureTransfer.assemble(test.getLevel(), player, test.absolutePos(bearing.offset(-1, 0, -1)), test.absolutePos(hub.offset(1, 1, 1)));
    }

    /** What a player does with a click or a redstone pulse: the bearing takes what is on it and starts turning. */
    static void startBearing(CraftEntity craft) {
        for (long key : craft.chunkKeys()) {
            var chunk = PlotChunks.find(craft.plotLevel(), net.minecraft.world.level.ChunkPos.getX(key), net.minecraft.world.level.ChunkPos.getZ(key));
            if (chunk == null) continue;
            for (var entity : chunk.getBlockEntities().values()) {
                if (entity instanceof MechanicalBearingBlockEntity bearing) bearing.assemble();
            }
        }
    }

    /** The pushes the craft's drives make now, in the craft's frame. */
    static List<net.minecraft.world.phys.Vec3> forces(CraftEntity craft) {
        return CreatePropulsion.scan(craft).stream().map(Propulsion.Drive::force).toList();
    }

    /** Whether the bearing on board has taken the planks into a contraption. */
    static boolean bearingRuns(CraftEntity craft) {
        var plot = craft.plotLevel();
        for (long key : craft.chunkKeys()) {
            var chunk = PlotChunks.find(plot, net.minecraft.world.level.ChunkPos.getX(key), net.minecraft.world.level.ChunkPos.getZ(key));
            if (chunk == null) continue;
            for (var entity : chunk.getBlockEntities().values()) {
                if (entity instanceof MechanicalBearingBlockEntity bearing && bearing.getAngularSpeed() != 0
                        && !plot.getEntitiesOfClass(com.simibubi.create.content.contraptions.ControlledContraptionEntity.class,
                                new net.minecraft.world.phys.AABB(bearing.getBlockPos()).inflate(3), bearing::isAttachedTo).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** What the bearings on board make of their contraptions, for a failing test to print. */
    static String describeBearings(CraftEntity craft) {
        var plot = craft.plotLevel();
        StringBuilder out = new StringBuilder();
        for (long key : craft.chunkKeys()) {
            var chunk = PlotChunks.find(plot, net.minecraft.world.level.ChunkPos.getX(key), net.minecraft.world.level.ChunkPos.getZ(key));
            if (chunk == null) continue;
            for (var entity : chunk.getBlockEntities().values()) {
                if (entity instanceof MechanicalBearingBlockEntity bearing) {
                    var near = plot.getEntitiesOfClass(com.simibubi.create.content.contraptions.ControlledContraptionEntity.class,
                            new net.minecraft.world.phys.AABB(bearing.getBlockPos()).inflate(3), bearing::isAttachedTo);
                    out.append("bearing speed=").append(bearing.getSpeed()).append(" angular=").append(bearing.getAngularSpeed())
                            .append(" contraptions=").append(near.size());
                    for (var c : near) {
                        out.append(" stalled=").append(c.isStalled()).append(" blocks=").append(c.getContraption().getBlocks().keySet());
                    }
                }
            }
        }
        return out.toString();
    }
}
