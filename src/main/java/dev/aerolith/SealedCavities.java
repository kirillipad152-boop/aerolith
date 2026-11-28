package dev.aerolith;

import java.util.Arrays;
import java.util.List;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/**
 * Computes sealed interior air cavities of a hull once at hull construction time using an exterior
 * 3D flood-fill on a compact 1-bit voxel mask and 3D greedy prism merging.
 */
final class SealedCavities {
    static final Result EMPTY = new Result(new double[0], 0.0);
    private static final long MAX_GRID_VOXELS = 16_777_216L; // 256^3 max craft span

    record Result(double[] boxes, double volume) {}

    private SealedCavities() {}

    static Result find(
            List<CraftBlock> blocks,
            List<CraftBlock> fluids,
            Long2ObjectOpenHashMap<AABB[]> solidCells,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ
    ) {
        int nx = maxX - minX + 1;
        int ny = maxY - minY + 1;
        int nz = maxZ - minZ + 1;
        long total = (long) nx * ny * nz;
        if (total <= 0 || total > MAX_GRID_VOXELS) return EMPTY;

        int n = (int) total;
        int strideY = nx * nz;
        int words = (n + 63) >>> 6;
        long[] sealed = new long[words];
        for (CraftBlock block : blocks) {
            if (!Materials.sealsCavity(block.state())) continue;
            BlockPos p = block.offset();
            int x = p.getX() - minX, y = p.getY() - minY, z = p.getZ() - minZ;
            if ((x | y | z) < 0 || x >= nx || y >= ny || z >= nz) continue;
            setBit(sealed, (y * nz + z) * nx + x);
        }

        long[] fluidMask = null;
        if (!fluids.isEmpty()) {
            fluidMask = new long[words];
            for (CraftBlock fluid : fluids) {
                BlockPos p = fluid.offset();
                int x = p.getX() - minX, y = p.getY() - minY, z = p.getZ() - minZ;
                if (x >= 0 && x < nx && y >= 0 && y < ny && z >= 0 && z < nz) {
                    setBit(fluidMask, (y * nz + z) * nx + x);
                }
            }
        }

        long[] visited = new long[words];
        IntQueue queue = new IntQueue(Math.min(n, 4096));
        seedBoundary(sealed, visited, queue, nx, ny, nz, strideY);
        floodExterior(sealed, visited, queue, nx, ny, nz, strideY);

        long[] interior = null;
        DoubleArrayList extras = null;
        double extraVolume = 0.0;

        for (int wordIdx = 0; wordIdx < words; wordIdx++) {
            long unvisited = ~(sealed[wordIdx] | visited[wordIdx]);
            if (fluidMask != null) unvisited &= ~fluidMask[wordIdx];
            if (unvisited == 0L) continue;
            int base = wordIdx << 6;
            while (unvisited != 0L) {
                int bit = Long.numberOfTrailingZeros(unvisited);
                unvisited &= unvisited - 1L;
                int idx = base + bit;
                if (idx >= n) break;
                int y = idx / strideY;
                int rem = idx - y * strideY;
                int z = rem / nx;
                int x = rem - z * nx;
                int ox = minX + x, oy = minY + y, oz = minZ + z;
                AABB[] own = solidCells.get(BlockPos.asLong(ox, oy, oz));
                if (own == null) {
                    if (interior == null) interior = new long[words];
                    setBit(interior, idx);
                } else if (own.length == 1) {
                    AABB remaining = complementarySlab(own[0], ox - 0.5, oy - 0.5, oz - 0.5);
                    if (remaining != null) {
                        if (extras == null) extras = new DoubleArrayList(24);
                        addBox(extras, remaining);
                        extraVolume += remaining.getXsize() * remaining.getYsize() * remaining.getZsize();
                    }
                }
            }
        }

        if (interior == null && extras == null) return EMPTY;

        DoubleArrayList out = extras != null ? extras : new DoubleArrayList(64);
        double volume = extraVolume;
        if (interior != null) {
            volume += greedyMerge(interior, out, minX, minY, minZ, nx, ny, nz, strideY);
        }
        return out.isEmpty() ? EMPTY : new Result(out.toDoubleArray(), volume);
    }

    private static void seedBoundary(long[] sealed, long[] visited, IntQueue queue, int nx, int ny, int nz, int strideY) {
        for (int y = 0; y < ny; y++) {
            int yBase = y * strideY;
            if (y == 0 || y == ny - 1) {
                for (int idx = yBase, end = yBase + strideY; idx < end; idx++) {
                    queue.tryPush(idx, sealed, visited);
                }
            } else {
                for (int x = 0; x < nx; x++) {
                    queue.tryPush(yBase + x, sealed, visited);
                    queue.tryPush(yBase + (nz - 1) * nx + x, sealed, visited);
                }
                for (int z = 1; z < nz - 1; z++) {
                    int row = yBase + z * nx;
                    queue.tryPush(row, sealed, visited);
                    queue.tryPush(row + nx - 1, sealed, visited);
                }
            }
        }
    }

    private static void floodExterior(long[] sealed, long[] visited, IntQueue queue, int nx, int ny, int nz, int strideY) {
        while (!queue.isEmpty()) {
            int idx = queue.pop();
            int y = idx / strideY;
            int rem = idx - y * strideY;
            int z = rem / nx;
            int x = rem - z * nx;
            if (x > 0) queue.tryPush(idx - 1, sealed, visited);
            if (x + 1 < nx) queue.tryPush(idx + 1, sealed, visited);
            if (z > 0) queue.tryPush(idx - nx, sealed, visited);
            if (z + 1 < nz) queue.tryPush(idx + nx, sealed, visited);
            if (y > 0) queue.tryPush(idx - strideY, sealed, visited);
            if (y + 1 < ny) queue.tryPush(idx + strideY, sealed, visited);
        }
    }

