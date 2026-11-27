package dev.aerolith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import dev.aerolith.mixin.LightEngineAccess;
import dev.aerolith.mixin.ServerLevelAccess;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * The chunks that hold crafts' blocks. The vanilla chunk map would generate each of them and surround it with more than
 * seven hundred chunk holders that are walked every tick, so the plot grid keeps its own: a chunk is empty when it is new,
 * exists only where a craft has blocks (or a neighbour was looked at), and ticks only for a craft that is loaded. They are
 * saved to the plot dimension's ordinary region files, in the ordinary format.
 */
public final class PlotChunks {
    private static final int FIRST = PlotGrid.BASE - PlotGrid.CELL / 2 >> 4;
    private static final int PRUNE_TICKS = 600;

    private static volatile PlotChunks current;

    private final ServerLevel level;
    private final PalettedContainerFactory palettes;
    /** One server-thread writer and concurrent light-thread readers; no full map copy for each new plot. */
    private final Map<Long, LevelChunk> chunks = new ConcurrentHashMap<>();
    /** Chunks that belong to a loaded craft; the others were only looked at. */
    private final LongSet kept = new LongOpenHashSet();
    /** Chunks of crafts that are gone, to be cleared on disk together with the save that forgets the craft. */
    private final LongSet cleared = new LongOpenHashSet();
    /** Keep the latest snapshot, even on eviction, until the region worker actually writes it. */
    private record Write(CompoundTag tag, CompletableFuture<Void> done) {}
    private final Map<Long, Write> pending = new ConcurrentHashMap<>();

    private PlotChunks(ServerLevel level) {
        this.level = level;
        this.palettes = PalettedContainerFactory.create(level.registryAccess());
    }

    // ---- what the game asks

    /** Whether the plot grid, and not the vanilla chunk map, answers for this chunk. */
    public static boolean covers(ServerLevel level, int x, int z) {
        return x >= FIRST && z >= FIRST && PlotGrid.idAtChunk(x, z) >= 0 && Plots.isPlot(level);
    }

    /** A chunk of the grid if it is there. Safe from any thread. */
    public static LevelChunk find(ServerLevel level, int x, int z) {
        PlotChunks store = current;
        return store == null || store.level != level ? null : store.chunks.get(ChunkPos.pack(x, z));
    }

    /** A previously saved chunk must not be shadowed by an empty neighbor on an early lookup. Server thread only. */
    public static LevelChunk get(ServerLevel level, int x, int z) {
        PlotChunks store = of(level);
        long key = ChunkPos.pack(x, z);
        LevelChunk chunk = store.chunks.get(key);
        if (chunk != null) return chunk;
        if (Plots.mayHaveSavedChunk(key)) return load(level, key);
        return store.add(store.empty(new ChunkPos(x, z)), true);
    }

    /** Whether the game may look into the chunk without having it made: it is there, or a loaded craft's cell holds it. */
    public static boolean available(ServerLevel level, int x, int z) {
        return find(level, x, z) != null || Plots.isLoaded(PlotGrid.idAtChunk(x, z));
    }

    /** Whether blocks, block entities and entities of the chunk tick. */
    public static boolean ticks(DistanceManager manager, long key) {
        if (ChunkPos.getX(key) < FIRST) return false;
        PlotChunks store = current;
        return store != null && store.level.getChunkSource().chunkMap.getDistanceManager() == manager && store.kept.contains(key);
    }

    // ---- what crafts ask

    private static PlotChunks of(ServerLevel level) {
        PlotChunks store = current;
        if (store == null || store.level != level) current = store = new PlotChunks(level);
        return store;
    }

    static void forget() {
        current = null;
    }

    /** The chunk with the blocks it was saved with. */
    static LevelChunk load(ServerLevel level, long key) {
        PlotChunks store = of(level);
        LevelChunk chunk = store.chunks.get(key);
        if (chunk != null) return chunk;
        ChunkPos pos = ChunkPos.unpack(key);
        LevelChunk saved = store.cleared.contains(key) ? null : store.read(pos);
        return saved != null ? store.add(saved, false) : store.add(store.empty(pos), true);
    }

