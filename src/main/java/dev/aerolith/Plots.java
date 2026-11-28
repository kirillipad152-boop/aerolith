package dev.aerolith;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.io.IOException;
import java.nio.file.Files;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Server side of plot storage: which craft owns which cell of the plot dimension, keeping those chunks loaded and ticking,
 * and moving blocks and block entities between the world and a plot.
 */
@EventBusSubscriber(modid = Aerolith.ID)
public final class Plots {
    static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            Identifier.fromNamespaceAndPath(Aerolith.ID, "plot"));
    static final Identifier DIMENSION_ID = DIMENSION.identifier();
    private static final String TNT_CRAFT = "AerolithTntCraft";
    /** Plot writes keep neighbours untouched so a block keeps exactly the state it had. */
    static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    private static final Map<Integer, CraftEntity> LOADED = new ConcurrentHashMap<>();
    /** Only while placing the initial blocks of a newly allocated cell, on the server thread. */
    private static int populating = -1;

    private Plots() {}

    /** The plot dimension; in the game-test server, which has no extra dimensions, a high region of its level stands in. */
    static ServerLevel level(MinecraftServer server) {
        ServerLevel plot = server.getLevel(DIMENSION);
        return plot != null || !(server instanceof GameTestServer) ? plot : server.overworld();
    }

    static boolean isPlot(Level level) {
        return level instanceof ServerLevel server && server == level(server.getServer());
    }

    // ---- allocation

    private static final class Store extends SavedData {
        final BitSet used = new BitSet();

        static Store load(CompoundTag tag) {
            Store store = new Store();
            store.used.or(BitSet.valueOf(tag.getLongArray("Used").orElseThrow()));
            return store;
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLongArray("Used", used.toLongArray());
            return tag;
        }
    }

    private static final SavedDataType<Store> STORE = new SavedDataType<>(Identifier.fromNamespaceAndPath(Aerolith.ID, "plots"),
            Store::new, CompoundTag.CODEC.xmap(Store::load, Store::save), null);

    private static Store store(ServerLevel plot) {
        var storage = plot.getDataStorage();
        Store existing = storage.get(STORE);
        if (existing != null) return existing;
        var root = plot.getServer().getWorldPath(LevelResource.ROOT);
        var folder = DimensionType.getStorageFolder(plot.dimension(), root).resolve("data");
        if (!Files.notExists(folder.resolve("aerolith/plots.dat"))) {
            throw new IllegalStateException("Unreadable Aerolith plot allocation data; refusing to reuse plots");
        }
        var legacy = folder.resolve("aerolith_plots.dat");
        if (!Files.notExists(legacy)) {
            try {
                Store migrated = Store.load(NbtIo.readCompressed(legacy, NbtAccounter.create(4L << 20)).getCompoundOrEmpty("data"));
                storage.set(STORE, migrated);
                migrated.setDirty();
                return migrated;
            } catch (IOException | RuntimeException failure) {
                throw new IllegalStateException("Unreadable legacy Aerolith plot allocation data; refusing to reuse plots", failure);
            }
        }
        return storage.computeIfAbsent(STORE);
    }

    static int allocate(ServerLevel plot) {
        Store store = store(plot);
        int id = store.used.nextClearBit(0);
        if (id >= PlotGrid.COLUMNS * PlotGrid.ROWS) throw new IllegalStateException("No free plot");
        store.used.set(id);
        store.setDirty();
        return id;
    }

    static void release(ServerLevel plot, int id) {
        Store store = store(plot);
        if (id >= 0 && store.used.get(id)) {
            store.used.clear(id);
            store.setDirty();
        }
    }

    // ---- loaded crafts

    static void register(CraftEntity craft) {
        if (craft.plotId() >= 0) LOADED.put(craft.plotId(), craft);
    }

    static void unregister(CraftEntity craft) {
        if (craft.plotId() >= 0) LOADED.remove(craft.plotId(), craft);
    }

    static boolean isLoaded(int id) {
        return id >= 0 && LOADED.containsKey(id);
    }

    static boolean mayHaveSavedChunk(long key) {
        int id = PlotGrid.idAtChunk(ChunkPos.getX(key), ChunkPos.getZ(key));
        CraftEntity owner = LOADED.get(id);
        return id != populating && (owner == null || owner.chunkKeys().contains(key));
    }

    /** The loaded craft whose plot contains {@code pos}. */
    public static CraftEntity craftAt(BlockPos pos) {
        int id = PlotGrid.idAt(pos);
        return id < 0 ? null : LOADED.get(id);
    }

    /** True if {@code pos} is a block of the plot dimension that belongs to some craft's cell. */
    public static boolean inGrid(BlockPos pos) {
        return PlotGrid.idAt(pos) >= 0;
    }

    @SubscribeEvent
    public static void starting(ServerStartingEvent event) {
        LOADED.clear();
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        LOADED.clear();
        PlotChunks.forget();
    }

    // ---- block transfer

    /** A block with the data of its block entity. */
    record Moved(BlockPos offset, BlockState state, CompoundTag entity) {}

    static Moved capture(ServerLevel level, BlockPos pos, BlockPos offset) {
        BlockEntity entity = level.getBlockEntity(pos);
        return new Moved(offset, level.getBlockState(pos), entity == null ? null : entity.saveWithFullMetadata(level.registryAccess()));
    }

    /** Writes blocks into the plot at {@code origin}, with their block entities; nothing around them is updated. */
    static void write(ServerLevel plot, BlockPos origin, List<Moved> blocks) {
        int previous = populating;
        populating = PlotGrid.idAt(origin);
        try {
            for (Moved moved : blocks) {
                BlockPos pos = origin.offset(moved.offset());
                plot.setBlock(pos, moved.state(), QUIET);
                if (moved.entity() != null) {
                    BlockEntity entity = plot.getBlockEntity(pos);
                    if (entity != null) {
                        entity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, plot.registryAccess(), moved.entity()));
                        entity.setChanged();
                    }
                }
            }
        } finally {
            populating = previous;
        }
    }

    /** Removes a block and its block entity without dropping the contents. */
    static void erase(ServerLevel level, BlockPos pos) {
        level.removeBlockEntity(pos);
        level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), QUIET);
    }

    // ---- entities made inside a plot

    /** Drops and lit TNT appear in the world at the same place on the craft, not in the hidden dimension. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void spill(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !isPlot(level)) return;
        var entity = event.getEntity();
        if (!(entity instanceof ItemEntity || entity instanceof ExperienceOrb || entity instanceof PrimedTnt)) return;
        BlockPos pos = entity.blockPosition();
        CraftEntity craft = craftAt(pos);
        if (craft == null) return;
        Vec3 world = craft.worldPoint(entity.position());
        if (craft.level() == level && inGrid(BlockPos.containing(world))) return;
        event.setCanceled(true);
        Vec3 velocity = craft.velocityAt(world).scale(0.05);
        if (entity instanceof PrimedTnt tnt) {
            double halfHeight = tnt.getBbHeight() * 0.5;
            Vec3 center = craft.worldPoint(tnt.position().add(0, halfHeight, 0));
            PrimedTnt copy = new PrimedTnt(craft.level(), center.x, center.y - halfHeight, center.z, tnt.getOwner());
            copy.setFuse(tnt.getFuse());
            copy.setBlockState(tnt.getBlockState());
            copy.setDeltaMovement(craft.velocityAt(center).scale(0.05).add(Hull.rotate(craft.orientation(1f), tnt.getDeltaMovement())));
            copy.getPersistentData().store(TNT_CRAFT, UUIDUtil.CODEC, craft.getUUID());
            craft.level().addFreshEntity(copy);
        } else if (entity instanceof ItemEntity item) {
            ItemEntity copy = new ItemEntity(craft.level(), world.x, world.y, world.z, item.getItem().copy());
            copy.setDeltaMovement(velocity.add(item.getDeltaMovement().scale(0.5)));
            copy.setPickUpDelay(10);
            craft.level().addFreshEntity(copy);
        } else if (entity instanceof ExperienceOrb orb) {
            craft.level().addFreshEntity(new ExperienceOrb(craft.level(), world.x, world.y, world.z, orb.getValue()));
        }
    }

    @SubscribeEvent
    public static void detonate(ExplosionEvent.Detonate event) {
        var blast = event.getExplosion();
        if (!(event.getLevel() instanceof ServerLevel world) || blast.getBlockInteraction() == net.minecraft.world.level.Explosion.BlockInteraction.KEEP
                || !(blast.getDirectSourceEntity() instanceof PrimedTnt tnt)) return;
        ServerLevel plot = level(world.getServer());
        if (plot == null || world == plot && inGrid(BlockPos.containing(blast.center()))) return;
        var id = tnt.getPersistentData().read(TNT_CRAFT, UUIDUtil.CODEC).orElse(null);
        if (id == null || !(world.getEntity(id) instanceof CraftEntity craft)
                || craft.isRemoved() || craft.hull() == null || craft.plotId() < 0) return;
        float radius = Math.min(blast.radius(), 4f);
        Vec3 local = HullCollisions.localPoint(craft, blast.center());
        if (craft.hull().localBounds.distanceToSqr(local) > 4.0 * radius * radius) return;
        // shortcut: only the originating hull gets a plot blast; shared shielding needs rays through world and plot blocks.
        Vec3 at = PlotGrid.toPlot(craft.plotOrigin(), local);
        plot.explode(tnt, at.x, at.y, at.z, radius, Level.ExplosionInteraction.TNT);
    }

    @SubscribeEvent
    public static void keepTicking(ServerTickEvent.Post event) {
        ServerLevel plot = level(event.getServer());
        // A level without players stops ticking its block entities after fifteen seconds unless told it is not empty.
        if (plot == null) return;
        if (!LOADED.isEmpty()) plot.resetEmptyTime();
        PlotChunks.tick(plot);
    }

    // ---- what happens in the plot dimension is shown and heard in the world

    /** A block of the plot dimension changed. */
    public static void changed(ServerLevel level, BlockPos pos, BlockState old, BlockState now) {
        if (LOADED.isEmpty()) return;
        int id = PlotGrid.idAt(pos);
        if (id < 0 || !isPlot(level)) return;
        CraftEntity craft = LOADED.get(id);
        if (craft != null) craft.plotChanged(pos, old, now);
    }

    /** Whether fluid may spread into {@code pos}: not out of the box that a craft's blocks span. */
    public static boolean allowsFluid(net.minecraft.world.level.BlockGetter level, BlockPos pos) {
        if (LOADED.isEmpty() || !(level instanceof ServerLevel server)) return true;
        int id = PlotGrid.idAt(pos);
        if (id < 0 || !isPlot(server)) return true;
        CraftEntity craft = LOADED.get(id);
        return craft == null || craft.holdsFluid(pos);
    }

    public static void blockEvent(ServerLevel level, BlockPos pos, int type, int data) {
        if (LOADED.isEmpty() || !isPlot(level)) return;
        CraftEntity craft = craftAt(pos);
        if (craft != null) PlotSync.sendEvent(craft, pos, type, data);
    }

    /** Plays a plot sound at the matching place in the craft's world; true if it was taken over. */
    public static boolean sound(ServerLevel level, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source,
            float volume, float pitch, long seed) {
        if (LOADED.isEmpty() || !isPlot(level)) return false;
        CraftEntity craft = craftAt(BlockPos.containing(x, y, z));
        if (craft == null) return false;
        Vec3 at = craft.worldPoint(new Vec3(x, y, z));
        ((ServerLevel) craft.level()).playSeededSound(null, at.x, at.y, at.z, sound, source, volume, pitch, seed);
        return true;
    }

    /** Level events (door sounds, breaking particles, smoke) of the plot are replayed in the craft's world. */
    public static boolean levelEvent(ServerLevel level, net.minecraft.world.entity.Entity source, int type, BlockPos pos, int data) {
        if (LOADED.isEmpty() || !isPlot(level)) return false;
        CraftEntity craft = craftAt(pos);
        if (craft == null) return false;
        // A player breaking a block gets its own effects drawn at the exact place; do not show them twice.
        if (type == 2001 && source instanceof Player) return true;
        BlockPos at = BlockPos.containing(craft.worldPoint(Vec3.atCenterOf(pos)));
        ((ServerLevel) craft.level()).levelEvent(null, type, at, data);
        return true;
    }

    /**
     * Whether {@code player} can reach the plot block {@code pos}, as the vanilla distance check asks. A block of a craft is
     * far from where it appears, so the question is answered for the craft's block in the world. Null leaves vanilla's answer.
     */
    public static Boolean reach(Player player, BlockPos pos, double extra) {
        if (LOADED.isEmpty() || player.level().isClientSide() || PlotGrid.idAt(pos) < 0 || isPlot(player.level())) return null;
        CraftEntity craft = craftAt(pos);
        if (craft == null) return null;
        double range = player.blockInteractionRange() + extra;
        Vec3 center = craft.worldPoint(Vec3.atCenterOf(pos));
        return new AABB(center, center).inflate(0.5).distanceToSqr(player.getEyePosition()) < range * range;
    }

    /** Players, anywhere, whose open container is the one a counter of the plot dimension is counting. */
    public static List<Player> remoteOpeners(ServerLevel level, java.util.function.Predicate<Player> owns) {
        if (LOADED.isEmpty() || !isPlot(level)) return List.of();
        List<Player> out = new java.util.ArrayList<>();
        for (Player player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level() != level && owns.test(player)) out.add(player);
        }
        return out;
    }
}