    private static double greedyMerge(
            long[] grid, DoubleArrayList out,
            int minX, int minY, int minZ,
            int nx, int ny, int nz, int strideY
    ) {
        double volume = 0.0;
        for (int y = 0; y < ny; y++) {
            int yBase = y * strideY;
            for (int z = 0; z < nz; z++) {
                int row = yBase + z * nx;
                for (int x = 0; x < nx; ) {
                    if (!getBit(grid, row + x)) {
                        x++;
                        continue;
                    }
                    int xEnd = x + 1;
                    while (xEnd < nx && getBit(grid, row + xEnd)) xEnd++;
                    int zEnd = z + 1;
                    while (zEnd < nz && rowFilled(grid, yBase + zEnd * nx, x, xEnd)) zEnd++;
                    int yEnd = y + 1;
                    while (yEnd < ny && slabFilled(grid, yEnd * strideY, nx, x, xEnd, z, zEnd)) yEnd++;
                    clearBox(grid, strideY, nx, x, xEnd, y, yEnd, z, zEnd);
                    double dx = xEnd - x, dy = yEnd - y, dz = zEnd - z;
                    out.add(minX + x - 0.5 + dx * 0.5);
                    out.add(minY + y - 0.5 + dy * 0.5);
                    out.add(minZ + z - 0.5 + dz * 0.5);
                    out.add(dx * 0.5);
                    out.add(dy * 0.5);
                    out.add(dz * 0.5);
                    volume += dx * dy * dz;
                    x = xEnd;
                }
            }
        }
        return volume;
    }

    private static boolean rowFilled(long[] grid, int rowOffset, int xStart, int xEnd) {
        for (int x = xStart; x < xEnd; x++) {
            if (!getBit(grid, rowOffset + x)) return false;
        }
        return true;
    }

    private static boolean slabFilled(long[] grid, int yBase, int nx, int xStart, int xEnd, int zStart, int zEnd) {
        for (int z = zStart; z < zEnd; z++) {
            if (!rowFilled(grid, yBase + z * nx, xStart, xEnd)) return false;
        }
        return true;
    }

    private static void clearBox(long[] grid, int strideY, int nx, int x0, int x1, int y0, int y1, int z0, int z1) {
        for (int y = y0; y < y1; y++) {
            int yBase = y * strideY;
            for (int z = z0; z < z1; z++) {
                int row = yBase + z * nx;
                for (int x = x0; x < x1; x++) {
                    clearBit(grid, row + x);
                }
            }
        }
    }

    /** Exact complement of a single full-width slab/carpet/trapdoor box inside a 1x1x1 voxel, or null if not planar. */
    private static AABB complementarySlab(AABB box, double x0, double y0, double z0) {
        double eps = 1.0e-6;
        double x1 = x0 + 1.0, y1 = y0 + 1.0, z1 = z0 + 1.0;
        boolean fullX = box.minX <= x0 + eps && box.maxX >= x1 - eps;
        boolean fullY = box.minY <= y0 + eps && box.maxY >= y1 - eps;
        boolean fullZ = box.minZ <= z0 + eps && box.maxZ >= z1 - eps;
        if (fullX && fullZ && !fullY) {
            if (box.minY <= y0 + eps && y1 - box.maxY >= 0.002) return new AABB(x0, box.maxY, z0, x1, y1, z1);
            if (box.maxY >= y1 - eps && box.minY - y0 >= 0.002) return new AABB(x0, y0, z0, x1, box.minY, z1);
        } else if (fullX && fullY && !fullZ) {
            if (box.minZ <= z0 + eps && z1 - box.maxZ >= 0.002) return new AABB(x0, y0, box.maxZ, x1, y1, z1);
            if (box.maxZ >= z1 - eps && box.minZ - z0 >= 0.002) return new AABB(x0, y0, z0, x1, y1, box.minZ);
        } else if (fullY && fullZ && !fullX) {
            if (box.minX <= x0 + eps && x1 - box.maxX >= 0.002) return new AABB(box.maxX, y0, z0, x1, y1, z1);
            if (box.maxX >= x1 - eps && box.minX - x0 >= 0.002) return new AABB(x0, y0, z0, box.minX, y1, z1);
        }
        return null;
    }

    private static void addBox(DoubleArrayList out, AABB box) {
        out.add((box.minX + box.maxX) * 0.5);
        out.add((box.minY + box.maxY) * 0.5);
        out.add((box.minZ + box.maxZ) * 0.5);
        out.add(box.getXsize() * 0.5);
        out.add(box.getYsize() * 0.5);
        out.add(box.getZsize() * 0.5);
    }

    private static boolean getBit(long[] bits, int idx) {
        return (bits[idx >>> 6] & (1L << idx)) != 0L;
    }

    private static void setBit(long[] bits, int idx) {
        bits[idx >>> 6] |= 1L << idx;
    }

    private static void clearBit(long[] bits, int idx) {
        bits[idx >>> 6] &= ~(1L << idx);
    }

    private static final class IntQueue {
        private int[] items;
        private int head;
        private int tail;

        IntQueue(int initialCapacity) {
            this.items = new int[Math.max(16, initialCapacity)];
        }

        boolean isEmpty() {
            return head == tail;
        }

        int pop() {
            return items[head++];
        }

        void tryPush(int idx, long[] sealed, long[] visited) {
            int word = idx >>> 6;
            long bit = 1L << idx;
            if (((sealed[word] | visited[word]) & bit) != 0L) return;
            visited[word] |= bit;
            if (tail == items.length) {
                items = Arrays.copyOf(items, items.length << 1);
            }
            items[tail++] = idx;
        }
    }
}
