package dev.aerolith;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cheap timers for the parts of the engine, so that its cost can be read off a running game: how long physics, plot ticks,
 * block sync and drawing take per tick or frame. Shown by {@code /aerolith perf} and by the benchmark.
 */
final class Perf {
    private static final class Stat {
        long nanos;
        long count;
        long worst;
    }

    private static final Map<String, Stat> STATS = new ConcurrentHashMap<>();

    private Perf() {}

    static long begin() {
        return System.nanoTime();
    }

    static void end(String key, long start) {
        long elapsed = System.nanoTime() - start;
        Stat stat = STATS.computeIfAbsent(key, k -> new Stat());
        synchronized (stat) {
            stat.nanos += elapsed;
            stat.count++;
            stat.worst = Math.max(stat.worst, elapsed);
        }
    }

    static void reset(String prefix) {
        STATS.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /** One line per timer: calls, average and worst time in milliseconds, and the total. */
    static String report(String prefix) {
        StringBuilder out = new StringBuilder();
        for (var entry : new TreeMap<>(STATS).entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            Stat s = entry.getValue();
            synchronized (s) {
                out.append(String.format(java.util.Locale.ROOT, "%-18s n=%-6d avg %7.3f ms  worst %7.3f ms  total %8.1f ms%n",
                        entry.getKey(), s.count, s.nanos / 1e6 / Math.max(1, s.count), s.worst / 1e6, s.nanos / 1e6));
            }
        }
        return out.toString();
    }

    static double averageMs(String key) {
        Stat s = STATS.get(key);
        if (s == null) return 0;
        synchronized (s) {
            return s.nanos / 1e6 / Math.max(1, s.count);
        }
    }
}
