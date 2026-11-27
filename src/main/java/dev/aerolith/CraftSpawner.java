package dev.aerolith;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * {@code /aerolith spawn}: any number of separate crafts of one size, in rows before whoever asked, then in layers above. It
 * goes on over as many ticks as it takes, so that thousands of them do not stop the server.
 */
@EventBusSubscriber(modid = Aerolith.ID)
final class CraftSpawner {
    static final int MAX_SIDE = 2 * Hull.REACH + 1;
    /** All crafts made here carry it, so that they can be removed together. */
    static final String TAG = "aerolith_spawned";
    /** Rows start this far from whoever asked and go on for about as many blocks to the front and to the sides. */
    private static final int DISTANCE = 3;
    private static final int SPREAD = 96;
    private static final long TICK_BUDGET = 25_000_000;

    private static final ArrayDeque<CraftSpawner> RUNNING = new ArrayDeque<>();

    private final CommandSourceStack source;
    private final ServerLevel level;
    private final List<CraftBlock> blocks;
    private final Vec3i size;
    private final UUID owner;
    private final int count;
    private final Direction ahead;
    private final BlockPos first;
    private final int rows, columns, layers;
    private int cell, made;

    private CraftSpawner(CommandSourceStack source, int count, Vec3i size, BlockState state) {
        this.source = source;
        this.level = source.getLevel();
        this.count = count;
        this.size = size;
        this.owner = source.getEntity() == null ? Util.NIL_UUID : source.getEntity().getUUID();
        blocks = new ArrayList<>(size.getX() * size.getY() * size.getZ());
        for (int x = 0; x < size.getX(); x++) {
            for (int y = 0; y < size.getY(); y++) {
                for (int z = 0; z < size.getZ(); z++) {
                    blocks.add(new CraftBlock(new BlockPos(x - size.getX() / 2, y - size.getY() / 2, z - size.getZ() / 2), state));
                }
            }
        }
        ahead = Direction.fromYRot(source.getRotation().y);
        first = BlockPos.containing(source.getPosition()).above().relative(ahead, DISTANCE);
        rows = Math.ceilDiv(SPREAD, along(ahead) + 1);
        columns = Math.ceilDiv(SPREAD, along(ahead.getClockWise()) + 1);
        layers = Math.max(0, (level.getMaxBuildHeight() - first.getY()) / (size.getY() + 1));
    }

    /** Starts spawning; the number of crafts asked for, or 0 if it cannot be done. */
    static int start(CommandSourceStack source, int count, Vec3i size, BlockState state) {
        ServerLevel level = source.getLevel();
        long volume = (long) size.getX() * size.getY() * size.getZ();
        if (volume > Hull.MAX_BLOCKS) return refuse(source, "spawn_blocks", volume, Hull.MAX_BLOCKS);
        if (!CraftEntity.solid(state)) return refuse(source, "spawn_not_solid");
        if (Plots.level(level.getServer()) == null) return refuse(source, "no_plot");
        if (Plots.isPlot(level) && Plots.inGrid(BlockPos.containing(source.getPosition()))) return refuse(source, "in_plot");
        int space = WorldPhysics.of(level).space();
        if (count > space) return refuse(source, "spawn_space", space, WorldPhysics.MAX_BODIES);
        RUNNING.add(new CraftSpawner(source, count, size, state));
        source.sendSuccess(() -> Component.translatable("message.aerolith.spawn_started", count, size.getX(), size.getY(), size.getZ()), true);
        return count;
    }

    private static int refuse(CommandSourceStack source, String key, Object... args) {
        source.sendFailure(Component.translatable("message.aerolith." + key, args));
        return 0;
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        long end = System.nanoTime() + TICK_BUDGET;
        while (!RUNNING.isEmpty() && System.nanoTime() < end) {
            if (!RUNNING.peek().next()) RUNNING.remove().finish();
        }
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        RUNNING.clear();
    }

    /** Fills or passes over one place; false when there is nothing left to do. */
    private boolean next() {
        if (made == count || cell == (long) rows * columns * layers || !WorldPhysics.of(level).hasSpace()) return false;
        int column = cell % columns, row = cell / columns % rows, layer = cell / columns / rows;
        cell++;
        // From the middle outwards: 0, 1, -1, 2, -2...
        int aside = (column + 1) / 2 * (column % 2 == 0 ? -1 : 1);
        Direction right = ahead.getClockWise();
        int wide = along(right);
        BlockPos near = first.relative(ahead, row * (along(ahead) + 1)).relative(right, aside * (wide + 1) - wide / 2)
                .above(layer * (size.getY() + 1));
        BlockPos far = near.relative(ahead, along(ahead) - 1).relative(right, wide - 1).above(size.getY() - 1);
        AABB box = AABB.encapsulatingFullBlocks(near, far);
        if (!free(box)) return true;
        Vec3 origin = new Vec3(box.minX + size.getX() / 2 + 0.5, box.minY + size.getY() / 2 + 0.5, box.minZ + size.getZ() / 2 + 0.5);
        CraftEntity craft = StructureTransfer.create(level, blocks, origin, owner);
        if (craft == null) return false;
        craft.addTag(TAG);
        made++;
        return true;
    }

    private boolean free(AABB box) {
        AABB inside = box.deflate(0.01);
        return level.getWorldBorder().isWithinBounds(box) && level.hasChunksAt(BlockPos.containing(box.getMinPosition()), BlockPos.containing(inside.getMaxPosition()))
                && level.isPositionEntityTicking(BlockPos.containing(box.getCenter())) && level.noBlockCollision(null, inside)
                && level.getEntitiesOfClass(LivingEntity.class, inside).isEmpty() && !HullCollisions.overlapsCraft(level, inside, null);
    }

    private int along(Direction direction) {
        return direction.getAxis().choose(size.getX(), size.getY(), size.getZ());
    }

    private void finish() {
        if (made == count) {
            source.sendSuccess(() -> Component.translatable("message.aerolith.spawn_done", made, blocks.size(), TAG), true);
        } else {
            source.sendFailure(Component.translatable("message.aerolith.spawn_no_room", made, count));
        }
    }
}
