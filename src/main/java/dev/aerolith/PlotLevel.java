package dev.aerolith;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;

/**
 * The client's copy of the plot dimension. It is never the level the player is in; it only holds the chunks of the crafts
 * around, as the server sends them, so that their blocks, block entities and fluids can be drawn like any others.
 */
final class PlotLevel extends ClientLevel {
    final ClientLevel owner;
    private final Long2ObjectOpenHashMap<LevelChunk> plotChunks = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap lightByPos = new Long2IntOpenHashMap();
    private CraftEntity viewed;

    PlotLevel(Minecraft client, ClientPacketListener connection, Holder<DimensionType> type) {
        super(connection, new ClientLevelData(Difficulty.NORMAL, false, true), Plots.DIMENSION, type, 2, 2,
                client.levelExtractor, false, 0L, client.level.getSeaLevel());
        this.owner = client.level;
    }

    LevelChunk put(int x, int z) {
        LevelChunk chunk = new LevelChunk(this, new ChunkPos(x, z));
        LevelChunk old = plotChunks.put(ChunkPos.pack(x, z), chunk);
        if (old != null) old.clearAllBlockEntities();
        return chunk;
    }

    LevelChunk chunkAt(long key) {
        return plotChunks.get(key);
    }

    LevelChunk chunkAt(BlockPos pos) {
        return plotChunks.get(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
    }

    void drop(long key) {
        LevelChunk chunk = plotChunks.remove(key);
        if (chunk != null) chunk.clearAllBlockEntities();
    }

    boolean isEmpty() {
        return plotChunks.isEmpty();
    }

    @Override
    public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean load) {
        LevelChunk chunk = plotChunks.get(ChunkPos.pack(x, z));
        return chunk != null ? chunk : super.getChunk(x, z, status, load);
    }

    /** Draws with the light of the world where {@code craft} is, instead of light data the plot does not have. */
    void view(CraftEntity craft) {
        viewed = craft;
        lightByPos.clear();
    }

    /** The brightest of the world cell where a plot block appears and its neighbours: a block in sand reads dark otherwise. */
    int light(BlockPos plotPos) {
        return light(plotPos.asLong());
    }

    /** The light read since {@link #view}, by plot position. */
    Long2IntMap lightsRead() {
        return lightByPos;
    }

    int light(long plotPos) {
        if (viewed == null) return LightCoordsUtil.pack(0, 15);
        return lightByPos.computeIfAbsent(plotPos, key -> {
            ClientLevel world = owner;
            BlockPos center = BlockPos.containing(viewed.worldPoint(Vec3.atCenterOf(BlockPos.of(key))));
            int light = LightCoordsUtil.getLightCoords(world, center);
            int block = LightCoordsUtil.block(light);
            int sky = LightCoordsUtil.sky(light);
            for (Direction face : Direction.values()) {
                light = LightCoordsUtil.getLightCoords(world, center.relative(face));
                block = Math.max(block, LightCoordsUtil.block(light));
                sky = Math.max(sky, LightCoordsUtil.sky(light));
            }
            return LightCoordsUtil.pack(block, sky);
        });
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        int light = light(pos);
        return layer == LightLayer.SKY ? LightCoordsUtil.sky(light) : LightCoordsUtil.block(light);
    }
}
