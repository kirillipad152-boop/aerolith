package dev.aerolith;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Whole server ticks while one-block rafts in a pen push each other all the time. Runs only when AEROLITH_BENCH_CUBES is set
 * (AEROLITH_BENCH_CUBES=256 ./gradlew runGameTestServer: the number of rafts; add JAVA_TOOL_OPTIONS=-Daerolith.syncPhysics=true
 * to step the engine on the server thread again for comparison). The result is in the server log.
 */
final class CubeRaftsBenchmark {
    private CubeRaftsBenchmark() {}

    static void run(GameTestHelper test) {
        String requested = System.getenv("AEROLITH_BENCH_CUBES");
        if (requested == null) {
            test.succeed();
            return;
        }
        int count = Integer.parseInt(requested.isBlank() || requested.equals("1") ? "128" : requested);
        var level = test.getLevel();
        var server = level.getServer();
        BlockPos start = test.absolutePos(new BlockPos(4, 40, 4));
        Vec3 origin = Vec3.atCenterOf(start).add(0, 1, 0);
        ChunkPos chunk = ChunkPos.containing(start);
        int side = (int) Math.ceil(Math.sqrt(count));
        int wall = (int) Math.ceil(side * .52 + 1.4);
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) level.setChunkForced(chunk.x() + x, chunk.z() + z, true);
        var oak = List.of(new CraftBlock(BlockPos.ZERO, Blocks.OAK_PLANKS.defaultBlockState()));
        var stone = Blocks.STONE.defaultBlockState();
        CraftEntity[] rafts = new CraftEntity[count];
        Vec3[] starts = new Vec3[count];
        double[] shifted = new double[count];
        int[] tick = {0};
        // The test server ticks as fast as it can, a real one 20 times a second: the test waits out the rest of each tick, as the
        // idle time of a real server would let the engine step in the background. What is timed is the work of the tick.
        long[] tickStart = {System.nanoTime()};
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Pre event) -> tickStart[0] = System.nanoTime());
        double[] work = new double[200];
        test.startSequence().thenIdle(60).thenExecute(() -> {
            for (int x = -wall; x <= wall; x++) for (int z = -wall; z <= wall; z++) {
                level.setBlock(start.offset(x, 0, z), stone, Block.UPDATE_CLIENTS);
                if (Math.abs(x) == wall || Math.abs(z) == wall) {
                    for (int y = 1; y <= 3; y++) level.setBlock(start.offset(x, y, z), stone, Block.UPDATE_CLIENTS);
                }
            }
            var owner = UUID.randomUUID();
            for (int i = 0; i < count; i++) {
                starts[i] = origin.add((i % side - (side - 1) * .5) * 1.025, 0, (i / side - (side - 1) * .5) * 1.025);
                rafts[i] = StructureTransfer.create(level, oak, starts[i], owner);
                test.assertTrue(rafts[i] != null, "Failed to spawn raft " + i);
            }
        }).thenIdle(24).thenExecuteFor(200, () -> {
            int k = tick[0]++;
            if (k == 40) Perf.reset("server.");
            boolean alongX = (k / 16 & 1) == 0;
            for (int i = 0; i < count; i++) {
                var craft = rafts[i];
                Vec3 p = craft.position(), velocity = craft.comVelocity();
                double x = p.x - starts[i].x, z = p.z - starts[i].z;
                shifted[i] = Math.max(shifted[i], x * x + z * z);
                test.assertTrue(!craft.isRemoved() && p.y > origin.y - .35 && p.y < origin.y + 4, "Raft " + i + " left the pen: " + p);
                double lateral = ((i / side + i % side) % 3 - 1) * 1.75;
                double u = starts[i].x < origin.x ? 5 : -5, v = starts[i].z < origin.z ? 5 : -5;
                Vec3 impulse = new Vec3(clamp((alongX ? u : lateral) - velocity.x), 0, clamp((alongX ? lateral : v) - velocity.z))
                        .scale(craft.hull().mass);
                craft.queuePush(craft.worldCenterOfMass(), impulse);
            }
            long spent = System.nanoTime() - tickStart[0];
            work[k] = spent / 1e6;
            try {
                Thread.sleep(Math.max(0, 50 - spent / 1_000_000));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }).thenIdle(1).thenExecute(() -> {
            int moved = (int) Arrays.stream(shifted).filter(s -> s > .0025).count();
            test.assertTrue(moved >= count * .9, "Only " + moved + "/" + count + " rafts were displaced");
            double[] ms = Arrays.copyOfRange(work, 40, Math.min(tick[0], 200));
            Arrays.sort(ms);
            Aerolith.LOG.info("CUBES SERVER count={} moved={} ticks={} mean={} p50={} p95={} max={}", count, moved, ms.length,
                    Arrays.stream(ms).average().orElseThrow(), ms[ms.length / 2], ms[ms.length * 95 / 100], ms[ms.length - 1]);
            Aerolith.LOG.info("CUBES SERVER parts:\n{}", Perf.report("server."));
            for (var craft : rafts) craft.discard();
        }).thenSucceed();
    }

    private static double clamp(double x) {
        return Math.max(-.65, Math.min(.65, x));
    }
}
