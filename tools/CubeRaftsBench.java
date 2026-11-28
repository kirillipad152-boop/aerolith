package dev.aerolith;

import java.util.Arrays;
import java.util.Locale;

/** Times native physics while one-block rafts push each other in a pen. No large pusher or idle settling is timed. */
public final class CubeRaftsBench {
    private static final double[] SHAPE = {0, 0, 0, .5, .5, .5, .55, 0};
    private static final double MASS = 480;
    private static final double[] INERTIA = {0, 0, 0, .5, .5, .5, MASS};
    private static final double[] EMPTY = {};
    private static final int WARMUP = 16;

    public static void main(String[] args) {
        int ticks = args.length == 0 ? 0 : Integer.parseInt(args[0]);
        if (ticks <= WARMUP || args.length < 2) {
            throw new IllegalArgumentException("Usage: CubeRaftsBench <ticks, at least 17> <count>...");
        }
        for (int i = 1; i < args.length; i++) simulate(Integer.parseInt(args[i]), ticks);
    }

    private static void simulate(int count, int ticks) {
        if (count < 2) throw new IllegalArgumentException("At least two rafts are needed");
        long world = NativePhysics.createWorld();
        try {
            int side = (int) Math.ceil(Math.sqrt(count));
            double extent = side * .52 + 1.4, edge = extent + .5;
            NativePhysics.setTerrain(world, 1, new double[] {
                    0, -.5, 0, extent + 2, .5, extent + 2, .60, 0,
                    edge, 1.5, 0, .5, 1.5, extent, .60, 0, -edge, 1.5, 0, .5, 1.5, extent, .60, 0,
                    0, 1.5, edge, extent, 1.5, .5, .60, 0, 0, 1.5, -edge, extent, 1.5, .5, .60, 0});
            long[] ids = new long[count];
            double[] sx = new double[count], sz = new double[count], vx = new double[count], vz = new double[count];
            double[] maxShift = new double[count];
            for (int i = 0; i < count; i++) {
                sx[i] = (i % side - (side - 1) * .5) * 1.025;
                sz[i] = (i / side - (side - 1) * .5) * 1.025;
                ids[i] = NativePhysics.addBody(world, new double[] {sx[i], .501, sz[i], 0, 0, 0, 1, 0, 0, 0, 0, 0, 0},
                        SHAPE, EMPTY, INERTIA, EMPTY);
            }
            for (int i = 0; i < 24; i++) NativePhysics.step(world, 0, 0, 0);
            double[] times = new double[ticks - WARMUP];
            for (int tick = 0; tick < ticks; tick++) {
                boolean alongX = (tick / 16 & 1) == 0;
                for (int i = 0; i < count; i++) {
                    double lateral = ((i / side + i % side) % 3 - 1) * 1.75;
                    double dx = (alongX ? (sx[i] < 0 ? 5 : -5) : lateral) - vx[i];
                    double dz = (alongX ? lateral : (sz[i] < 0 ? 5 : -5)) - vz[i];
                    NativePhysics.impulse(world, ids[i], MASS * clamp(dx), 0, MASS * clamp(dz));
                }
                long start = System.nanoTime();
                double[] out = NativePhysics.step(world, 0, 0, 0);
                if (tick >= WARMUP) times[tick - WARMUP] = (System.nanoTime() - start) / 1e6;
                if (out.length != count * NativePhysics.RECORD) throw new AssertionError("Missing rafts at tick " + tick);
                for (double value : out) if (!Double.isFinite(value)) throw new AssertionError("Non-finite state");
                for (int i = 0; i < out.length; i += NativePhysics.RECORD) {
                    int row = Arrays.binarySearch(ids, (long) out[i]);
                    if (out[i + 2] < 0) throw new AssertionError("Raft " + row + " went through the floor");
                    double dx = out[i + 1] - sx[row], dz = out[i + 3] - sz[row];
                    maxShift[row] = Math.max(maxShift[row], dx * dx + dz * dz);
                    vx[row] = out[i + 8];
                    vz[row] = out[i + 10];
                }
            }
            long moved = Arrays.stream(maxShift).filter(d -> d > .0025).count();
            if (moved < count * .9) throw new AssertionError("Only " + moved + "/" + count + " rafts moved");
            Arrays.sort(times);
            System.out.printf(Locale.ROOT, "CUBES count=%d active=%d displaced=%d mean=%.3f p50=%.3f p95=%.3f max=%.3f%n",
                    count, count, moved, Arrays.stream(times).average().orElseThrow(),
                    times[times.length / 2], times[(int) Math.ceil(times.length * .95) - 1], times[times.length - 1]);
        } finally {
            NativePhysics.destroyWorld(world);
        }
    }

    private static double clamp(double x) {
        return Math.max(-.65, Math.min(.65, x));
    }
}
