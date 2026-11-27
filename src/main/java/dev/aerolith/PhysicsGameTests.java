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

    /** A raft with redstone on it can be made into a craft and back: the wires, the lever and the lamp survive both ways. */
    public static void raftWithRedstoneAssemblesAndDocksBack(GameTestHelper test) {
        ServerLevel level = test.getLevel();
        ServerPlayer player = player(level);
        for (int x = 1; x <= 10; x++) for (int z = 1; z <= 10; z++) test.setBlock(x, 1, z, Blocks.STONE);
        for (int x = 2; x <= 7; x++) for (int z = 2; z <= 7; z++) test.setBlock(x, 2, z, Blocks.OAK_PLANKS);
        for (int x = 3; x <= 6; x++) test.setBlock(x, 3, 4, Blocks.REDSTONE_WIRE);
        test.setBlock(2, 3, 4, Blocks.LEVER.defaultBlockState().setValue(net.minecraft.world.level.block.LeverBlock.FACE,
                net.minecraft.world.level.block.state.properties.AttachFace.FLOOR));
        test.setBlock(7, 3, 4, Blocks.REDSTONE_LAMP);
        CraftEntity craft = StructureTransfer.assemble(level, player, test.absolutePos(new BlockPos(2, 2, 2)), test.absolutePos(new BlockPos(7, 3, 7)));
        test.assertTrue(craft != null, "A raft with redstone dust on it was not assembled");
        test.assertTrue(craft.cells().values().stream().filter(s -> s.is(Blocks.REDSTONE_WIRE)).count() == 4, "The redstone dust did not come along");
        test.startSequence().thenWaitUntil(() -> test.assertTrue(craft.speed() < 0.2 && craft.spin() < 0.2, "The raft has not come to rest")).thenExecute(() -> {
            test.assertTrue(StructureTransfer.dock(level, player, craft), "A raft with redstone on it was not turned back into blocks");
            long wires = 0, levers = 0, lamps = 0;
            for (BlockPos pos : BlockPos.betweenClosed(test.absolutePos(new BlockPos(0, 0, 0)), test.absolutePos(new BlockPos(11, 6, 11)))) {
                var state = level.getBlockState(pos);
                if (state.is(Blocks.REDSTONE_WIRE)) wires++;
                if (state.is(Blocks.LEVER)) levers++;
                if (state.is(Blocks.REDSTONE_LAMP)) lamps++;
            }
            test.assertTrue(wires == 4 && levers == 1 && lamps == 1, "Redstone was lost on the way back: " + wires + " wires, " + levers + " levers, " + lamps + " lamps");
        }).thenSucceed();
    }

    /**
     * Redstone on a craft must not cost a rebuild of its hull: dust, levers and torches have no collision, and a change of
     * their power changes nothing the physics holds. A block with a collision shape still does.
     */
    public static void redstoneOnACraftDoesNotRebuildItsHull(GameTestHelper test) {
        ServerLevel level = test.getLevel();
        ServerPlayer player = player(level);
        for (int x = 1; x <= 10; x++) for (int z = 1; z <= 10; z++) test.setBlock(x, 1, z, Blocks.STONE);
        for (int x = 2; x <= 7; x++) for (int z = 2; z <= 7; z++) test.setBlock(x, 2, z, Blocks.OAK_PLANKS);
        CraftEntity craft = StructureTransfer.assemble(level, player, test.absolutePos(new BlockPos(2, 2, 2)), test.absolutePos(new BlockPos(7, 2, 7)));
        test.assertTrue(craft != null, "no craft");
        Hull[] first = new Hull[1];
        BlockPos[] wire = new BlockPos[1];
        BlockPos[] lever = new BlockPos[1];
        test.startSequence().thenIdle(30).thenExecute(() -> {
            first[0] = craft.hull();
            // Two planks side by side: the lever on one, the dust on the other, so that the dust takes the lever's power.
            BlockPos plank = craft.cells().keySet().stream().sorted().filter(p -> craft.cells().containsKey(p.east())).skip(7).findFirst().orElseThrow();
            wire[0] = plank.above();
            lever[0] = plank.east().above();
            var plot = craft.plotLevel();
            plot.setBlock(craft.plotPos(wire[0]), Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
            plot.setBlock(craft.plotPos(lever[0]), Blocks.LEVER.defaultBlockState().setValue(net.minecraft.world.level.block.LeverBlock.FACE,
                    net.minecraft.world.level.block.state.properties.AttachFace.FLOOR), 3);
        }).thenIdle(3).thenExecute(() -> {
            test.assertTrue(craft.hull() == first[0], "Placing dust and a lever rebuilt the hull");
            test.assertTrue(craft.blocks().stream().anyMatch(b -> b.offset().equals(wire[0]) && b.state().is(Blocks.REDSTONE_WIRE))
                    && craft.blocks().stream().anyMatch(b -> b.offset().equals(lever[0]) && b.state().is(Blocks.LEVER)),
                    "The new redstone blocks cannot be aimed at");
            var plot = craft.plotLevel();
            var state = plot.getBlockState(craft.plotPos(lever[0]));
            ((net.minecraft.world.level.block.LeverBlock) Blocks.LEVER).pull(state, plot, craft.plotPos(lever[0]), null);
        }).thenIdle(3).thenExecute(() -> {
            test.assertTrue(craft.hull() == first[0], "Throwing a lever rebuilt the hull");
            test.assertTrue(craft.plotLevel().getBlockState(craft.plotPos(wire[0])).is(Blocks.REDSTONE_WIRE)
                    && craft.plotLevel().getBlockState(craft.plotPos(wire[0])).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER) > 0,
                    "The dust beside the lever did not take power");
            craft.plotLevel().setBlock(craft.plotPos(wire[0].above()), Blocks.STONE.defaultBlockState(), 3);
        }).thenIdle(3).thenExecute(() -> {
            test.assertTrue(craft.hull() != first[0] && craft.hull().mass > first[0].mass, "A block with a collision shape must rebuild the hull");
            test.assertTrue(craft.blocks().stream().anyMatch(b -> b.offset().equals(wire[0].above()) && b.state().is(Blocks.STONE)), "The stone is not part of the hull");
            craft.discard();
        }).thenSucceed();
    }

    public static void terrainClaimVisitsOnlyChangedTiles(GameTestHelper test) {
        Terrain.Range[] ranges = {null, new Terrain.Range(-2, -3, -4, 2, 2, 1),
                new Terrain.Range(-2, -3, -4, 2, 3, 1), new Terrain.Range(-1, -2, -3, 3, 3, 2),
                new Terrain.Range(2, 2, 1, 2, 2, 1), new Terrain.Range(4, 4, 4, 7, 7, 7)};
        for (Terrain.Range range : ranges) {
            if (range == null) continue;
            for (Terrain.Range other : ranges) {
                var expected = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
                for (int x = range.x1(); x <= range.x2(); x++) {
                    for (int y = range.y1(); y <= range.y2(); y++) {
                        for (int z = range.z1(); z <= range.z2(); z++) {
                            if (other == null || x < other.x1() || x > other.x2() || y < other.y1() || y > other.y2()
                                    || z < other.z1() || z > other.z2()) expected.add(BlockPos.asLong(x, y, z));
                        }
                    }
                }
                range.forEachOutside(other, key -> test.assertTrue(expected.remove(key), "Duplicate or shared terrain tile visited"));
                test.assertTrue(expected.isEmpty(), "Some tiles outside the shared range were missed");
            }
        }
        test.succeed();
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
