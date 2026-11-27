package dev.aerolith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongFunction;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class CraftEntity extends Entity {
    private static final EntityDataAccessor<Quaternionf> ROTATION =
            SynchedEntityData.defineId(CraftEntity.class, EntityDataSerializers.QUATERNION);
    private static final EntityDataAccessor<Boolean> ANCHORED =
            SynchedEntityData.defineId(CraftEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> STAFF_HOLDER =
            SynchedEntityData.defineId(CraftEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Vector3f> STAFF_POINT =
            SynchedEntityData.defineId(CraftEntity.class, EntityDataSerializers.VECTOR3);
    /** Set by the client to drop the copy of a craft's plot chunks when the craft goes away. */
    static volatile Consumer<CraftEntity> clientRelease = craft -> {};
    /** The client's view of a craft's plot blocks, for fluid flow; the server uses the plot level itself. */
    static volatile java.util.function.Function<CraftEntity, net.minecraft.world.level.BlockGetter> clientBlocks =
            craft -> net.minecraft.world.level.EmptyBlockGetter.INSTANCE;

    private final double[] motion = {0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0};
    private final Quaternionf previousRotation = new Quaternionf();
    private final Quaternionf viewRotation = new Quaternionf();
    private Hull hull;
    private CraftSections sections;
    private UUID owner;
    private Vec3 lerpTarget = Vec3.ZERO;
    private int lerpTicks;

    // The blocks live in the plot dimension. This is a copy of what is there, offset -> state, kept current from block
    // changes on the server and from packets on the client; the hull is derived from it.
    private int plotId = -1;
    private BlockPos plotOrigin = BlockPos.ZERO;
    private final Map<BlockPos, BlockState> cells = new HashMap<>();
    private final LongSet chunkKeys = new LongOpenHashSet();
    private final LongLinkedOpenHashSet changed = new LongLinkedOpenHashSet();
    private boolean hullDirty;
    /** Blocks without a collision shape that came or changed since the hull was last brought up to date: offset to state. */
    private final Map<BlockPos, BlockState> pendingAim = new HashMap<>();
    private boolean ticketed;
    /** The box of block offsets, inclusive, that fluid may fill: whatever the craft's blocks span. */
    private BlockPos boundsMin = BlockPos.ZERO;
    private BlockPos boundsMax = BlockPos.ZERO;
    /** The saved data of a craft whose plot could not be reached, written back unchanged. */
    private CompoundTag unreadable;

    public CraftEntity(EntityType<? extends CraftEntity> type, Level level) {
        super(type, level);
        setNoGravity(true);
    }

    // ---- plot access

    int plotId() {
        return plotId;
    }

    BlockPos plotOrigin() {
        return plotOrigin;
    }

    LongSet chunkKeys() {
        return chunkKeys;
    }

    BlockPos plotPos(BlockPos offset) {
        return plotOrigin.offset(offset);
    }

    Map<BlockPos, BlockState> cells() {
        return Collections.unmodifiableMap(cells);
    }

    /** The server-side level holding this craft's blocks. */
    ServerLevel plotLevel() {
        return level() instanceof ServerLevel server ? Plots.level(server.getServer()) : null;
    }

    /** The point of the world that a point of the plot dimension (inside this craft's cell) is drawn at. */
    Vec3 worldPoint(Vec3 plotPoint) {
        return position().add(Hull.rotate(orientation(1f), PlotGrid.toCraft(plotOrigin, plotPoint)));
    }

    static boolean isFluid(BlockState state) {
        return state.getBlock() instanceof LiquidBlock;
    }

    /** Blocks of this craft, or its pure fluid cells when {@code fluids}. */
    List<CraftBlock> cellBlocks(boolean fluids) {
        List<CraftBlock> out = new ArrayList<>();
        for (var entry : cells.entrySet()) {
            if (isFluid(entry.getValue()) == fluids) out.add(new CraftBlock(entry.getKey(), entry.getValue()));
        }
        // A fixed order keeps the hull, and the colliders sent to the engine, the same however the cells were gathered.
        out.sort(Comparator.comparing(CraftBlock::offset));
        return out;
    }

    static boolean solid(BlockState state) {
        return !Materials.boxes(state).isEmpty();
    }

    static void scanChunk(LevelChunk chunk, BlockPos origin, Map<BlockPos, BlockState> out) {
        LevelChunkSection[] sections = chunk.getSections();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section.hasOnlyAir()) continue;
            int minY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.isAir()) continue;
                        BlockPos offset = new BlockPos(minX + x, minY + y, minZ + z).subtract(origin);
                        // Only this craft's cell: whatever else the chunk holds (terrain, in a plot that shares a world) is not its.
                        if (PlotGrid.validOffset(offset)) out.put(offset, state);
                    }
                }
            }
        }
    }

    private void updateBounds(List<CraftBlock> blocks) {
        if (blocks.isEmpty()) return;
        int x0 = Integer.MAX_VALUE, y0 = x0, z0 = x0, x1 = Integer.MIN_VALUE, y1 = x1, z1 = x1;
        for (CraftBlock block : blocks) {
            BlockPos o = block.offset();
            x0 = Math.min(x0, o.getX());
            y0 = Math.min(y0, o.getY());
            z0 = Math.min(z0, o.getZ());
            x1 = Math.max(x1, o.getX());
            y1 = Math.max(y1, o.getY());
            z1 = Math.max(z1, o.getZ());
        }
        boundsMin = new BlockPos(x0, y0, z0);
        boundsMax = new BlockPos(x1, y1, z1);
    }

    /** Whether fluid may be in the plot cell {@code plotPos}: only inside the box the craft's blocks span. */
    boolean holdsFluid(BlockPos plotPos) {
        BlockPos o = plotPos.subtract(plotOrigin);
        return o.getX() >= boundsMin.getX() && o.getX() <= boundsMax.getX() && o.getY() >= boundsMin.getY() && o.getY() <= boundsMax.getY()
                && o.getZ() >= boundsMin.getZ() && o.getZ() <= boundsMax.getZ();
    }

    private void buildHull() {
        pendingAim.clear();
        List<CraftBlock> blocks = cellBlocks(false);
        updateBounds(blocks);
        if (blocks.stream().noneMatch(block -> solid(block.state()))) {
            hull = null;
        } else {
            hull = new Hull(blocks, cellBlocks(true));
        }
        reapplyPosition();
    }

    // ---- server: starting, changes, saving

    /** A craft whose blocks have just been written to plot {@code id}, in the chunks {@code chunks}. */
    void start(Vec3 origin, UUID owner, int id, BlockPos plotOrigin, LongSet chunks) {
        this.owner = owner;
        this.plotId = id;
        this.plotOrigin = plotOrigin;
        chunkKeys.addAll(chunks);
        loadCells();
        buildHull();
        setPos(origin);
        setOldPosAndRot();
    }

    private void loadCells() {
        ServerLevel plot = plotLevel();
        cells.clear();
        for (long key : chunkKeys) {
            scanChunk(PlotChunks.load(plot, key), plotOrigin, cells);
        }
    }

    /** Called when a block of this craft's plot changes. */
    void plotChanged(BlockPos pos, BlockState old, BlockState now) {
        BlockPos offset = pos.subtract(plotOrigin);
        if (now.isAir()) {
            cells.remove(offset);
        } else {
            cells.put(offset.immutable(), now);
        }
        long key = PlotGrid.chunkKey(pos);
        if (chunkKeys.add(key) && ticketed) PlotChunks.keep(plotLevel(), key);
        changed.add(pos.asLong());
        if (old.hasBlockEntity() || now.hasBlockEntity()) propulsion.changed();
        noteChange(offset, old, now);
    }

    /** What a change of one block does to the hull. */
    private void noteChange(BlockPos offset, BlockState old, BlockState now) {
        if (old == now) return;
        boolean wasBlock = old != null && !old.isAir() && !isFluid(old);
        boolean isBlock = !now.isAir() && !isFluid(now);
        if (!wasBlock && !isBlock) return;
        // The same block with the same shapes, only another state of it: redstone power, a lit lamp, a lever. Nothing to redo.
        if (wasBlock && isBlock && Materials.samePhysics(old, now)) return;
        // A block without a collision shape placed in the air, or replacing another one: only the aim changes.
        if (isBlock && !Materials.hasCollision(now) && (!wasBlock || !Materials.hasCollision(old))) {
            if (!hullDirty) pendingAim.put(offset.immutable(), now);
            return;
        }
        hullDirty = true;
    }

    /** Takes in the blocks that only change what is aimed at. */
    private void applyAim() {
        if (pendingAim.isEmpty()) return;
        if (hull != null) {
            hull.updateWithoutCollision(pendingAim);
            updateBounds(hull.blocks);
            reapplyPosition();
        }
        pendingAim.clear();
    }

    /** What an entity feels from the fluid in this craft's plot: per fluid type, how deep it is and which way it pushes. */
    record Touch(net.neoforged.neoforge.fluids.FluidType type, double height, Vec3 push) {}

    /** The fluid of this craft around {@code entity}; the craft's blocks are in its own frame, so the entity is carried there. */
    List<Touch> touching(Entity entity) {
        if (hull == null || cells.isEmpty()) return List.of();
        AABB box = entity.getBoundingBox().deflate(0.001);
        Quaternionf rotation = orientation(1f);
        Quaternionf inverse = new Quaternionf(rotation).conjugate();
        Vec3 origin = position();
        double minX = Double.MAX_VALUE, minY = minX, minZ = minX, maxX = -Double.MAX_VALUE, maxY = maxX, maxZ = maxX;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 q = Hull.rotate(inverse, new Vec3((corner & 1) == 0 ? box.minX : box.maxX, (corner & 2) == 0 ? box.minY : box.maxY,
                    (corner & 4) == 0 ? box.minZ : box.maxZ).subtract(origin));
            minX = Math.min(minX, q.x);
            minY = Math.min(minY, q.y);
            minZ = Math.min(minZ, q.z);
            maxX = Math.max(maxX, q.x);
            maxY = Math.max(maxY, q.y);
            maxZ = Math.max(maxZ, q.z);
        }
        net.minecraft.world.level.BlockGetter blocks = level().isClientSide ? clientBlocks.apply(this) : plotLevel();
        Map<net.neoforged.neoforge.fluids.FluidType, double[]> found = null;
        Map<net.neoforged.neoforge.fluids.FluidType, Vec3> flows = null;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int x = (int) Math.floor(minX + 0.5); x <= (int) Math.floor(maxX + 0.5); x++) {
            for (int y = (int) Math.floor(minY + 0.5); y <= (int) Math.floor(maxY + 0.5); y++) {
                for (int z = (int) Math.floor(minZ + 0.5); z <= (int) Math.floor(maxZ + 0.5); z++) {
                    BlockState state = cells.get(at.set(x, y, z));
                    if (state == null) continue;
                    var fluid = state.getFluidState();
                    if (fluid.isEmpty()) continue;
                    var type = fluid.getFluidType();
                    BlockState above = cells.get(at.set(x, y + 1, z));
                    float height = above != null && above.getFluidState().getType().isSame(fluid.getType()) ? 1f : fluid.getOwnHeight();
                    double surface = y - 0.5 + height;
                    if (surface < minY) continue;
                    if (found == null) {
                        found = new HashMap<>();
                        flows = new HashMap<>();
                    }
                    double[] entry = found.computeIfAbsent(type, t -> new double[2]);
                    entry[0] = Math.max(entry[0], surface - minY);
                    if (entity.isPushedByFluid(type)) {
                        Vec3 flow = fluid.getFlow(blocks, plotPos(new BlockPos(x, y, z)));
                        if (entry[0] < 0.4) flow = flow.scale(entry[0]);
                        flows.merge(type, Hull.rotate(rotation, flow), Vec3::add);
                        entry[1]++;
                    }
                }
            }
        }
        if (found == null) return List.of();
        List<Touch> out = new ArrayList<>(found.size());
        for (var entry : found.entrySet()) {
            Vec3 push = Vec3.ZERO;
            Vec3 flow = flows.get(entry.getKey());
            if (flow != null && flow.length() > 0.0) {
                if (entry.getValue()[1] > 0) flow = flow.scale(1.0 / entry.getValue()[1]);
                if (!(entity instanceof Player)) flow = flow.normalize();
                flow = flow.scale(entity.getFluidMotionScale(entry.getKey()));
                Vec3 motion = entity.getDeltaMovement();
                if (Math.abs(motion.x) < 0.003 && Math.abs(motion.z) < 0.003 && flow.length() < 0.0045) {
                    flow = flow.normalize().scale(0.0045);
                }
                push = flow;
            }
            out.add(new Touch(entry.getKey(), entry.getValue()[0], push));
        }
        return out;
    }

    /** The fluid at a point of the world (an entity's eyes), if it lies in this craft's fluid. */
    net.neoforged.neoforge.fluids.FluidType fluidAt(Vec3 point) {
        if (hull == null || cells.isEmpty()) return null;
        Vec3 q = Hull.rotate(new Quaternionf(orientation(1f)).conjugate(), point.subtract(position()));
        BlockPos offset = BlockPos.containing(q.x + 0.5, q.y + 0.5, q.z + 0.5);
        BlockState state = cells.get(offset);
        if (state == null || state.getFluidState().isEmpty()) return null;
        var fluid = state.getFluidState();
        BlockState above = cells.get(offset.above());
        float height = above != null && above.getFluidState().getType().isSame(fluid.getType()) ? 1f : fluid.getOwnHeight();
        return offset.getY() - 0.5 + height > q.y ? fluid.getFluidType() : null;
    }

    boolean hullDirty() {
        return hullDirty;
    }

    void clearDirty() {
        hullDirty = false;
        pendingAim.clear();
    }

    /** Applies pending block changes to the hull and tells clients about them. */
    void settle() {
        if (plotId < 0 || !(level() instanceof ServerLevel server)) return;
        if (hullDirty) {
            long start = Perf.begin();
            CraftEditing.settle(server, this);
            Perf.end("server.settle", start);
        } else if (!pendingAim.isEmpty()) {
            long start = Perf.begin();
            applyAim();
            Perf.end("server.settle-aim", start);
        }
        if (changed.isEmpty()) return;
        long start = Perf.begin();
        flushChanges();
        Perf.end("server.plot-sync", start);
    }

    void flushChanges() {
        if (changed.isEmpty()) return;
        List<Long> list = new ArrayList<>(changed);
        changed.clear();
        PlotSync.sendChanges(this, list);
    }

    /** Replaces the blocks of a live craft, keeping its momentum. */
    void edit(List<CraftBlock> blocks, List<CraftBlock> fluids) {
        pendingAim.clear();
        long begun = Perf.begin();
        Hull next = new Hull(blocks, fluids);
        Perf.end("server.settle.hull", begun);
        updateBounds(blocks);
        Vec3 shift = Hull.rotate(motionRotation(), next.localCom.subtract(hull.localCom));
        Vec3 velocity = comVelocity().add(angularVelocity().cross(shift));
        motion[7] = velocity.x;
        motion[8] = velocity.y;
        motion[9] = velocity.z;
        hull = next;
        reapplyPosition();
        if (level() instanceof ServerLevel server) {
            begun = Perf.begin();
            WorldPhysics.of(server).rebuild(this);
            Perf.end("server.settle.engine", begun);
        }
    }

    /** Breaks every block that is left, with drops, and removes the craft. */
    void dissolve() {
        ServerLevel plot = plotLevel();
        if (plot != null) {
            for (BlockPos offset : new ArrayList<>(cells.keySet())) {
                BlockPos pos = plotPos(offset);
                if (isFluid(cells.get(offset))) Plots.erase(plot, pos);
                else plot.destroyBlock(pos, true);
            }
        }
        discard();
    }

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        if (level() instanceof ServerLevel server) {
            sections = WorldPhysics.of(server).sections;
            sections.update(this);
        }
        if (!level().isClientSide && plotId >= 0) {
            ServerLevel plot = plotLevel();
            if (plot != null) {
                for (long key : chunkKeys) PlotChunks.keep(plot, key);
                ticketed = true;
            }
            Plots.register(this);
        }
    }

    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        if (sections != null) {
            sections.remove(this);
            sections = null;
        }
        if (level().isClientSide) {
            clientRelease.accept(this);
        } else {
            releasePlotLoading();
        }
    }

    /** Stops keeping the plot chunks loaded and ticking, once. */
    private void releasePlotLoading() {
        if (plotId < 0) return;
        Plots.unregister(this);
        ServerLevel plot = plotLevel();
        if (plot != null && ticketed) {
            for (long key : chunkKeys) PlotChunks.letGo(plot, key);
        }
        ticketed = false;
    }

    @Override
    public void remove(RemovalReason reason) {
        // A craft that is killed or discarded takes its blocks with it; one that merely unloads keeps them.
        if (!level().isClientSide && plotId >= 0 && reason.shouldDestroy()) {
            ServerLevel plot = plotLevel();
            Plots.unregister(this);
            if (plot != null) {
                // Beds and workstations are known to the dimension as places of interest; taken out one by one they are forgotten.
                for (var cell : cells.entrySet()) {
                    if (PoiTypes.hasPoi(cell.getValue())) Plots.erase(plot, plotPos(cell.getKey()));
                }
                for (long key : chunkKeys) PlotChunks.discard(plot, key);
                Plots.release(plot, plotId);
            }
            ticketed = false;
            // The cell may be given to another craft at once, so nothing here may touch it again.
            plotId = -1;
            cells.clear();
            chunkKeys.clear();
            changed.clear();
        }
        super.remove(reason);
    }

    // ---- client: copies of the plot chunks

    /** Client: the craft's plot chunks were (re)sent; rebuild everything derived from them. */
    void clientRescan(BlockPos origin, LongFunction<LevelChunk> chunks) {
        plotOrigin = origin;
        cells.clear();
        for (long key : chunkKeys) {
            LevelChunk chunk = chunks.apply(key);
            if (chunk != null) scanChunk(chunk, plotOrigin, cells);
        }
        buildHull();
        previousRotation.set(entityData.get(ROTATION));
        viewRotation.set(previousRotation);
    }

    /** Client: one block of the plot changed. */
    void clientChanged(BlockPos pos, BlockState now) {
        BlockPos offset = pos.subtract(plotOrigin);
        BlockState old = now.isAir() ? cells.remove(offset) : cells.put(offset.immutable(), now);
        noteChange(offset, old, now);
    }

    public List<CraftBlock> blocks() {
        return hull != null ? hull.blocks : List.of();
    }

    public List<AABB> collisionBoxes() {
        return hull != null ? hull.collisionBoxes : List.of();
    }

    Hull hull() {
        return hull;
    }

    public Quaternionf orientation(float partialTick) {
        return level().isClientSide
                ? new Quaternionf(previousRotation).slerp(viewRotation, partialTick)
                : new Quaternionf(entityData.get(ROTATION));
    }

    boolean canEdit(Player player) {
        return player.getUUID().equals(owner) || player.hasPermissions(2);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(ROTATION, new Quaternionf());
        builder.define(ANCHORED, false);
        builder.define(STAFF_HOLDER, -1);
        builder.define(STAFF_POINT, new Vector3f());
    }

    @Override
    public void tick() {
        // Portal and vanilla fluid forces do not own the pose of a physics body.
        if (!level().isClientSide) {
            settle();
        } else {
            if (hullDirty) {
                hullDirty = false;
                buildHull();
            } else {
                applyAim();
            }
            Vec3 prior = position();
            previousRotation.set(viewRotation);
            Quaternionf target = entityData.get(ROTATION);
            if (lerpTicks > 0) {
                // Position and rotation chase the server pose with one step, or a hull whose origin is far from its
                // mass would show a visible gap between where it turns and where it is.
                float step = 1.0f / lerpTicks--;
                viewRotation.slerp(target, step);
                setPos(position().lerp(lerpTarget, step));
            } else if (!viewRotation.equals(target, 1.0e-6f)) {
                viewRotation.slerp(target, 0.5f);
                reapplyPosition();
            }
            HullCollisions.onPoseChanged(this, prior, previousRotation);
        }
    }

    @Override
    public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps) {
        lerpTarget = new Vec3(x, y, z);
        // One step, ignoring vanilla's three: showing the latest server pose and interpolating between ticks keeps a
        // fast craft where it is. Easing over three ticks made falling hulls look like they braked before landing, and
        // sink into what they were about to hit.
        lerpTicks = 1;
    }

    @Override public double lerpTargetX() { return lerpTicks > 0 ? lerpTarget.x : getX(); }
    @Override public double lerpTargetY() { return lerpTicks > 0 ? lerpTarget.y : getY(); }
    @Override public double lerpTargetZ() { return lerpTicks > 0 ? lerpTarget.z : getZ(); }

    @Override
    public void setPos(double x, double y, double z) {
        super.setPos(x, y, z);
        if (sections != null) sections.update(this);
    }

    @Override
    protected AABB makeBoundingBox() {
        if (hull == null) {
            return super.makeBoundingBox();
        }
        AABB a = hull.localBounds;
        Quaternionf q = level().isClientSide ? viewRotation : entityData.get(ROTATION);
        double x = q.x, y = q.y, z = q.z, w = q.w;
        double hx = a.getXsize() * 0.5, hy = a.getYsize() * 0.5, hz = a.getZsize() * 0.5;
        // The rows of the rotation, without their signs, say how far the half extents reach along each axis of the world.
        double ex = Math.abs(1 - 2 * (y * y + z * z)) * hx + Math.abs(2 * (x * y - w * z)) * hy + Math.abs(2 * (x * z + w * y)) * hz;
        double ey = Math.abs(2 * (x * y + w * z)) * hx + Math.abs(1 - 2 * (x * x + z * z)) * hy + Math.abs(2 * (y * z - w * x)) * hz;
        double ez = Math.abs(2 * (x * z - w * y)) * hx + Math.abs(2 * (y * z + w * x)) * hy + Math.abs(1 - 2 * (x * x + y * y)) * hz;
        Vec3 c = Hull.rotate(q, a.getCenter()).add(position());
        return new AABB(c.x - ex, c.y - ey, c.z - ez, c.x + ex, c.y + ey, c.z + ez);
    }

    @Override public boolean isPickable() { return hull != null && !isRemoved(); }
    @Override public boolean hurt(DamageSource source, float amount) { return false; }
    @Override public boolean canChangeDimensions(Level from, Level to) { return false; }
    @Override public boolean shouldRenderAtSqrDistance(double distanceSq) { return distanceSq < 160.0 * 160.0; }

    /** A left click removes the block under the crosshair instead of damaging the craft. */
    @Override
    public boolean skipAttackInteraction(Entity attacker) {
        if (!level().isClientSide && attacker instanceof ServerPlayer player) {
            CraftEditing.breakBlock(player, this);
        }
        return true;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        return interact(player, hand, null);
    }

    /** {@code target} is the block the client aimed at, or null to find it with the server's own ray. */
    InteractionResult interact(Player player, InteractionHand hand, CraftEditing.Target target) {
        ItemStack held = player.getItemInHand(hand);
        boolean tool = held.is(Aerolith.ASSEMBLER.get());
        if (level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer pilot) || player.isSpectator()) {
            return InteractionResult.PASS;
        }
        if (!canEdit(player)) {
            AssemblerItem.message(pilot, "owner");
            return InteractionResult.CONSUME;
        }
        if (!TowStaffItem.held(player).isEmpty()) return InteractionResult.CONSUME;
        if (tool && player.isSecondaryUseActive()) {
            StructureTransfer.dock((ServerLevel) level(), pilot, this);
            return InteractionResult.CONSUME;
        }
        // Blocks of the craft get the click first, exactly as in the world: chests open, levers flip, blocks are placed.
        InteractionResult used = CraftEditing.use(pilot, this, hand, target);
        if (used != InteractionResult.PASS) return used;
        if (player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }
        return InteractionResult.PASS;
    }

    double[] nativeState() {
        double[] state = motion.clone();
        state[0] = getX();
        state[1] = getY();
        state[2] = getZ();
        return state;
    }

    void applyNative(double[] state, int offset) {
        Vec3 prior = position();
        Quaternionf priorRotation = entityData.get(ROTATION);
        System.arraycopy(state, offset, motion, 0, motion.length);
        entityData.set(ROTATION, motionRotation());
        setPos(motion[0], motion[1], motion[2]);
        if (isVehicle()) getPassengers().forEach(this::positionRider);
        HullCollisions.onPoseChanged(this, prior, priorRotation);
    }

    final CraftLoads loads = new CraftLoads();
    /** Spinning machinery on board, from other mods, that pushes the craft. */
    final Propulsion propulsion = new Propulsion();
    /** The last game time at which something moved against this craft. */
    long touchedAt = Long.MIN_VALUE;
    private final List<Vec3[]> pushes = new ArrayList<>();

    boolean anchored() {
        return entityData.get(ANCHORED);
    }

    void setAnchored(boolean anchored) {
        entityData.set(ANCHORED, anchored);
        if (anchored) {
            Arrays.fill(motion, 7, motion.length, 0);
            pushes.clear();
        }
    }

    int staffHolder() {
        return entityData.get(STAFF_HOLDER);
    }

    Vec3 staffPoint() {
        var point = entityData.get(STAFF_POINT);
        return new Vec3(point.x(), point.y(), point.z());
    }

    void setStaffGrip(int holder, Vec3 point) {
        entityData.set(STAFF_POINT, new Vector3f((float) point.x, (float) point.y, (float) point.z));
        entityData.set(STAFF_HOLDER, holder);
    }

    /** Something leans on this craft from the side: an impulse at a world point, applied before the next step. */
    void queuePush(Vec3 point, Vec3 impulse) {
        if (!anchored() && pushes.size() < 64) pushes.add(new Vec3[] {point, impulse});
    }

    int pendingPushes() {
        return pushes.size();
    }

    void applyPushes(long world, long body) {
        if (pushes.isEmpty()) return;
        for (Vec3[] push : pushes) {
            NativePhysics.impulseAt(world, body, push[1].x, push[1].y, push[1].z, push[0].x, push[0].y, push[0].z);
        }
        pushes.clear();
    }

    UUID ownerId() {
        return owner;
    }

    Vec3 comVelocity() {
        return new Vec3(motion[7], motion[8], motion[9]);
    }

    Vec3 angularVelocity() {
        return new Vec3(motion[10], motion[11], motion[12]);
    }

    Vec3 velocityAt(Vec3 world) {
        return comVelocity().add(angularVelocity().cross(world.subtract(worldCenterOfMass())));
    }

    /** Starts a piece that broke off {@code parent}: same pose, and each point moves as it did on the parent. */
    void inheritMotion(CraftEntity parent) {
        System.arraycopy(parent.motion, 3, motion, 3, 4);
        entityData.set(ROTATION, motionRotation());
        Vec3 velocity = parent.velocityAt(worldCenterOfMass());
        motion[7] = velocity.x;
        motion[8] = velocity.y;
        motion[9] = velocity.z;
        System.arraycopy(parent.motion, 10, motion, 10, 3);
        setAnchored(parent.anchored());
    }

    Vec3 worldCenterOfMass() {
        return position().add(Hull.rotate(motionRotation(), hull.localCom));
    }

    double speed() {
        return Math.sqrt(motion[7] * motion[7] + motion[8] * motion[8] + motion[9] * motion[9]);
    }

    double spin() {
        return Math.sqrt(motion[10] * motion[10] + motion[11] * motion[11] + motion[12] * motion[12]);
    }

    private Quaternionf motionRotation() {
        return new Quaternionf((float) motion[3], (float) motion[4], (float) motion[5], (float) motion[6]);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (unreadable != null) {
            tag.merge(unreadable);
            return;
        }
        tag.putInt("PlotId", plotId);
        tag.putLong("PlotOrigin", plotOrigin.asLong());
        tag.putLongArray("PlotChunks", chunkKeys.toLongArray());
        tag.put("MotionSI", newDoubleList(Arrays.copyOfRange(motion, 3, motion.length)));
        if (anchored()) tag.putBoolean("Anchored", true);
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.hasUUID("Owner")) {
            owner = tag.getUUID("Owner");
        }
        ListTag state = tag.getList("MotionSI", Tag.TAG_DOUBLE);
        if (state.size() == 10) {
            double[] saved = new double[10];
            for (int i = 0; i < saved.length; i++) {
                saved[i] = state.getDouble(i);
            }
            double norm = new Quaternionf((float) saved[0], (float) saved[1], (float) saved[2], (float) saved[3]).lengthSquared();
            if (norm > 0.999 && norm < 1.001 && Arrays.stream(saved).allMatch(v -> Double.isFinite(v) && Math.abs(v) <= 80.0)) {
                System.arraycopy(saved, 0, motion, 3, saved.length);
                entityData.set(ROTATION, motionRotation());
            }
        }
        setAnchored(tag.getBoolean("Anchored"));
        if (!(level() instanceof ServerLevel server)) return;
        ServerLevel plot = Plots.level(server.getServer());
        if (plot == null) {
            unreadable = tag.copy();
            Aerolith.LOG.warn("Cannot reach the plot dimension for Aerolith craft {}; keeping its saved data unchanged", getUUID());
            return;
        }
        if (tag.contains("PlotId", Tag.TAG_INT)) {
            plotId = tag.getInt("PlotId");
            plotOrigin = tag.contains("PlotOrigin", Tag.TAG_LONG) ? BlockPos.of(tag.getLong("PlotOrigin")) : PlotGrid.origin(plotId);
            for (long key : tag.getLongArray("PlotChunks")) chunkKeys.add(key);
            loadCells();
            buildHull();
        } else if (tag.contains("Hull", Tag.TAG_LIST)) {
            migrate(plot, tag.getList("Hull", Tag.TAG_COMPOUND));
        }
        if (hull == null) {
            Aerolith.LOG.warn("Aerolith craft {} at {} has no solid blocks in its plot", getUUID(), position());
        }
    }

    /** Crafts saved before plot storage kept their blocks in a list; they move into a plot the first time they load. */
    private void migrate(ServerLevel plot, ListTag saved) {
        List<Plots.Moved> moved = new ArrayList<>(saved.size());
        for (int i = 0; i < saved.size(); i++) {
            CompoundTag entry = saved.getCompound(i);
            BlockPos offset = BlockPos.of(entry.getLong("Offset"));
            BlockState block = NbtUtils.readBlockState(registryAccess().lookupOrThrow(Registries.BLOCK), entry.getCompound("State"));
            if (PlotGrid.validOffset(offset) && !block.isAir()) moved.add(new Plots.Moved(offset, block, null));
        }
        if (moved.isEmpty()) return;
        plotId = Plots.allocate(plot);
        plotOrigin = PlotGrid.origin(plotId, moved.stream().map(Plots.Moved::offset).toList());
        for (Plots.Moved block : moved) chunkKeys.add(PlotGrid.chunkKey(plotOrigin.offset(block.offset())));
        Plots.write(plot, plotOrigin, moved);
        loadCells();
        buildHull();
        Aerolith.LOG.info("Moved Aerolith craft {} with {} blocks into plot {}", getUUID(), moved.size(), plotId);
    }
}
