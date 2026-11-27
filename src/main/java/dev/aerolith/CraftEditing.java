package dev.aerolith;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/** Breaking, using and placing blocks of an assembled craft while it keeps moving. */
final class CraftEditing {
    record Target(Hull.Pick pick, Vec3 localHit, Vec3 localView) {}

    record Located(CraftEntity craft, Target target) {}

    private CraftEditing() {}

    /**
     * The craft whose actual block shapes a ray reaches first. Vanilla picks entities by their bounding box, which for a
     * ring or a tilted hull covers other crafts and the terrain behind its holes.
     */
    static Located nearest(Level level, Vec3 eye, Vec3 view, double reach, float partialTick) {
        return nearest(level, eye, view, reach, partialTick, false);
    }

    static Located nearest(Level level, Vec3 eye, Vec3 view, double reach, float partialTick, boolean fluids) {
        AABB area = new AABB(eye, eye.add(view.scale(reach))).inflate(1);
        Located best = null;
        for (CraftEntity craft : HullCollisions.crafts(level)) {
            if (craft.isRemoved() || !craft.getBoundingBox().intersects(area)) continue;
            Target target = target(craft, eye, view, reach, partialTick, fluids);
            if (target != null && (best == null || target.pick().distance() < best.target().pick().distance())) {
                best = new Located(craft, target);
            }
        }
        return best;
    }

    /** True while the player holds an empty bucket, which scoops fluid and therefore aims at it. */
    static boolean aimsAtFluid(net.minecraft.world.entity.player.Player player) {
        return player.getMainHandItem().getItem() instanceof BucketItem bucket && bucket.content == Fluids.EMPTY;
    }

    static Target target(CraftEntity craft, Vec3 eye, Vec3 view, double reach, float partialTick) {
        return target(craft, eye, view, reach, partialTick, false);
    }

    /** The block shape of {@code craft} that a ray hits, found in the craft's own frame so rotation is exact. */
    static Target target(CraftEntity craft, Vec3 eye, Vec3 view, double reach, float partialTick, boolean fluids) {
        Hull hull = craft.hull();
        if (hull == null) return null;
        Quaternionf inverse = craft.orientation(partialTick).conjugate();
        Vec3 origin = Hull.rotate(inverse, eye.subtract(craft.getPosition(partialTick)));
        Vec3 direction = Hull.rotate(inverse, view);
        Hull.Pick pick = hull.pick(origin, direction, reach, fluids);
        return pick == null ? null : new Target(pick, origin.add(direction.scale(pick.distance())), direction);
    }

    // ---- hitting

    /** Impulse of one punch, applied at the hit point along the player's view. */
    private static final double HIT_IMPULSE = 60.0;
    private static final int KNOCK_INTERVAL = 6;
    private static final int CREATIVE_DELAY = 5;

    private static final class Mining {
        final int craft;
        final CraftBlock block;
        float progress;
        long lastTick;
        long nextKnock;

        Mining(int craft, CraftBlock block) {
            this.craft = craft;
            this.block = block;
        }
    }

    private static final Map<ServerPlayer, Mining> MINING = new WeakHashMap<>();
    private static final Map<ServerPlayer, Long> LAST_CREATIVE_BREAK = new WeakHashMap<>();

