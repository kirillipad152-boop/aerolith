package dev.aerolith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class Terrain {
    /** A chunk section. Fewer, larger boxes make fewer contacts; seams between them are not felt. */
    static final int TILE = 16;
    /** Tiles with blocks whose shape can change on its own are looked at this often. */
    private static final int DYNAMIC_TICKS = 10;
    /** Tiles nobody needs stay this long, so that a craft moving to and fro over a border does not rebuild them. */
    private static final int LINGER_TICKS = 40;
    /** Changes of blocks are reported; something that writes chunks behind the game's back is noticed within this time. */
    private static final int RESCAN_TICKS = 400;
    private static final AABB UNIT = new AABB(0, 0, 0, 1, 1, 1);
    private static final double[] EMPTY = {};
    private final ServerLevel level;
    private final FrictionConfig materials;
    private final Long2ObjectOpenHashMap<Tile> tiles = new Long2ObjectOpenHashMap<>();
    private final ArrayList<Tile> order = new ArrayList<>();
    private final ArrayList<Tile> stale = new ArrayList<>();
    private int cursorTile;
    private long rescanCredit;
    private final IdentityHashMap<BlockState, List<AABB>> shapeCache = new IdentityHashMap<>();
    private final DoubleArrayList boxes = new DoubleArrayList();
    private final DoubleArrayList water = new DoubleArrayList();
    private final FrictionConfig.Contact[] solid = new FrictionConfig.Contact[TILE * TILE * TILE];
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final BlockGetter view = new BlockGetter() {
        @Override
        public BlockState getBlockState(BlockPos pos) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(pos);
        }

        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public int getHeight() { return level.getHeight(); }
        @Override public int getMinY() { return level.getMinY(); }
    };

    Terrain(ServerLevel level, FrictionConfig materials) {
        this.level = level;
        this.materials = materials;
    }

    private static final class Tile {
        final long key;
        final int x, y, z;
        int users;
        long idleSince;
        boolean stale;
        boolean dynamic;
        double[] boxes = EMPTY;
        double[] water = EMPTY;

        Tile(long key) {
            this.key = key;
            x = BlockPos.getX(key) * TILE;
            y = BlockPos.getY(key) * TILE;
            z = BlockPos.getZ(key) * TILE;
        }
    }

    /** An inclusive box of tiles. */
    record Range(int x1, int y1, int z1, int x2, int y2, int z2) {
        boolean contains(int x, int y, int z) {
            return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
        }
    }

    /** Moves a craft's claim from one box of tiles to another; either may be null. */
    void claim(Range from, Range to) {
        if (to != null) {
            for (int x = to.x1; x <= to.x2; x++) {
                for (int y = to.y1; y <= to.y2; y++) {
                    for (int z = to.z1; z <= to.z2; z++) {
                        if (from != null && from.contains(x, y, z)) continue;
                        long key = BlockPos.asLong(x, y, z);
                        Tile tile = tiles.get(key);
                        if (tile == null) {
                            tiles.put(key, tile = new Tile(key));
                            order.add(tile);
                            mark(tile);
                        }
                        tile.users++;
                    }
                }
            }
        }
        if (from != null) {
            long now = level.getGameTime();
            for (int x = from.x1; x <= from.x2; x++) {
                for (int y = from.y1; y <= from.y2; y++) {
                    for (int z = from.z1; z <= from.z2; z++) {
                        if (to != null && to.contains(x, y, z)) continue;
                        Tile tile = tiles.get(BlockPos.asLong(x, y, z));
                        if (tile != null && --tile.users == 0) tile.idleSince = now;
                    }
                }
            }
        }
    }

    private void mark(Tile tile) {
        if (!tile.stale) {
            tile.stale = true;
            stale.add(tile);
        }
    }

    /** A block of the world changed. What water does depends on the blocks beside it, so a change at a border counts for both tiles. */
    void changed(BlockPos pos) {
        if (tiles.isEmpty()) return;
        int x1 = Math.floorDiv(pos.getX() - 1, TILE), x2 = Math.floorDiv(pos.getX() + 1, TILE);
        int y1 = Math.floorDiv(pos.getY() - 1, TILE), y2 = Math.floorDiv(pos.getY() + 1, TILE);
        int z1 = Math.floorDiv(pos.getZ() - 1, TILE), z2 = Math.floorDiv(pos.getZ() + 1, TILE);
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    Tile tile = tiles.get(BlockPos.asLong(x, y, z));
                    if (tile != null) mark(tile);
                }
            }
        }
    }

    /** The native world is new and knows no terrain. */
    void reset() {
        for (Tile tile : order) {
            tile.boxes = EMPTY;
            tile.water = EMPTY;
            mark(tile);
        }
    }

    /** Brings the native world's colliders and water up to date with the blocks. */
    void sync(long world) {
        long now = level.getGameTime();
        if (now % DYNAMIC_TICKS == 0) {
            for (int i = order.size() - 1; i >= 0; i--) {
                Tile tile = order.get(i);
                if (tile.users == 0 && now - tile.idleSince >= LINGER_TICKS) {
                    forget(world, i, tile);
                } else if (tile.dynamic) {
                    mark(tile);
                }
            }
        }
        // Spread a full pass over RESCAN_TICKS, even for a single tile. Block-change events do not wait for it.
        rescanCredit += order.size();
        while (rescanCredit >= RESCAN_TICKS) {
            rescanCredit -= RESCAN_TICKS;
            if (++cursorTile >= order.size()) cursorTile = 0;
            mark(order.get(cursorTile));
        }
        if (stale.isEmpty()) return;
        // A chunk that is not available right now is not empty ground: what is published stays, and it is asked for again.
        stale.removeIf(tile -> {
            if (tiles.get(tile.key) != tile) return true;
            tile.stale = !update(world, tile);
            return !tile.stale;
        });
    }

    private void forget(long world, int index, Tile tile) {
        if (tile.boxes.length != 0) NativePhysics.setTerrain(world, tile.key, EMPTY);
        if (tile.water.length != 0) NativePhysics.setWater(world, tile.key, EMPTY);
        tiles.remove(tile.key);
        Tile last = order.removeLast();
        if (index < order.size()) order.set(index, last);
    }

    private boolean update(long world, Tile tile) {
        int sx = tile.x, sy = tile.y, sz = tile.z;
        LevelChunk chunk = level.getChunkSource().getChunkNow(sx >> 4, sz >> 4);
        if (chunk == null) return false;
        LevelChunkSection section = chunk.getSection(level.getSectionIndex(sy));
        boxes.clear();
        water.clear();
        tile.dynamic = false;
        if (!section.hasOnlyAir()) {
            Arrays.fill(solid, null);
            BlockState last = null;
            FrictionConfig.Contact lastSolid = null;
            for (int y = 0; y < TILE; y++) {
                for (int z = 0; z < TILE; z++) {
                    for (int x = 0; x < TILE; x++) {
                        int wx = sx + x, wy = sy + y, wz = sz + z;
                        BlockState state = section.getBlockState(wx & 15, wy & 15, wz & 15);
                        if (state == last) {
                            // Most blocks are like the one before them: the same full cube, or the same nothing.
                            solid[index(x, y, z)] = lastSolid;
                            continue;
                        }
                        if (state.isAir()) {
                            last = state;
                            lastSolid = null;
                            continue;
                        }
                        cursor.set(wx, wy, wz);
                        FluidState fluid = state.getFluidState();
                        if (fluid.is(FluidTags.WATER)) {
                            double height = fluid.getHeight(view, cursor);
                            if (height > 0) {
                                Vec3 flow = fluid.getFlow(view, cursor);
                                water.add(wx);
                                water.add(wy);
                                water.add(wz);
                                water.add(height);
                                water.add(flow.x);
                                water.add(flow.y);
                                water.add(flow.z);
                            }
                        }
                        boolean dynamic = state.getBlock().hasDynamicShape();
                        tile.dynamic |= dynamic;
                        List<AABB> shape = dynamic ? state.getCollisionShape(view, cursor).toAabbs()
                                : shapeCache.computeIfAbsent(state, b -> List.copyOf(b.getCollisionShape(view, cursor).toAabbs()));
                        if (shape.isEmpty()) continue;
                        FrictionConfig.Contact material = materials.contact(state);
                        if (shape.size() == 1 && UNIT.equals(shape.getFirst())) {
                            solid[index(x, y, z)] = material;
                            if (!dynamic && fluid.isEmpty()) {
                                last = state;
                                lastSolid = material;
                            }
                        } else {
                            for (AABB part : shape) {
                                addBox(wx + part.minX, wy + part.minY, wz + part.minZ,
                                        wx + part.maxX, wy + part.maxY, wz + part.maxZ, material.mu(), material.restitution());
                            }
                        }
                    }
                }
            }
            mergeSolid(sx, sy, sz);
        }
        tile.boxes = publish(world, tile.key, tile.boxes, boxes, false);
        tile.water = publish(world, tile.key, tile.water, water, true);
        return true;
    }

    private static double[] publish(long world, long key, double[] prior, DoubleArrayList data, boolean fluid) {
        if (prior.length == data.size()) {
            int i = 0;
            while (i < prior.length && prior[i] == data.getDouble(i)) i++;
            if (i == prior.length) return prior;
        }
        double[] next = data.isEmpty() ? EMPTY : data.toDoubleArray();
        if (fluid) NativePhysics.setWater(world, key, next);
        else NativePhysics.setTerrain(world, key, next);
        return next;
    }

    private void mergeSolid(int sx, int sy, int sz) {
        for (int y = 0; y < TILE; y++) {
            for (int z = 0; z < TILE; z++) {
                for (int x = 0; x < TILE; x++) {
                    FrictionConfig.Contact material = solid[index(x, y, z)];
                    if (material == null) continue;
                    int ex = x + 1, ez = z + 1, ey = y + 1;
                    while (ex < TILE && material.equals(solid[index(ex, y, z)])) ex++;
                    while (ez < TILE && matches(material, x, ex, y, ez, ez + 1)) ez++;
                    while (ey < TILE && matches(material, x, ex, ey, z, ez)) ey++;
                    for (int iy = y; iy < ey; iy++) {
                        for (int iz = z; iz < ez; iz++) {
                            Arrays.fill(solid, index(x, iy, iz), index(x, iy, iz) + ex - x, null);
                        }
                    }
                    addBox(sx + x, sy + y, sz + z, sx + ex, sy + ey, sz + ez, material.mu(), material.restitution());
                }
            }
        }
    }

    private boolean matches(FrictionConfig.Contact material, int x1, int x2, int y, int z1, int z2) {
        for (int z = z1; z < z2; z++) {
            for (int x = x1; x < x2; x++) {
                if (!material.equals(solid[index(x, y, z)])) return false;
            }
        }
        return true;
    }

    private static int index(int x, int y, int z) {
        return (y * TILE + z) * TILE + x;
    }

    private void addBox(double x1, double y1, double z1, double x2, double y2, double z2, double friction, double bounce) {
        boxes.add((x1 + x2) * 0.5);
        boxes.add((y1 + y2) * 0.5);
        boxes.add((z1 + z2) * 0.5);
        boxes.add((x2 - x1) * 0.5);
        boxes.add((y2 - y1) * 0.5);
        boxes.add((z2 - z1) * 0.5);
        boxes.add(friction);
        boxes.add(bounce);
    }
}
