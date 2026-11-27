package dev.aerolith;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Moving a structure between the world and a craft's plot, with the data of its block entities. */
final class StructureTransfer {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    /** A block to turn into another, with the block entity data it has and the data it should get. */
    private record Change(BlockPos pos, BlockState before, BlockState after, CompoundTag beforeEntity, CompoundTag afterEntity) {}

    private static final long MAX_SCAN = (long) (2 * PlotGrid.REACH + 1) * (2 * PlotGrid.REACH + 1) * (2 * PlotGrid.REACH + 1);

    /** True while a craft is turned back into blocks: those blocks go where the craft still stands. */
    static boolean docking;

    private StructureTransfer() {}

    static CraftEntity assemble(ServerLevel level, ServerPlayer player, BlockPos a, BlockPos b) {
        int x = Math.min(a.getX(), b.getX()), y = Math.min(a.getY(), b.getY()), z = Math.min(a.getZ(), b.getZ());
        int dx = Math.abs(a.getX() - b.getX()), dy = Math.abs(a.getY() - b.getY()), dz = Math.abs(a.getZ() - b.getZ());
        // No limit on the shape of the area, only on how many cells one assembly may scan and how many blocks it may hold.
        if ((long) (dx + 1) * (dy + 1) * (dz + 1) > MAX_SCAN) {
            AssemblerItem.message(player, "too_large");
            return null;
        }
        ServerLevel plot = Plots.level(level.getServer());
        if (plot == null) {
            AssemblerItem.message(player, "no_plot");
            return null;
        }
        if (Plots.isPlot(level) && (Plots.inGrid(a) || Plots.inGrid(b))) {
            AssemblerItem.message(player, "in_plot");
            return null;
        }
        return assemble(level, player, BlockPos.betweenClosed(a, b), new BlockPos(x + dx / 2, y + dy / 2, z + dz / 2));
    }