    /**
     * One tick of hitting a craft block. Creative breaks it at once (and again every few ticks while held), survival
     * mines it like any block: the time follows the block's hardness and the tool, and every punch pushes the craft.
     */
    static void hit(ServerPlayer player, CraftEntity craft, Target chosen) {
        if (player.isSpectator() || !player.getAbilities().mayBuild || player.getMainHandItem().is(Aerolith.TOW_STAFF.get())) return;
        if (!craft.canEdit(player)) {
            AssemblerItem.message(player, "owner");
            return;
        }
        ServerLevel plot = craft.plotLevel();
        if (plot == null) return;
        Target target = chosen != null ? chosen
                : target(craft, player.getEyePosition(), player.getViewVector(1f), player.blockInteractionRange(), 1f);
        if (target == null) return;
        ServerLevel level = player.level();
        long now = level.getGameTime();
        CraftBlock block = target.pick().block();
        BlockPos pos = craft.plotPos(block.offset());
        BlockState state = plot.getBlockState(pos);
        if (state.isAir()) return;
        Vec3 world = worldCenter(craft, block.offset());

        if (player.isCreative()) {
            Long last = LAST_CREATIVE_BREAK.put(player, now);
            if (last != null && now - last < CREATIVE_DELAY) {
                LAST_CREATIVE_BREAK.put(player, last);
                return;
            }
            knock(player, craft, target);
            breakBlock(player, craft, block);
            return;
        }

        Mining mining = MINING.get(player);
        boolean started = mining == null || mining.craft != craft.getId() || !mining.block.equals(block) || now - mining.lastTick > 3;
        if (started) {
            mining = new Mining(craft.getId(), block);
            MINING.put(player, mining);
        }
        float step = state.getDestroyProgress(player, plot, pos);
        if (step <= 0f) {
            AssemblerItem.message(player, "unbreakable");
            return;
        }
        int oldStage = (int) (mining.progress * 10);
        mining.progress += step;
        mining.lastTick = now;
        if (now % 4 == 0) {
            level.playSound(null, world.x, world.y, world.z, state.getSoundType().getHitSound(), SoundSource.BLOCKS, 0.5f, 0.75f);
            Vec3 face = craft.position().add(Hull.rotate(craft.orientation(1f), target.localHit()));
            particles(level, state, face, 3, 0.08, 0.05);
        }
        if (now >= mining.nextKnock) {
            knock(player, craft, target);
            mining.nextKnock = now + KNOCK_INTERVAL;
        }
        if (mining.progress >= 1f) {
            MINING.remove(player);
            player.sendOverlayMessage(Component.empty());
            breakBlock(player, craft, block);
        } else {
            int stage = (int) (mining.progress * 10);
            if (started || stage != oldStage || now % 4 == 0) sendCrack(player, craft, block.offset(), stage);
            AssemblerItem.message(player, "mining", Math.round(mining.progress * 100));
        }
    }

    private static void sendCrack(ServerPlayer player, CraftEntity craft, BlockPos offset, int stage) {
        PlotSync.sendToTrackers(craft, new CraftCrack(player.getId(), craft.getId(), offset, stage));
    }

