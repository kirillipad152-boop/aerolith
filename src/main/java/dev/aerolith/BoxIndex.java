package dev.aerolith;

import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.phys.AABB;

/**
 * The collision boxes of a hull, which can be asked for the ones near a region instead of all of them. A hull of a
 * million blocks has about as many boxes, and every entity that comes near it asks several times per tick.
 * Each box is kept in the cell that holds its lowest corner, so a query looks at cells a box's size further down.
 */
final class BoxIndex extends AbstractList<AABB> implements RandomAccess {
    private static final int CELL = 4;

    private final List<AABB> boxes;
    private final Long2ObjectOpenHashMap<IntArrayList> cells = new Long2ObjectOpenHashMap<>();
    private double reachX, reachY, reachZ;

    BoxIndex(List<AABB> boxes) {
        this.boxes = boxes;
        for (int i = 0; i < boxes.size(); i++) {
            AABB box = boxes.get(i);
            reachX = Math.max(reachX, box.getXsize());
            reachY = Math.max(reachY, box.getYsize());
            reachZ = Math.max(reachZ, box.getZsize());
            cells.computeIfAbsent(key(cell(box.minX), cell(box.minY), cell(box.minZ)), k -> new IntArrayList()).add(i);
        }
    }

    @Override
    public AABB get(int index) {
        return boxes.get(index);
    }

    @Override
    public int size() {
        return boxes.size();
    }

    /** Indices of the boxes that touch {@code region}. */
    IntArrayList near(AABB region) {
        IntArrayList found = new IntArrayList();
        int x0 = cell(region.minX - reachX), x1 = cell(region.maxX);
        int y0 = cell(region.minY - reachY), y1 = cell(region.maxY);
        int z0 = cell(region.minZ - reachZ), z1 = cell(region.maxZ);
        // shortcut: a region wider than the hull is looked up cell by cell, which beats scanning only while few cells are empty.
        if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) > cells.size()) {
            for (IntArrayList list : cells.values()) collect(list, region, found);
            return found;
        }
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    IntArrayList list = cells.get(key(x, y, z));
                    if (list != null) collect(list, region, found);
                }
            }
        }
        return found;
    }

    private void collect(IntArrayList list, AABB region, IntArrayList found) {
        for (int i = 0; i < list.size(); i++) {
            int index = list.getInt(i);
            if (boxes.get(index).intersects(region)) found.add(index);
        }
    }

    private static int cell(double coordinate) {
        return (int) Math.floor(coordinate / CELL);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }
}
