package dev.aerolith;

import java.util.Random;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The survival assembler block, and the index of a hull's boxes. */
@GameTestHolder(Aerolith.ID)
@PrefixGameTestTemplate(false)
public final class PhysicsGameTests {
    private static ServerPlayer player(ServerLevel level) {
        return new FakePlayer(level, new GameProfile(UUID.randomUUID(), "AerolithTest"));
    }

    @GameTest(template = "test_pad", timeoutTicks = 300)
    public static void physicsAssemblerBlockMakesSetsRangeAndUnmakesACraft(GameTestHelper test) {
        ServerLevel level = test.getLevel();
        ServerPlayer player = player(level);
        for (int x = 2; x <= 8; x++) {
            for (int z = 2; z <= 8; z++) test.setBlock(x, 1, z, Blocks.STONE);
        }
        BlockPos at = new BlockPos(5, 4, 5);
        test.setBlock(at, AerolithParts.PHYSICS_ASSEMBLER.get());
        test.setBlock(at.east(), Blocks.OAK_PLANKS);
        test.setBlock(at.west(), Blocks.OAK_PLANKS);
        test.setBlock(at.above(), Blocks.OAK_PLANKS);
        // Not touching: must stay where it is.
        test.setBlock(new BlockPos(8, 4, 8), Blocks.STONE);
        BlockPos absolute = test.absolutePos(at);
        player.setShiftKeyDown(true);
        var state = level.getBlockState(absolute);
        state.useWithoutItem(level, player, new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, false));
        test.assertTrue(level.getBlockState(absolute).getValue(PhysicsAssemblerBlock.RANGE) == 3, "Sneak-use must widen the range one step");
        level.setBlockAndUpdate(absolute, level.getBlockState(absolute).setValue(PhysicsAssemblerBlock.RANGE, 2));
        player.setShiftKeyDown(false);
        CraftEntity[] made = new CraftEntity[1];
        test.startSequence().thenExecute(() -> {
            level.getBlockState(absolute).useWithoutItem(level, player, new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, false));
            var found = level.getEntities(Aerolith.CRAFT.get(), new AABB(absolute).inflate(4), craft -> true);
            test.assertTrue(found.size() == 1 && found.getFirst().blocks().size() == 4,
                    "The assembler must make one craft of its four connected blocks, got " + found.size());
            made[0] = found.getFirst();
            test.assertTrue(level.getBlockState(absolute).isAir(), "The assembler block stayed in the world");
            test.assertTrue(level.getBlockState(test.absolutePos(new BlockPos(8, 4, 8))).is(Blocks.STONE), "A block that does not touch it was taken");
        }).thenIdle(60).thenExecute(() -> {
            CraftBlock block = made[0].blocks().stream().filter(b -> b.state().is(AerolithParts.PHYSICS_ASSEMBLER.get())).findFirst().orElseThrow();
            BlockPos plotPos = made[0].plotPos(block.offset());
            var plot = made[0].plotLevel();
            plot.getBlockState(plotPos).useWithoutItem(plot, player, new BlockHitResult(Vec3.atCenterOf(plotPos), Direction.UP, plotPos, false));
            test.assertTrue(made[0].isRemoved(), "Using the assembler on board must turn the craft back into blocks");
            boolean back = false;
            for (BlockPos pos : BlockPos.betweenClosed(absolute.offset(-3, -3, -3), absolute.offset(3, 3, 3))) {
                back |= level.getBlockState(pos).is(AerolithParts.PHYSICS_ASSEMBLER.get());
            }
            test.assertTrue(back, "The assembler block did not come back");
        }).thenSucceed();
    }

    @GameTest(template = "test_pad")
    public static void boxIndexFindsExactlyWhatAScanFinds(GameTestHelper test) {
        Random random = new Random(7);
        var boxes = new java.util.ArrayList<AABB>();
        for (int i = 0; i < 4000; i++) {
            double x = random.nextInt(60) - 30, y = random.nextInt(60) - 30, z = random.nextInt(60) - 30;
            boolean slab = random.nextBoolean();
            boxes.add(new AABB(x, y, z, x + 1, y + (slab ? 0.5 : 1), z + 1));
        }
        BoxIndex index = new BoxIndex(boxes);
        for (int q = 0; q < 300; q++) {
            double x = random.nextDouble() * 80 - 40, y = random.nextDouble() * 80 - 40, z = random.nextDouble() * 80 - 40;
            double s = q % 10 == 0 ? 90 : random.nextDouble() * 6;
            AABB region = new AABB(x, y, z, x + s, y + random.nextDouble() * 6, z + random.nextDouble() * s);
            var expected = new java.util.TreeSet<Integer>();
            for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).intersects(region)) expected.add(i);
            var found = new java.util.TreeSet<Integer>(index.near(region));
            test.assertTrue(found.equals(expected), "Query " + q + " found " + found.size() + " boxes, a scan " + expected.size());
        }
        test.succeed();
    }
}