    /**
     * The blocks touching the one at {@code start}, up to {@code radius} blocks away from it on each axis, as a craft. This is
     * what the survival assembler block does; open water and air are left out, and what the structure stands on, if it is
     * ground, comes along: pick a radius that ends before it.
     */
    static CraftEntity assembleConnected(ServerLevel level, ServerPlayer player, BlockPos start, int radius) {
        if (Plots.level(level.getServer()) == null) {
            AssemblerItem.message(player, "no_plot");
            return null;
        }
        if (Plots.isPlot(level) && Plots.inGrid(start)) {
            AssemblerItem.message(player, "in_plot");
            return null;
        }
        LongOpenHashSet seen = new LongOpenHashSet();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        List<BlockPos> found = new ArrayList<>();
        int minX = start.getX(), minY = start.getY(), minZ = start.getZ(), maxX = minX, maxY = minY, maxZ = minZ;
        seen.add(start.asLong());
        queue.add(start);
        while (!queue.isEmpty()) {
            BlockPos at = queue.remove();
            BlockState state = level.getBlockState(at);
            if (state.isAir() || CraftEntity.isFluid(state)) continue;
            if (found.size() == Hull.MAX_BLOCKS) {
                AssemblerItem.message(player, "too_large");
                return null;
            }
            found.add(at);
            minX = Math.min(minX, at.getX());
            minY = Math.min(minY, at.getY());
            minZ = Math.min(minZ, at.getZ());
            maxX = Math.max(maxX, at.getX());
            maxY = Math.max(maxY, at.getY());
            maxZ = Math.max(maxZ, at.getZ());
            for (Direction face : Direction.values()) {
                BlockPos next = at.relative(face);
                if (Math.abs(next.getX() - start.getX()) <= radius && Math.abs(next.getY() - start.getY()) <= radius
                        && Math.abs(next.getZ() - start.getZ()) <= radius && level.isInWorldBounds(next) && level.isLoaded(next)
                        && seen.add(next.asLong())) {
                    queue.add(next);
                }
            }
        }
        return assemble(level, player, found, new BlockPos((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2));
    }

    /** The blocks at {@code cells} as one craft that has its origin at {@code anchor}. */
    static CraftEntity assemble(ServerLevel level, ServerPlayer player, Iterable<BlockPos> cells, BlockPos anchor) {
        ServerLevel plot = Plots.level(level.getServer());
        if (plot == null) {
            AssemblerItem.message(player, "no_plot");
            return null;
        }
        WorldPhysics physics = WorldPhysics.of(level);
        if (!physics.hasSpace()) {
            AssemblerItem.message(player, "capacity");
            return null;
        }
        List<CraftBlock> blocks = new ArrayList<>();
        List<Plots.Moved> moved = new ArrayList<>();
        List<Change> removals = new ArrayList<>();
        boolean solid = false;
        for (BlockPos cursor : cells) {
            if (!accessible(level, player, cursor)) return null;
            BlockState state = level.getBlockState(cursor);
            // Open water stays where it is: a box around a raft would otherwise carry off the sea under it.
            if (state.isAir() || CraftEntity.isFluid(state)) continue;
            if (HullCollisions.overlapsCraft(level, new AABB(cursor).deflate(0.01), null)) {
                AssemblerItem.at(player, "obstructed", cursor);
                return null;
            }
            if (blocks.size() == Hull.MAX_BLOCKS) {
                AssemblerItem.message(player, "too_large");
                return null;
            }
            BlockPos position = cursor.immutable();
            BlockPos offset = position.subtract(anchor);
            if (!PlotGrid.validOffset(offset)) {
                AssemblerItem.message(player, "too_large");
                return null;
            }
            Plots.Moved block = Plots.capture(level, position, offset);
            blocks.add(new CraftBlock(offset, state));
            moved.add(block);
            removals.add(new Change(position, state, AIR, block.entity(), null));
            solid |= CraftEntity.solid(state);
        }
        // What stands on other blocks (redstone dust, levers, torches) goes first. Taking a wire out tells the wires next to it,
        // and one whose floor was already taken out would break before its turn, which would fail the whole assembly.
        removals.sort(Comparator.comparingInt(change -> Materials.hasCollision(change.before()) ? 1 : 0));
        if (blocks.isEmpty() || !solid) {
            AssemblerItem.message(player, "empty");
            return null;
        }
        if (!Hull.connected(blocks)) {
            AssemblerItem.message(player, "disconnected");
            return null;
        }
        for (Change removal : removals) {
            if (NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, removal.pos, removal.before, player)).isCanceled()) {
                AssemblerItem.at(player, "permission", removal.pos);
                return null;
            }
        }
        if (!physics.ready()) {
            AssemblerItem.message(player, "failed");
            return null;
        }
        CraftEntity craft = Aerolith.CRAFT.get().create(level);
        if (craft == null) {
            AssemblerItem.message(player, "failed");
            return null;
        }
        int id = Plots.allocate(plot);
        BlockPos origin = PlotGrid.origin(id, blocks.stream().map(CraftBlock::offset).toList());
        LongSet chunks = new LongOpenHashSet();
        for (Plots.Moved block : moved) chunks.add(PlotGrid.chunkKey(origin.offset(block.offset())));
        Plots.write(plot, origin, moved);
        craft.start(Vec3.atCenterOf(anchor), player.getUUID(), id, origin, chunks);
        // Discarding the craft clears the plot again, so a failure here leaves nothing behind.
        if (!level.addFreshEntity(craft) || !physics.bind(craft) || !replace(level, removals, null)) {
            craft.discard();
            AssemblerItem.message(player, "failed");
            return null;
        }
        notifyNeighbors(level, removals);
        AssemblerItem.message(player, "assembled", blocks.size(), craft.getId());
        return craft;
    }

    /** A craft made straight from blocks, without taking them from the world; for tests and tools. */
    static CraftEntity create(ServerLevel level, List<CraftBlock> blocks, Vec3 origin, java.util.UUID owner) {
        ServerLevel plot = Plots.level(level.getServer());
        WorldPhysics physics = WorldPhysics.of(level);
        CraftEntity craft = Aerolith.CRAFT.get().create(level);
        if (plot == null || craft == null || !physics.hasSpace() || !physics.ready()) return null;
        int id = Plots.allocate(plot);
        BlockPos plotOrigin = PlotGrid.origin(id, blocks.stream().map(CraftBlock::offset).toList());
        LongSet chunks = new LongOpenHashSet();
        List<Plots.Moved> moved = new ArrayList<>(blocks.size());
        for (CraftBlock block : blocks) {
            moved.add(new Plots.Moved(block.offset(), block.state(), null));
            chunks.add(PlotGrid.chunkKey(plotOrigin.offset(block.offset())));
        }
        Plots.write(plot, plotOrigin, moved);
        craft.start(origin, owner, id, plotOrigin, chunks);
        if (!level.addFreshEntity(craft) || !physics.bind(craft)) {
            craft.discard();
            return null;
        }
        return craft;
    }

    static boolean dock(ServerLevel level, ServerPlayer player, CraftEntity craft) {
        if (!craft.canEdit(player)) {
            AssemblerItem.message(player, "owner");
            return false;
        }
        if (craft.isVehicle()) {
            AssemblerItem.message(player, "occupied");
            return false;
        }
        if (craft.speed() > 0.6 || craft.spin() > 0.45) {
            AssemblerItem.message(player, "stationary");
            return false;
        }
        ServerLevel plot = craft.plotLevel();
        if (plot == null || craft.hull() == null) {
            AssemblerItem.message(player, "failed");
            return false;
        }
        // Make sure the hull and the blocks agree before they are copied out.
        craft.settle();
        BlockPos anchor = dockingAnchor(craft);
        Rotation rotation = dockingRotation(craft);
        List<Change> placements = new ArrayList<>();
        for (var cell : craft.cells().entrySet()) {
            // Fluid that was poured into the craft is not carried back into the world.
            if (CraftEntity.isFluid(cell.getValue())) continue;
            BlockPos pos = anchor.offset(cell.getKey().rotate(rotation));
            if (!accessible(level, player, pos)) return false;
            BlockState prior = level.getBlockState(pos);
            BlockState restored = cell.getValue().rotate(rotation);
            if (!prior.isAir() || Materials.boxes(restored).stream().anyMatch(box ->
                    !level.getEntities(craft, box.move(pos).deflate(0.001),
                            e -> !e.isSpectator() && (e instanceof LivingEntity || e instanceof CraftEntity)).isEmpty())) {
                AssemblerItem.at(player, "obstructed", pos);
                return false;
            }
            placements.add(new Change(pos, prior, restored, null, Plots.capture(plot, craft.plotPos(cell.getKey()), cell.getKey()).entity()));
        }
        // The other way round: a wire put down before its floor would break when it tells its neighbours.
        placements.sort(Comparator.comparingInt(change -> Materials.hasCollision(change.after()) ? 0 : 1));
        boolean placed;
        docking = true;
        try {
            placed = !placements.isEmpty() && replace(level, placements, player);
        } finally {
            docking = false;
        }
        if (!placed) {
            AssemblerItem.message(player, "failed");
            return false;
        }
        craft.discard();
        notifyNeighbors(level, placements);
        AssemblerItem.message(player, "recovered");
        return true;
    }

    static BlockPos dockingAnchor(CraftEntity craft) {
        // At a half-block surface the center sits on the snapping tie, slightly below it under contact slop.
        return BlockPos.containing(craft.position().add(0, 0.075, 0));
    }

    private static Rotation dockingRotation(CraftEntity craft) {
        var rotation = craft.orientation(1.0f);
        Vec3 forward = Hull.rotate(rotation, new Vec3(0, 0, 1));
        if (Math.hypot(forward.x, forward.z) < 0.15) {
            Vec3 right = Hull.rotate(rotation, new Vec3(1, 0, 0));
            forward = new Vec3(-right.z, 0, right.x);
        }
        int quarterTurns = Math.floorMod((int) Math.round(Math.atan2(forward.x, forward.z) / (Math.PI / 2)), 4);
        return switch (quarterTurns) {
            case 1 -> Rotation.COUNTERCLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.CLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    private static boolean accessible(ServerLevel level, ServerPlayer player, BlockPos pos) {
        if (!WorldPhysics.chunkLoaded(level, pos) || level.isOutsideBuildHeight(pos)) {
            AssemblerItem.at(player, "unloaded", pos);
            return false;
        }
        if (!player.getAbilities().mayBuild || !level.getWorldBorder().isWithinBounds(pos)
                || !level.mayInteract(player, pos) || !player.mayUseItemAt(pos, Direction.UP, player.getMainHandItem())) {
            AssemblerItem.at(player, "permission", pos);
            return false;
        }
        return true;
    }

    private static void load(ServerLevel level, BlockPos pos, CompoundTag tag) {
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity != null) {
            entity.loadWithComponents(tag, level.registryAccess());
            entity.setChanged();
        }
    }

    private static boolean replace(ServerLevel level, List<Change> plan, ServerPlayer placing) {
        if (level.captureBlockSnapshots) return false;
        int written = 0;
        boolean committed = false;
        Change inFlight = null;
        List<BlockSnapshot> checkpoints = placing == null ? List.of() : new ArrayList<>(plan.size());
        try {
            for (Change change : plan) {
                if (level.getBlockState(change.pos) != change.before) return false;
                if (placing != null) checkpoints.add(BlockSnapshot.create(level.dimension(), level, change.pos));
                inFlight = change;
                // A block entity is removed before its block so that a chest does not drop what it holds.
                if (change.beforeEntity != null) level.removeBlockEntity(change.pos);
                if (!level.setBlock(change.pos, change.after, QUIET)) return false;
                inFlight = null;
                if (change.afterEntity != null) load(level, change.pos, change.afterEntity);
                written++;
            }
            if (placing != null) {
                for (BlockSnapshot snapshot : checkpoints) {
                    BlockState against = level.getBlockState(snapshot.getPos().below());
                    if (NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(snapshot, against, placing)).isCanceled()) return false;
                }
            }
            for (Change change : plan) if (level.getBlockState(change.pos) != change.after) return false;
            committed = true;
            return true;
        } catch (RuntimeException failure) {
            Aerolith.LOG.warn("Aerolith rolled back an interrupted structure transfer", failure);
            return false;
        } finally {
            if (!committed) {
                if (inFlight != null && inFlight.beforeEntity != null && level.getBlockState(inFlight.pos) == inFlight.before) {
                    // Its block entity was taken off but the block never changed; asking for it makes a fresh one.
                    load(level, inFlight.pos, inFlight.beforeEntity);
                }
                for (int i = written - 1; i >= 0; i--) {
                    Change change = plan.get(i);
                    if (level.getBlockState(change.pos) == change.after) {
                        if (change.afterEntity != null) level.removeBlockEntity(change.pos);
                        if (!level.setBlock(change.pos, change.before, QUIET)) {
                            Aerolith.LOG.error("Could not roll back Aerolith block at {}", change.pos);
                        } else if (change.beforeEntity != null) {
                            load(level, change.pos, change.beforeEntity);
                        }
                    }
                }
            }
        }
    }

    private static void notifyNeighbors(ServerLevel level, List<Change> plan) {
        for (Change change : plan) {
            level.markAndNotifyBlock(change.pos, level.getChunkAt(change.pos), change.before, change.after, Block.UPDATE_NEIGHBORS, 512);
        }
    }
}
