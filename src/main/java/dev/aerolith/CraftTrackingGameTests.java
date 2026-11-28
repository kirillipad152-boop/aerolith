package dev.aerolith;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

final class CraftTrackingGameTests {
    static void trackingRestoresCraftResources(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos here = test.absolutePos(new BlockPos(5, 7, 5));
        // Keep the whole one-block craft's terrain sweep within its ticking chunk.
        Vec3 origin = Vec3.atCenterOf(ChunkPos.containing(here).getMiddleBlockPosition(here.getY()));
        CraftEntity craft = StructureTransfer.create(level,
                List.of(new CraftBlock(BlockPos.ZERO, Blocks.OAK_PLANKS.defaultBlockState())), origin, UUID.randomUUID());
        test.assertTrue(craft != null, "Could not create the test craft");
        var physics = WorldPhysics.of(level);
        try {
            long key = craft.chunkKeys().iterator().nextLong();
            var manager = craft.plotLevel().getChunkSource().chunkMap.getDistanceManager();
            AABB box = craft.getBoundingBox();
            test.assertTrue(level.getEntity(craft.getId()) == craft && physics.isSimulated(craft),
                    "Initial tracking and physics binding failed");
            test.assertTrue(PlotChunks.ticks(manager, key), "Initial plot chunk is not ticking");

            // Moving out and back before unload makes the real section manager stop and restart tracking the same instance.
            craft.setPos(origin.add(-100_000, 0, 0));
            test.assertTrue(!craft.isRemoved() && level.getEntity(craft.getId()) == null && !craft.isAddedToLevel(),
                    "The craft should be hidden, not removed");
            test.assertTrue(Plots.craftAt(craft.plotOrigin()) == null && !PlotChunks.ticks(manager, key),
                    "Hiding the craft did not release its plot");
            test.assertFalse(physics.sections.anyNear(box, candidate -> candidate == craft) || physics.isSimulated(craft),
                    "Hiding the craft left stale collision or physics entries");

            craft.setPos(origin);
            test.assertTrue(level.getEntity(craft.getId()) == craft && craft.isAddedToLevel(),
                    "Tracking did not restore the same craft");
            test.assertTrue(Plots.craftAt(craft.plotOrigin()) == craft && PlotChunks.ticks(manager, key),
                    "Tracking did not restore the plot owner and ticking chunks");
            test.assertTrue(craft.plotLevel().getBlockState(craft.plotOrigin()).is(Blocks.OAK_PLANKS),
                    "The restored plot lost its plank");
            test.assertTrue(physics.sections.anyNear(box, candidate -> candidate == craft),
                    "Tracking did not restore the collision index");
        } catch (RuntimeException | Error failure) {
            craft.discard();
            throw failure;
        }
        test.runBeforeTestEnd(craft::discard);
        test.startSequence().thenWaitUntil(() -> test.assertTrue(physics.isSimulated(craft), "Physics did not reattach the craft"))
                .thenExecute(craft::discard).thenSucceed();
    }

    /** An engine that cannot keep up with the tick, and a stall of the whole server, must not lose or sink a craft. */
    static void craftsSurviveASlowEngineAndAServerStall(GameTestHelper test) {
        var level = test.getLevel();
        for (int x = 1; x <= 9; x++) {
            for (int z = 1; z <= 9; z++) test.setBlock(x, 1, z, Blocks.STONE);
        }
        var plank = Blocks.OAK_PLANKS.defaultBlockState();
        List<CraftBlock> blocks = new java.util.ArrayList<>();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) blocks.add(new CraftBlock(new BlockPos(x, 0, z), plank));
        CraftEntity craft = StructureTransfer.create(level, blocks, test.absoluteVec(new Vec3(5.5, 9, 5.5)), UUID.randomUUID());
        test.assertTrue(craft != null, "Could not create the test craft");
        double floorTop = test.absolutePos(new BlockPos(5, 1, 5)).getY() + 1.0;
        // Steps three times as long as a tick for the first three seconds.
        WorldPhysics.slowStepMs = 150;
        WorldPhysics.slowUntil = System.nanoTime() + 3_000_000_000L;
        test.startSequence().thenExecuteFor(40, () -> {
            test.assertTrue(!craft.isRemoved() && level.getEntity(craft.getId()) == craft, "A craft vanished while the engine was slow");
            test.assertTrue(craft.getY() > floorTop + 0.2, "A craft sank into the floor while the engine was slow: y=" + craft.getY());
        }).thenExecute(() -> {
            // One tick of the server that lasts a second and a half.
            try {
                Thread.sleep(1500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }).thenWaitUntil(() -> {
            test.assertTrue(!craft.isRemoved() && level.getEntity(craft.getId()) == craft, "A craft vanished after a server stall");
            test.assertTrue(Math.abs(craft.getY() - (floorTop + 0.5)) < 0.1 && craft.speed() < 0.25,
                    "The craft did not come to rest on the floor: y=" + craft.getY() + ", speed=" + craft.speed()
                            + ", simulated=" + WorldPhysics.of(level).isSimulated(craft));
        }).thenExecute(craft::discard).thenSucceed();
    }

    static void interpolatedBoundsFollowRenderedPose(GameTestHelper test) {
        Vec3 from = test.absoluteVec(new Vec3(4.5, 7, 4.5));
        var wood = Blocks.OAK_PLANKS.defaultBlockState();
        CraftEntity craft = StructureTransfer.create(test.getLevel(), List.of(
                new CraftBlock(new BlockPos(4, 0, 0), wood), new CraftBlock(new BlockPos(5, 0, 0), wood)), from, UUID.randomUUID());
        test.assertTrue(craft != null, "Could not create the test craft");
        try {
            craft.commonTick();
            Vec3 to = from.add(2, 1, 0);
            Quaternionf rotation = new Quaternionf().rotationY((float) (Math.PI / 2));
            double[] state = craft.nativeState();
            state[0] = to.x;
            state[1] = to.y;
            state[2] = to.z;
            state[3] = rotation.x;
            state[4] = rotation.y;
            state[5] = rotation.z;
            state[6] = rotation.w;
            craft.needsSync = false;
            craft.applyNative(state, 0);
            test.assertTrue(craft.needsSync, "A changed native pose must sync even if its chunk stops ticking");
            craft.updateClientPose();

            // At 45 degrees, the local box's x and z extrema are (x+z)/sqrt(2) and (z-x)/sqrt(2).
            double diagonal = Math.sqrt(0.5);
            AABB expected = new AABB(3 * diagonal, -0.5, -6 * diagonal, 6 * diagonal, 0.5, -3 * diagonal)
                    .move(from.lerp(to, 0.5));
            AABB bounds = craft.renderBounds(0.5f);
            test.assertTrue(bounds.getMinPosition().distanceTo(expected.getMinPosition()) < 1.0e-5
                    && bounds.getMaxPosition().distanceTo(expected.getMaxPosition()) < 1.0e-5,
                    "The culling box did not match the translated, half-rotated craft");
        } finally {
            craft.discard();
        }
        test.succeed();
    }
}