    /** Makes the chunk tick, and remain, until it is let go. */
    static void keep(ServerLevel level, long key) {
        LevelChunk chunk = load(level, key);
        PlotChunks store = of(level);
        if (!store.kept.add(key)) return;
        chunk.setFullStatus(() -> FullChunkStatus.ENTITY_TICKING);
        ((ServerLevelAccess) level).aerolith$entityManager().updateChunkStatus(chunk.getPos(), FullChunkStatus.ENTITY_TICKING);
    }

    /** The craft was unloaded: its chunk is saved and leaves memory. */
    static void letGo(ServerLevel level, long key) {
        PlotChunks store = of(level);
        LevelChunk chunk = store.chunks.get(key);
        if (chunk != null) store.drop(chunk, true);
    }

    /** The craft is gone: so is its chunk. */
    static void discard(ServerLevel level, long key) {
        PlotChunks store = of(level);
        LevelChunk chunk = store.chunks.get(key);
        if (chunk != null) {
            // What stood in the cell goes too: a contraption entity left behind would be saved with the chunk and attach itself
            // to whatever craft is given this cell next that has a block of its kind in the same place.
            ChunkPos pos = chunk.getPos();
            var box = new net.minecraft.world.phys.AABB(pos.getMinBlockX(), level.getMinY(), pos.getMinBlockZ(),
                    pos.getMaxBlockX() + 1, level.getMaxY() + 1, pos.getMaxBlockZ() + 1);
            for (var entity : level.getEntities((net.minecraft.world.entity.Entity) null, box,
                    e -> !(e instanceof net.minecraft.world.entity.player.Player))) {
                entity.discard();
            }
            store.drop(chunk, false);
        }
        store.cleared.add(key);
    }

    // ---- keeping them

