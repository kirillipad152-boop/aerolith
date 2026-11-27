package dev.aerolith;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * Where each craft's blocks live. Every craft owns one cell of a grid in the plot dimension, far from anything a player
 * could build, and its blocks sit at {@code origin + offset}. A craft block at offset {@code o} is drawn centred on
 * {@code o} in the craft's frame, so a point {@code q} of that frame is the point {@code origin + 0.5 + q} of the plot.
 */
final class PlotGrid {
    static final int CELL = 512;
    static final int COLUMNS = 1000;
    static final int ROWS = 19_000;
    static final int BASE = 20_000_000;
    static final int ORIGIN_Y = 128;
    /** Blocks may be this far from the craft origin; the plot dimension is 256 blocks tall. */
    static final int REACH = 127;

    private PlotGrid() {}

    /** The middle of a cell, where crafts saved before origins were saved with them have theirs. */
    static BlockPos origin(int id) {
        return new BlockPos(BASE + (id % COLUMNS) * CELL, ORIGIN_Y, BASE + (id / COLUMNS) * CELL);
    }

    /** The origin for blocks at {@code offsets}: shifted so that they start at the edge of a chunk and touch as few as possible. */
    static BlockPos origin(int id, Iterable<BlockPos> offsets) {
        int x = Integer.MAX_VALUE, z = Integer.MAX_VALUE;
        for (BlockPos offset : offsets) {
            x = Math.min(x, offset.getX());
            z = Math.min(z, offset.getZ());
        }
        return origin(id).offset(x == Integer.MAX_VALUE ? 0 : Math.floorMod(-x, 16), 0, z == Integer.MAX_VALUE ? 0 : Math.floorMod(-z, 16));
    }

    /** The plot a chunk lies in, or -1; cells begin and end at chunk borders. */
    static int idAtChunk(int x, int z) {
        return idAt((x << 4) + 8, (z << 4) + 8);
    }

    /** The plot a position belongs to, or -1 for a position outside the grid. */
    static int idAt(BlockPos pos) {
        return pos.getY() < 0 || pos.getY() > 255 ? -1 : idAt(pos.getX(), pos.getZ());
    }

    private static int idAt(int x, int z) {
        int column = Math.floorDiv(x - BASE + CELL / 2, CELL);
        int row = Math.floorDiv(z - BASE + CELL / 2, CELL);
        if (column < 0 || column >= COLUMNS || row < 0 || row >= ROWS) return -1;
        return row * COLUMNS + column;
    }

    static boolean validOffset(BlockPos offset) {
        return Math.abs(offset.getX()) <= REACH && Math.abs(offset.getY()) <= REACH && Math.abs(offset.getZ()) <= REACH;
    }

    static Vec3 toPlot(BlockPos origin, Vec3 craftPoint) {
        return Vec3.atLowerCornerOf(origin).add(0.5, 0.5, 0.5).add(craftPoint);
    }

    static Vec3 toCraft(BlockPos origin, Vec3 plotPoint) {
        return plotPoint.subtract(Vec3.atLowerCornerOf(origin)).subtract(0.5, 0.5, 0.5);
    }

    static long chunkKey(BlockPos pos) {
        return ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
    }
}
