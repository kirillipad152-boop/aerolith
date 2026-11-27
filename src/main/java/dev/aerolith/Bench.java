package dev.aerolith;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiConsumer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * A repeatable load for the engine: a field of rafts (a deck, a wall around it, a chest, a furnace, optionally water) dropped
 * onto the ground. Run with {@code /aerolith bench}, it reports the synchronous spawn cost separately and measures
 * three intervals of ticks while the rafts fall and settle.
 */
@EventBusSubscriber(modid = Aerolith.ID)
final class Bench {
    static final int MAX_SIDE = 60;

    private record Run(WorldPhysics physics, List<CraftEntity> crafts, int ticks, BiConsumer<Boolean, String> done,
            StringBuilder log) {}

    private static Run run;
    private static long tickStart;
    private static int elapsed;

    private Bench() {}

    static boolean running() {
        return run != null;
    }

    /** The blocks of one raft with sides of {@code side} blocks. */
    static List<CraftBlock> raft(int side) {
        List<CraftBlock> blocks = new ArrayList<>();
        int half = side / 2;
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                blocks.add(new CraftBlock(new BlockPos(x - half, 0, z - half), Blocks.OAK_PLANKS.defaultBlockState()));
                if (x == 0 || z == 0 || x == side - 1 || z == side - 1) {
                    blocks.add(new CraftBlock(new BlockPos(x - half, 1, z - half), Blocks.OAK_PLANKS.defaultBlockState()));
                }
            }
        }
        if (side >= 5) {
            blocks.add(new CraftBlock(new BlockPos(1 - half, 1, 1 - half), Blocks.CHEST.defaultBlockState()));
            blocks.add(new CraftBlock(new BlockPos(2 - half, 1, 1 - half), Blocks.FURNACE.defaultBlockState()));
        }
        return blocks;
    }

    /** Starts a run. Reports failure if a run is already active or the requested load cannot be simulated. */
    static boolean start(ServerLevel level, Vec3 center, int crafts, int side, int ticks, boolean water, BiConsumer<Boolean, String> done) {
        if (run != null) {
            done.accept(false, "A benchmark is already running");
            return false;
        }
        long spawnStart = Perf.begin();
        WorldPhysics physics = WorldPhysics.of(level);
        List<CraftBlock> blocks = raft(side);
        int columns = (int) Math.ceil(Math.sqrt(crafts));
        double spacing = side + 3;
        List<CraftEntity> spawned = new ArrayList<>();
        for (int i = 0; i < crafts && physics.hasSpace(); i++) {
            Vec3 at = center.add((i % columns - (columns - 1) / 2.0) * spacing, 3.0 + (i % 3), (i / columns - (columns - 1) / 2.0) * spacing);
            CraftEntity craft = StructureTransfer.create(level, blocks, at, UUID.randomUUID());
            if (craft == null) break;
            if (water) {
                var plot = craft.plotLevel();
                plot.setBlockAndUpdate(craft.plotPos(new BlockPos(0, 1, 0)), Blocks.WATER.defaultBlockState());
            }
            spawned.add(craft);
        }
        if (spawned.size() != crafts) {
            spawned.forEach(CraftEntity::discard);
            done.accept(false, "Benchmark aborted: could only spawn " + spawned.size() + " of " + crafts + " test rafts");
            return false;
        }
        elapsed = -1;
        String header = String.format(Locale.ROOT, "Benchmark: %d rafts of %d blocks (%d x %d)%s, %d measured ticks%n"
                + "Spawn: %.2f ms (startup tick excluded from the measurements below)%n",
                crafts, blocks.size(), side, side, water ? ", with water" : "", ticks, (System.nanoTime() - spawnStart) / 1e6);
        run = new Run(physics, spawned, ticks, done, new StringBuilder(header));
        return true;
    }

    @SubscribeEvent
    public static void pre(ServerTickEvent.Pre event) {
        tickStart = Perf.begin();
    }

    @SubscribeEvent
    public static void post(ServerTickEvent.Post event) {
        long start = tickStart;
        if (start != 0) Perf.end("server.tick", start);
        Run current = run;
        if (current == null) return;
        if (++elapsed == 0) {
            Perf.reset("server.");
            return;
        }
        for (CraftEntity craft : current.crafts()) {
            if (!current.physics().isSimulated(craft)) {
                finish(current, false, "Benchmark aborted: a test raft is not being simulated. Keep the whole test area loaded.");
                return;
            }
        }
        if (elapsed == current.ticks() / 3) {
            current.log().append("-- dropping and settling (first third) --\n").append(Perf.report("server."));
            Perf.reset("server.");
        } else if (elapsed == current.ticks() * 2 / 3) {
            current.log().append("-- second third --\n").append(Perf.report("server."));
            Perf.reset("server.");
        } else if (elapsed >= current.ticks()) {
            current.log().append("-- last third (at rest if the rafts have gone to sleep) --\n").append(Perf.report("server."));
            double mspt = Perf.averageMs("server.tick");
            current.log().append(String.format(Locale.ROOT, "Last-third average: %.2f ms of the 50 ms per tick at 20 TPS.%n", mspt));
            finish(current, true, current.log().toString());
        }
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        if (run != null) finish(run, false, "Benchmark stopped with the server");
    }

    private static void finish(Run current, boolean ok, String report) {
        run = null;
        current.crafts().forEach(CraftEntity::discard);
        current.done().accept(ok, report);
    }
}
