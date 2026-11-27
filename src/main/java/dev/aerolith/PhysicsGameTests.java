package dev.aerolith;

import java.util.Random;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/** The survival assembler block, and the index of a hull's boxes. */
public final class PhysicsGameTests {
    private static ServerPlayer player(ServerLevel level) {
        return new FakePlayer(level, new GameProfile(UUID.randomUUID(), "AerolithTest"));
    }

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

    /** Single-block crafts dropped from every height and at every turn onto a raft must never end up inside it. */
    public static void blocksDroppedOnARaftNeverSinkIntoIt(GameTestHelper test) {
        ServerLevel level = test.getLevel();
        ServerPlayer player = player(level);
        for (int x = 0; x <= 11; x++) for (int z = 0; z <= 11; z++) test.setBlock(x, 1, z, Blocks.STONE);
        for (int x = 3; x <= 8; x++) for (int z = 3; z <= 8; z++) test.setBlock(x, 2, z, Blocks.OAK_PLANKS);
        CraftEntity raft = StructureTransfer.assemble(level, player, test.absolutePos(new BlockPos(3, 2, 3)), test.absolutePos(new BlockPos(8, 2, 8)));
        test.assertTrue(raft != null, "The raft was not made");
        Random random = new Random(11);
        java.util.List<CraftEntity> cubes = new java.util.ArrayList<>();
        net.minecraft.world.level.block.Block[] kinds = {Blocks.STONE, Blocks.GOLD_BLOCK, Blocks.OAK_PLANKS, Blocks.WOOL.pick(net.minecraft.world.item.DyeColor.WHITE), Blocks.SMOOTH_STONE_SLAB};
        for (int n = 0; n < 24; n++) {
            // Cubes, flat plates and bars, light and heavy; each in the air at a height of its own so that none is in another's way.
            int sx = 1 + (n % 3 == 1 ? 1 : 0) + (n % 7 == 3 ? 2 : 0), sz = 1 + (n % 4 == 2 ? 2 : 0), sy = n % 5 == 4 ? 2 : 1;
            BlockPos at = new BlockPos(1 + random.nextInt(7), 6 + n * 3, 1 + random.nextInt(7));
            for (int x = 0; x < sx; x++) for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) test.setBlock(at.offset(x, y, z), kinds[n % kinds.length]);
            CraftEntity cube = StructureTransfer.assemble(level, player, test.absolutePos(at), test.absolutePos(at.offset(sx - 1, sy - 1, sz - 1)));
            test.assertTrue(cube != null, "A block was not made into a craft: n=" + n + " " + kinds[n % kinds.length] + " at " + at);
            cubes.add(cube);
        }
        double[] deepest = {0};
        String[] worst = {""};
        test.startSequence().thenExecuteFor(600, () -> {
            var top = raft.getBoundingBox();
            for (CraftEntity cube : cubes) {
                Vec3 c = cube.getBoundingBox().getCenter();
                boolean over = c.x > top.minX && c.x < top.maxX && c.z > top.minZ && c.z < top.maxZ;
                var size = cube.hull().localBounds;
                double sunk = top.maxY - (c.y - Math.min(size.getXsize(), Math.min(size.getYsize(), size.getZsize())) * 0.5);
                if (over && c.y < top.maxY && sunk > deepest[0]) {
                    deepest[0] = sunk;
                    worst[0] = "tick " + test.getTick() + " cube at " + c + " raft " + top + " speed " + cube.speed();
                }
            }
        }).thenExecute(() -> {
            test.assertTrue(deepest[0] < 0.2, "A block sank " + deepest[0] + " m into the raft: " + worst[0]);
            cubes.forEach(CraftEntity::discard);
            raft.discard();
        }).thenSucceed();
    }

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