    /** Terrain particles carry the block state but spawn at the real, fractional position of the moving block. */
    private static void particles(ServerLevel level, BlockState state, Vec3 at, int count, double spread, double speed) {
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), at.x, at.y, at.z, count, spread, spread, spread, speed);
    }

    /** A punch shoves the craft: the impulse goes in at the hit point, so a hit near an edge also turns it. */
    private static void knock(ServerPlayer player, CraftEntity craft, Target target) {
        Vec3 point = craft.position().add(Hull.rotate(craft.orientation(1f), target.localHit()));
        craft.queuePush(point, player.getViewVector(1f).scale(HIT_IMPULSE));
    }

    static void breakBlock(ServerPlayer player, CraftEntity craft) {
        hit(player, craft, null);
    }

    /**
     * Breaks a block as the game does for a player, but in the plot: the block's own hooks run, the right tool decides
     * the drops, containers spill, and everything that appears is moved out to the craft in the world.
     */
    private static void breakBlock(ServerPlayer player, CraftEntity craft, CraftBlock broken) {
        ServerLevel level = player.level();
        ServerLevel plot = craft.plotLevel();
        BlockPos pos = craft.plotPos(broken.offset());
        BlockState state = plot.getBlockState(pos);
        if (state.isAir()) return;
        Vec3 world = worldCenter(craft, broken.offset());
        level.playSound(null, world.x, world.y, world.z, state.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1f, 0.8f);
        particles(level, state, world, 40, 0.28, 0.08);
        sendCrack(player, craft, broken.offset(), -1);

        BlockEntity entity = plot.getBlockEntity(pos);
        state.getBlock().playerWillDestroy(plot, pos, state, player);
        boolean removed = plot.removeBlock(pos, false);
        if (removed) state.getBlock().destroy(plot, pos, state);
        if (!player.isCreative()) {
            ItemStack tool = player.getMainHandItem();
            ItemStack used = tool.copy();
            boolean drops = player.hasCorrectToolForDrops(state);
            tool.mineBlock(plot, state, pos, player);
            if (removed && drops) state.getBlock().playerDestroy(plot, player, pos, state, entity, used);
        }
        craft.settle();
    }

    // ---- using

    /** A placement context that looks along the player's view in the craft's own frame, not the world's. */
    private static final class CraftPlaceContext extends BlockPlaceContext {
        private final Vec3 view;

        CraftPlaceContext(ServerLevel plot, ServerPlayer player, InteractionHand hand, ItemStack stack, BlockHitResult hit, Vec3 localView) {
            super(plot, player, hand, stack, hit);
            this.view = localView;
        }

        @Override
        public Direction getNearestLookingDirection() {
            return getNearestLookingDirections()[0];
        }

        @Override
        public Direction getNearestLookingVerticalDirection() {
            return view.y < 0 ? Direction.DOWN : Direction.UP;
        }

        @Override
        public Direction[] getNearestLookingDirections() {
            Direction[] all = Direction.values().clone();
            java.util.Arrays.sort(all, Comparator.comparingDouble((Direction d) -> -(view.x * d.getStepX() + view.y * d.getStepY() + view.z * d.getStepZ())));
            if (replaceClicked) return all;
            // As in the game: the face that was clicked, and the one opposite it, are tried last.
            Direction clicked = getClickedFace();
            List<Direction> ordered = new ArrayList<>(List.of(all));
            ordered.remove(clicked);
            ordered.remove(clicked.getOpposite());
            ordered.add(0, clicked.getOpposite());
            return ordered.toArray(new Direction[0]);
        }

        @Override
        public Direction getHorizontalDirection() {
            return Direction.getApproximateNearest(view.x, 0, view.z);
        }

        @Override
        public float getRotation() {
            return (float) Math.toDegrees(Math.atan2(-view.x, view.z));
        }
    }

    /**
     * A right click on a block of the craft, as the game handles one on a block of the world: the block's own use, then
     * the item's. Returns PASS when nothing took the click, so.
     */
    static InteractionResult use(ServerPlayer player, CraftEntity craft, InteractionHand hand, Target chosen) {
        ServerLevel plot = craft.plotLevel();
        if (plot == null || player.isSpectator()) return InteractionResult.PASS;
        ItemStack stack = player.getItemInHand(hand);
        Target target = chosen != null ? chosen : target(craft, player.getEyePosition(), player.getViewVector(1f),
                player.blockInteractionRange(), 1f, aimsAtFluid(player));
        if (target == null) return InteractionResult.PASS;
        BlockPos pos = craft.plotPos(target.pick().block().offset());
        Vec3 hitPoint = PlotGrid.toPlot(craft.plotOrigin(), target.localHit());
        BlockHitResult hit = new BlockHitResult(hitPoint, target.pick().face(), pos, false);
        BlockState state = plot.getBlockState(pos);
        ServerLevel level = player.level();

        if (stack.getItem() instanceof BucketItem bucket) {
            return useBucket(player, craft, plot, hand, stack, bucket, hit);
        }

        boolean holding = !player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty();
        boolean skipBlock = player.isSecondaryUseActive() && holding
                && !(player.getMainHandItem().doesSneakBypassUse(plot, pos, player) && player.getOffhandItem().doesSneakBypassUse(plot, pos, player));
        if (!skipBlock && !state.isAir() && !CraftEntity.isFluid(state)) {
            InteractionResult result = state.useItemOn(stack, plot, player, hand, hit);
            if (result.consumesAction()) return result;
            if (result == InteractionResult.TRY_WITH_EMPTY_HAND && hand == InteractionHand.MAIN_HAND) {
                InteractionResult plain = state.useWithoutItem(plot, player, hit);
                if (plain.consumesAction()) return plain;
            }
        }
        if (stack.isEmpty() || player.getCooldowns().isOnCooldown(stack) || !player.getAbilities().mayBuild) {
            return InteractionResult.PASS;
        }
        if (stack.getItem() instanceof BlockItem blockItem) {
            CraftPlaceContext context = new CraftPlaceContext(plot, player, hand, stack, hit, target.localView());
            BlockPos at = context.getClickedPos();
            if (!PlotGrid.validOffset(at.subtract(craft.plotOrigin())) || craft.cells().size() >= Hull.MAX_BLOCKS) {
                AssemblerItem.message(player, "too_large");
                return InteractionResult.CONSUME;
            }
            BlockState placing = blockItem.getBlock().defaultBlockState();
            if (!Materials.boxes(placing).isEmpty() && obstructed(level, craft, worldCenter(craft, at.subtract(craft.plotOrigin())))) {
                AssemblerItem.at(player, "obstructed", BlockPos.containing(worldCenter(craft, at.subtract(craft.plotOrigin()))));
                return InteractionResult.CONSUME;
            }
            InteractionResult placed = blockItem.place(context);
            if (placed.consumesAction()) craft.settle();
            return placed;
        }
        if (spawnsEntities(stack)) {
            // Entities made in the plot dimension would be out of sight, and the item would be spent for nothing.
            AssemblerItem.message(player, "no_entities");
            return InteractionResult.CONSUME;
        }
        UseOnContext context = new UseOnContext(plot, player, hand, stack, hit);
        int count = stack.getCount();
        InteractionResult result = stack.useOn(context);
        if (player.isCreative()) stack.setCount(count);
        if (result.consumesAction()) craft.settle();
        return result;
    }

    private static boolean spawnsEntities(ItemStack stack) {
        var item = stack.getItem();
        return item instanceof net.minecraft.world.item.HangingEntityItem || item instanceof net.minecraft.world.item.SpawnEggItem
                || item instanceof net.minecraft.world.item.ArmorStandItem || item instanceof net.minecraft.world.item.MinecartItem
                || item instanceof net.minecraft.world.item.BoatItem || item instanceof net.minecraft.world.item.EndCrystalItem;
    }

    /** Buckets read the world through the player's eyes, so they are done here against the plot. */
    private static InteractionResult useBucket(ServerPlayer player, CraftEntity craft, ServerLevel plot, InteractionHand hand,
            ItemStack stack, BucketItem bucket, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        BlockState state = plot.getBlockState(pos);
        if (bucket.content == Fluids.EMPTY) {
            if (state.getBlock() instanceof BucketPickup pickup) {
                ItemStack filled = pickup.pickupBlock(player, plot, pos, state);
                if (!filled.isEmpty()) {
                    pickup.getPickupSound(state).ifPresent(sound -> {
                        Vec3 at = worldCenter(craft, pos.subtract(craft.plotOrigin()));
                        player.level().playSound(null, at.x, at.y, at.z, sound, SoundSource.BLOCKS, 1f, 1f);
                    });
                    player.setItemInHand(hand, net.minecraft.world.item.ItemUtils.createFilledResult(stack, player, filled));
                    craft.settle();
                    return InteractionResult.SUCCESS;
                }
            }
            return InteractionResult.PASS;
        }
        BlockPos at = state.canBeReplaced(bucket.content) ? pos : pos.relative(hit.getDirection());
        if (!PlotGrid.validOffset(at.subtract(craft.plotOrigin()))) {
            AssemblerItem.message(player, "too_large");
            return InteractionResult.CONSUME;
        }
        if (!craft.holdsFluid(at)) {
            AssemblerItem.message(player, "fluid_outside");
            return InteractionResult.CONSUME;
        }
        if (bucket.emptyContents(player, plot, at, hit, stack)) {
            bucket.checkExtraContent(player, plot, stack, at);
            player.setItemInHand(hand, BucketItem.getEmptySuccessItem(stack, player));
            craft.settle();
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    // ---- keeping the hull in step with the blocks

    /**
     * Brings the hull up to date with the blocks of the plot. Parts that no longer touch the main hull leave it: with
     * something solid in them they become crafts of their own, otherwise they fall apart into drops.
     */
    static void settle(ServerLevel level, CraftEntity craft) {
        craft.clearDirty();
        ServerLevel plot = craft.plotLevel();
        if (plot == null) return;
        long begun = Perf.begin();
        List<CraftBlock> blocks = craft.cellBlocks(false);
        Perf.end("server.settle.cells", begun);
        begun = Perf.begin();
        List<List<CraftBlock>> groups = Hull.components(blocks);
        Perf.end("server.settle.components", begun);
        List<List<CraftBlock>> solid = groups.stream().filter(CraftEditing::hasSolid).toList();
        if (solid.isEmpty()) {
            craft.dissolve();
            return;
        }
        List<CraftBlock> main = solid.stream()
                .max(Comparator.comparingInt(List::size)).orElseThrow();
        if (groups.size() > 1) {
            Map<List<CraftBlock>, List<CraftBlock>> water = assignFluids(groups, craft.cellBlocks(true));
            for (List<CraftBlock> group : groups) {
                if (group == main) continue;
                if (hasSolid(group)) splitOff(level, craft, plot, group, water.getOrDefault(group, List.of()));
                else group.forEach(block -> plot.destroyBlock(craft.plotPos(block.offset()), true));
            }
            // Fluid that no longer touches any block of the craft is gone with whatever held it.
            Set<BlockPos> keep = new HashSet<>();
            water.getOrDefault(main, List.of()).forEach(block -> keep.add(block.offset()));
            for (CraftBlock fluid : craft.cellBlocks(true)) {
                if (!keep.contains(fluid.offset())) Plots.erase(plot, craft.plotPos(fluid.offset()));
            }
            craft.clearDirty();
        }
        craft.edit(craft.cellBlocks(false), craft.cellBlocks(true));
        // Fluid left outside the box the blocks now span, because a wall was broken or a part left, would hang in the air.
        for (CraftBlock fluid : craft.cellBlocks(true)) {
            if (!craft.holdsFluid(craft.plotPos(fluid.offset()))) Plots.erase(plot, craft.plotPos(fluid.offset()));
        }
    }

    private static boolean hasSolid(List<CraftBlock> group) {
        return group.stream().anyMatch(block -> CraftEntity.solid(block.state()));
    }

    /** Each fluid cell goes with the group it can be reached from through fluid, so a pool stays with its basin. */
    private static Map<List<CraftBlock>, List<CraftBlock>> assignFluids(List<List<CraftBlock>> groups, List<CraftBlock> fluids) {
        Map<BlockPos, CraftBlock> free = new HashMap<>();
        for (CraftBlock fluid : fluids) free.put(fluid.offset(), fluid);
        Map<List<CraftBlock>, List<CraftBlock>> out = new HashMap<>();
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        for (List<CraftBlock> group : groups) {
            for (CraftBlock block : group) queue.add(new Object[] {group, block.offset()});
        }
        while (!queue.isEmpty()) {
            Object[] item = queue.poll();
            @SuppressWarnings("unchecked")
            List<CraftBlock> group = (List<CraftBlock>) item[0];
            BlockPos from = (BlockPos) item[1];
            for (Direction face : Direction.values()) {
                CraftBlock fluid = free.remove(from.relative(face));
                if (fluid == null) continue;
                out.computeIfAbsent(group, k -> new ArrayList<>()).add(fluid);
                queue.add(new Object[] {group, fluid.offset()});
            }
        }
        return out;
    }

    /** Moves a part that broke off into a plot of its own, as a new craft with the motion the part had. */
    private static void splitOff(ServerLevel level, CraftEntity parent, ServerLevel plot, List<CraftBlock> group, List<CraftBlock> fluids) {
        WorldPhysics physics = WorldPhysics.of(level);
        CraftEntity fragment = physics.hasSpace() ? Aerolith.CRAFT.get().create(level, net.minecraft.world.entity.EntitySpawnReason.TRIGGERED) : null;
        if (fragment == null) {
            group.forEach(block -> plot.destroyBlock(parent.plotPos(block.offset()), true));
            fluids.forEach(block -> Plots.erase(plot, parent.plotPos(block.offset())));
            return;
        }
        int id = Plots.allocate(plot);
        BlockPos origin = PlotGrid.origin(id, () -> java.util.stream.Stream.concat(group.stream(), fluids.stream()).map(CraftBlock::offset).iterator());
        List<Plots.Moved> moved = new ArrayList<>(group.size() + fluids.size());
        LongOpenHashSet chunks = new LongOpenHashSet();
        for (List<CraftBlock> part : List.of(group, fluids)) {
            for (CraftBlock block : part) {
                moved.add(Plots.capture(plot, parent.plotPos(block.offset()), block.offset()));
                chunks.add(PlotGrid.chunkKey(origin.offset(block.offset())));
            }
        }
        Plots.write(plot, origin, moved);
        for (Plots.Moved block : moved) Plots.erase(plot, parent.plotPos(block.offset()));
        fragment.start(parent.position(), parent.ownerId(), id, origin, chunks);
        fragment.inheritMotion(parent);
        if (!level.addFreshEntity(fragment)) {
            fragment.dissolve();
            return;
        }
        // If the engine is down the piece keeps its blocks and joins the simulation when it is back.
        physics.bind(fragment);
    }

    /** Removes blocks from a craft the way anything but a player would: set to air, then the hull follows. */
    static void remove(CraftEntity craft, java.util.Collection<CraftBlock> blocks) {
        ServerLevel plot = craft.plotLevel();
        for (CraftBlock block : blocks) {
            BlockPos pos = craft.plotPos(block.offset());
            plot.removeBlockEntity(pos);
            plot.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        }
        craft.settle();
    }

    static Vec3 worldCenter(CraftEntity craft, BlockPos offset) {
        return craft.position().add(Hull.rotate(craft.orientation(1), Vec3.atLowerCornerOf(offset)));
    }

    // shortcut: a rotated cell is tested by its axis-aligned bounds; use the oriented cell if tilted hulls need tighter fit.
    private static boolean obstructed(ServerLevel level, CraftEntity craft, Vec3 center) {
        if (!level.noBlockCollision(null, AABB.ofSize(center, 0.8, 0.8, 0.8))) return true;
        if (HullCollisions.overlapsCraft(level, AABB.ofSize(center, 0.9, 0.9, 0.9), craft)) return true;
        return !level.getEntities(craft, AABB.ofSize(center, 0.9, 0.9, 0.9),
                e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator()).isEmpty();
    }
}