    private LevelChunk empty(ChunkPos pos) {
        Holder<Biome> nothing = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.THE_VOID);
        LevelChunkSection[] sections = new LevelChunkSection[level.getSectionsCount()];
        for (int i = 0; i < sections.length; i++) {
            sections[i] = new LevelChunkSection(palettes.createForBlockStates(), new PalettedContainer<>(nothing, palettes.biomeStrategy()));
        }
        LevelChunk chunk = new LevelChunk(level, pos, UpgradeData.EMPTY, new LevelChunkTicks<>(), new LevelChunkTicks<>(), 0L,
                sections, null, null);
        chunk.setLightCorrect(false);
        return chunk;
    }

    private LevelChunk read(ChunkPos pos) {
        try {
            var map = level.getChunkSource().chunkMap;
            Write waiting = pending.get(pos.pack());
            CompoundTag tag = waiting == null ? map.read(pos).join().orElse(null) : waiting.tag() == null ? null : waiting.tag().copy();
            if (tag == null) return null;
            tag = map.upgradeChunkTag(tag, -1,
                    net.minecraft.server.level.ChunkMap.getChunkDataFixContextTag(level.dimension(), level.getChunkSource().getGenerator().getTypeNameForDataFixer()),
                    SharedConstants.getCurrentVersion().dataVersion().version());
            var info = new RegionStorageInfo(level.getServer().getWorldData().getLevelName(), level.dimension(), "chunk");
            // A chunk that never finished generating is the rim of an old plot: it holds nothing.
            var serialized = SerializableChunkData.parse(level, palettes, tag);
            if (serialized == null) throw new IllegalStateException("Missing chunk data");
            var chunk = serialized.read(level, level.getPoiManager(), info, pos);
            NeoForge.EVENT_BUS.post(new ChunkDataEvent.Load(chunk, serialized));
            return chunk instanceof ImposterProtoChunk full ? full.getWrapped() : null;
        } catch (RuntimeException failure) {
            // Going on with an empty chunk would overwrite the blocks at the next save.
            throw new IllegalStateException("Cannot read Aerolith plot chunk " + pos, failure);
        }
    }

    private LevelChunk add(LevelChunk chunk, boolean fresh) {
        ChunkPos pos = chunk.getPos();
        chunks.put(pos.pack(), chunk);
        chunk.setFullStatus(() -> FullChunkStatus.FULL);
        chunk.runPostLoad();
        chunk.setLoaded(true);
        chunk.registerAllBlockEntitiesAfterLevelLoad();
        chunk.registerTickContainerInLevel(level);
        chunk.initializeLightSources();
        ThreadedLevelLightEngine light = level.getChunkSource().getLightEngine();
        boolean lit = chunk.isLightCorrect();
        light.initializeLight(chunk, lit);
        light.lightChunk(chunk, lit);
        light.tryScheduleUpdate();
        chunk.tryMarkSaved();
        NeoForge.EVENT_BUS.post(new ChunkEvent.Load(chunk, fresh));
        return chunk;
    }

    private void drop(LevelChunk chunk, boolean save) {
        ChunkPos pos = chunk.getPos();
        long key = pos.pack();
        chunk.setLoaded(false);
        NeoForge.EVENT_BUS.post(new ChunkEvent.Unload(chunk));
        if (save) write(chunk);
        level.unload(chunk);
        if (kept.remove(key)) ((ServerLevelAccess) level).aerolith$entityManager().updateChunkStatus(pos, FullChunkStatus.INACCESSIBLE);
        chunk.setFullStatus(() -> FullChunkStatus.INACCESSIBLE);
        ThreadedLevelLightEngine light = level.getChunkSource().getLightEngine();
        ((LightEngineAccess) light).aerolith$updateChunkStatus(pos);
        light.tryScheduleUpdate();
        chunks.remove(key);
    }

    private void write(LevelChunk chunk) {
        level.getPoiManager().flush(chunk.getPos());
        if (!chunk.isUnsaved() || !kept.contains(chunk.getPos().pack()) && !solid(chunk)) return;
        var serialized = SerializableChunkData.copyOf(level, chunk);
        NeoForge.EVENT_BUS.post(new ChunkDataEvent.Save(chunk, level, serialized));
        store(chunk.getPos(), serialized.write());
        cleared.remove(chunk.getPos().pack());
        chunk.tryMarkSaved();
    }

    private Write store(ChunkPos pos, CompoundTag tag) {
        Write write = new Write(tag, level.getChunkSource().chunkMap.write(pos, tag));
        long key = pos.pack();
        pending.put(key, write);
        write.done().whenComplete((unused, failure) -> {
            if (failure == null) pending.remove(key, write);
            else Aerolith.LOG.error("Cannot save Aerolith plot chunk {}; keeping it for the next save", pos, failure);
        });
        return write;
    }

    /** The world is being saved. */
    public static void save(ServerLevel level, boolean flush) {
        PlotChunks store = current;
        if (store == null || store.level != level) return;
        for (long key : store.cleared) store.store(ChunkPos.unpack(key), null);
        store.cleared.clear();
        for (LevelChunk chunk : store.chunks.values()) store.write(chunk);
        store.pending.forEach((key, write) -> {
            if (write.done().isCompletedExceptionally()) write = store.store(ChunkPos.unpack(key), write.tag());
            if (flush) write.done().join();
        });
    }

    /** Random ticks, which the vanilla chunk source gives only to chunks of its own. */
    static void tick(ServerLevel level) {
        PlotChunks store = current;
        if (store == null || store.level != level) return;
        if (level.tickRateManager().runsNormally()) {
            int speed = level.getGameRules().get(GameRules.RANDOM_TICK_SPEED);
            if (speed > 0) {
                for (long key : store.kept.toLongArray()) {
                    LevelChunk chunk = store.chunks.get(key);
                    if (chunk != null && growing(chunk)) level.tickChunk(chunk, speed);
                }
            }
        }
        if (level.getGameTime() % PRUNE_TICKS == 0) store.prune();
    }

    private static boolean growing(ChunkAccess chunk) {
        for (LevelChunkSection section : chunk.getSections()) {
            if (section.isRandomlyTicking()) return true;
        }
        return false;
    }

    /** Lets go of chunks that were only looked at. */
    private void prune() {
        List<LevelChunk> idle = new ArrayList<>();
        for (LevelChunk chunk : chunks.values()) {
            if (!kept.contains(chunk.getPos().pack())) idle.add(chunk);
        }
        for (LevelChunk chunk : idle) drop(chunk, !chunk.isEmpty() && solid(chunk));
    }

    private static boolean solid(ChunkAccess chunk) {
        for (LevelChunkSection section : chunk.getSections()) {
            if (!section.hasOnlyAir()) return true;
        }
        return false;
    }
}
