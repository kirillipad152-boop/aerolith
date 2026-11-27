package dev.aerolith;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

public final class WorldPhysics {
    static final int MAX_BODIES = 16_384;
    /** With as many crafts moving it pays to find out once who else is about. */
    private static final int MANY = 32;
    /** How often it is checked that the ground of a craft at rest is still loaded; changes of its blocks are reported. */
    private static final int REST_CHECK_TICKS = 20;
    private static final Map<ServerLevel, WorldPhysics> LEVELS = new IdentityHashMap<>();

    private final ServerLevel level;
    private final FrictionConfig materials;
    private final Terrain terrain;
    final CraftSections sections = new CraftSections();
    private final Map<CraftEntity, Body> crafts = new LinkedHashMap<>();
    private final Long2ObjectOpenHashMap<Body> bodies = new Long2ObjectOpenHashMap<>();
    private long world;
    private int awake;
    private boolean failed;
    private Vec3 wind = Vec3.ZERO;

    private static final class Body {
        final CraftEntity craft;
        long id;
        boolean active = true;
        /** At rest in the engine: nothing about it can change until something outside touches it. */
        boolean asleep;
        long checkAt;
        Terrain.Range ground;
        Grab grab;

        Body(CraftEntity craft) {
            this.craft = craft;
        }
    }

    private record Grab(ServerPlayer player, ItemStack staff, Vec3 localPoint, double distance) {}

    private WorldPhysics(ServerLevel level) {
        this.level = level;
        this.materials = new FrictionConfig();
        this.terrain = new Terrain(level, materials);
    }

    static WorldPhysics of(ServerLevel level) {
        return LEVELS.computeIfAbsent(level, WorldPhysics::new);
    }

    static void onLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) of(level);
    }

    static boolean chunkLoaded(ServerLevel level, BlockPos p) {
        return level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) != null;
    }

    static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof CraftEntity craft) {
            of(level).crafts.putIfAbsent(craft, new Body(craft));
        }
    }

    static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof CraftEntity craft) {
            WorldPhysics physics = LEVELS.get(level);
            if (physics != null) {
                try {
                    physics.remove(physics.crafts.remove(craft));
                } catch (RuntimeException | LinkageError failure) {
                    physics.fail(failure);
                }
            }
        }
    }

    static void onTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && level.tickRateManager().runsNormally()) {
            WorldPhysics physics = LEVELS.get(level);
            if (physics != null && physics.recovered()) {
                long start = Perf.begin();
                try {
                    HullCollisions.step(level, physics.awake >= MANY, physics::tick);
                } catch (RuntimeException | LinkageError failure) {
                    physics.fail(failure);
                }
                if (!physics.crafts.isEmpty()) Perf.end("server.physics", start);
            }
        }
    }

    static void onUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            WorldPhysics physics = LEVELS.remove(level);
            if (physics != null) physics.close();
        }
    }

    boolean hasSpace() {
        return space() > 0;
    }

    int space() {
        return MAX_BODIES - crafts.size();
    }

    boolean isSimulated(CraftEntity craft) {
        Body body = crafts.get(craft);
        return !failed && body != null && body.id != 0 && body.active;
    }

    boolean ready() {
        if (failed) return false;
        try {
            if (world == 0) world = NativePhysics.createWorld();
            return true;
        } catch (RuntimeException | LinkageError failure) {
            fail(failure);
            return false;
        }
    }

    boolean bind(CraftEntity craft) {
        if (!ready()) return false;
        try {
            attach(craft, crafts.computeIfAbsent(craft, Body::new));
            return true;
        } catch (RuntimeException | LinkageError failure) {
            fail(failure);
            return false;
        }
    }

    private void attach(CraftEntity craft, Body body) {
        if (body.id == 0) {
            Hull hull = craft.hull();
            Hull.Colliders geometry = hull.colliders(materials);
            body.id = NativePhysics.addBody(world, craft.nativeState(), geometry.boxes(), geometry.frictionCells(), hull.masses(), hull.surfaces);
            bodies.put(body.id, body);
            craft.controlsSent = false;
            body.asleep = false;
            body.active = !craft.anchored();
            if (!body.active) NativePhysics.setActive(world, body.id, false);
        }
    }

    /** Re-registers a craft whose blocks changed; its pose and momentum are carried over from the craft entity. */
    void rebuild(CraftEntity craft) {
        Body body = crafts.get(craft);
        if (body == null || body.id == 0 || world == 0) return;
        try {
            detach(body);
            attach(craft, body);
        } catch (RuntimeException | LinkageError failure) {
            fail(failure);
        }
    }

    /** Applies a world-space impulse at a world-space point of an active craft; false when it cannot be applied. */
    boolean impulseAt(CraftEntity craft, Vec3 impulse, Vec3 point) {
        Body body = crafts.get(craft);
        if (failed || world == 0 || body == null || body.id == 0 || !body.active) return false;
        NativePhysics.impulseAt(world, body.id, impulse.x, impulse.y, impulse.z, point.x, point.y, point.z);
        return true;
    }

    /** The server picks the grip point; no position or force is taken from the client packet. */
    boolean grab(CraftEntity craft, ServerPlayer player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || player.level() != level || !player.isCreative()
                || !player.getMainHandItem().is(Aerolith.TOW_STAFF.get()) || !craft.canEdit(player) || player.isUsingItem()
                || player.isPassenger() || craft.isVehicle() || !isSimulated(craft) || craft.loads.supports(player)) return false;
        Body body = crafts.get(craft);
        if (body.grab != null || crafts.values().stream().anyMatch(b -> b.grab != null && b.grab.player() == player)) return false;
        Vec3 eye = player.getEyePosition();
        double reach = player.blockInteractionRange();
        var target = CraftEditing.target(craft, eye, player.getViewVector(1), reach, 1);
        if (target == null || !staffLineClear(player, target)) return false;
        player.startUsingItem(hand);
        if (!player.isUsingItem()) return false;
        body.grab = new Grab(player, player.getUseItem(), target.localHit(), Math.max(1.5, target.pick().distance()));
        return true;
    }

    void toggleAnchor(ServerPlayer player) {
        if (!player.getMainHandItem().is(Aerolith.TOW_STAFF.get())) return;
        if (!player.isCreative()) {
            AssemblerItem.message(player, "staff_creative");
            return;
        }
        CraftEntity craft = null;
        if (player.isUsingItem() && player.getUseItem() == player.getMainHandItem()) {
            for (var entry : crafts.entrySet()) {
                Grab grab = entry.getValue().grab;
                if (grab != null && grab.player() == player && grab.staff() == player.getUseItem()) {
                    craft = entry.getKey();
                    break;
                }
            }
        }
        if (craft == null) {
            var aimed = CraftEditing.nearest(level, player.getEyePosition(), player.getViewVector(1), player.blockInteractionRange(), 1);
            if (aimed == null || !staffLineClear(player, aimed.target())) return;
            craft = aimed.craft();
        }
        if (craft.isRemoved() || craft.hull() == null) return;
        if (!craft.canEdit(player)) {
            AssemblerItem.message(player, "owner");
            return;
        }
        craft.setAnchored(!craft.anchored());
        // Re-register with zero momentum before making it fixed; a normal unloaded body instead keeps its old motion.
        if (craft.anchored()) rebuild(craft);
        AssemblerItem.message(player, craft.anchored() ? "staff_anchored" : "staff_unanchored");
    }

    private static boolean staffLineClear(ServerPlayer player, CraftEditing.Target target) {
        HitResult terrain = player.pick(player.blockInteractionRange(), 1, false);
        return terrain.getType() != HitResult.Type.BLOCK || terrain.getLocation().distanceTo(player.getEyePosition()) >= target.pick().distance();
    }

    private static void release(Body body) {
        Grab grab = body.grab;
        body.grab = null;
        if (grab != null && grab.player().getUseItem() == grab.staff()) grab.player().stopUsingItem();
    }

    private void pull(CraftEntity craft, Body body, boolean simulated) {
        Grab grab = body.grab;
        if (grab == null) return;
        ServerPlayer player = grab.player();
        if (!simulated || player.isRemoved() || !player.isAlive() || player.level() != level || !player.isCreative()
                || !player.isUsingItem() || player.getUseItem() != grab.staff() || player.getMainHandItem() != grab.staff()
                || !craft.canEdit(player) || player.isPassenger() || craft.isVehicle() || craft.loads.supports(player)) {
            release(body);
            return;
        }
        Vec3 point = craft.position().add(Hull.rotate(craft.orientation(1), grab.localPoint()));
        Vec3 eye = player.getEyePosition();
        if (eye.distanceToSqr(point) > (grab.distance() + 8) * (grab.distance() + 8)) {
            release(body);
            return;
        }
        Vec3 velocity = eye.add(player.getViewVector(1).scale(grab.distance())).subtract(point).scale(4);
        if (velocity.lengthSqr() > 36) velocity = velocity.normalize().scale(6);
        // Pull at the centre of mass so the staff cannot kick a long hull into a spin. Rapier still resolves contacts.
        Vec3 impulse = velocity.subtract(craft.comVelocity()).scale(0.5).add(0, 9.81 * 0.05, 0).scale(craft.hull().mass);
        NativePhysics.impulse(world, body.id, impulse.x, impulse.y, impulse.z);
    }

    private void remove(Body body) {
        if (body == null) return;
        detach(body);
        claim(body, null);
    }

    private void detach(Body body) {
        release(body);
        if (body.id != 0) {
            bodies.remove(body.id);
            if (world != 0) NativePhysics.removeBody(world, body.id);
            body.id = 0;
        }
        body.checkAt = 0;
    }

    private void claim(Body body, Terrain.Range range) {
        if (java.util.Objects.equals(range, body.ground)) return;
        terrain.claim(body.ground, range);
        body.ground = range;
    }

    /** A block of the world changed, on the server thread. */
    public static void blockChanged(ServerLevel level, BlockPos pos, BlockState old, BlockState now) {
        if (old == now || !level.getServer().isSameThread()) return;
        WorldPhysics physics = LEVELS.get(level);
        if (physics != null) physics.terrain.changed(pos);
    }

    private void tick() {
        for (var it = crafts.values().iterator(); it.hasNext();) {
            Body body = it.next();
            if (body.craft.isRemoved() || level.getEntity(body.craft.getId()) != body.craft) {
                remove(body);
                it.remove();
            }
        }
        if (crafts.isEmpty() && world == 0) return;
        if (!ready()) return;
        long now = level.getGameTime();
        for (Body body : crafts.values()) {
            CraftEntity craft = body.craft;
            if (craft.hull() == null) {
                release(body);
                continue;
            }
            if (!body.asleep || !body.active || now >= body.checkAt || body.grab != null) {
                boolean awake = !craft.anchored() && level.isPositionEntityTicking(craft.blockPosition()) && standsOnLoadedGround(body);
                if (!awake) claim(body, null);
                body.checkAt = now + REST_CHECK_TICKS;
                if (body.id == 0 && (awake || craft.anchored()) && bodies.size() < MAX_BODIES) attach(craft, body);
                if (body.id != 0 && body.active != awake) {
                    NativePhysics.setActive(world, body.id, awake);
                    body.active = awake;
                    body.asleep = false;
                }
            }
            if (body.id != 0 && body.active) {
                craft.applyPilot(world, body.id);
                craft.loads.apply(craft, world, body.id, body.asleep);
                craft.applyPushes(world, body.id);
            }
            if (body.grab != null) pull(craft, body, body.id != 0 && body.active);
        }
        long terrainStart = Perf.begin();
        terrain.sync(world);
        Perf.end("server.terrain", terrainStart);
        awake = 0;
        if (bodies.isEmpty()) return;
        long stepStart = Perf.begin();
        double[] moved = NativePhysics.step(world, wind.x, wind.y, wind.z);
        Perf.end("server.native-step", stepStart);
        awake = moved.length / NativePhysics.RECORD;
        long applyStart = Perf.begin();
        for (int i = 0; i < moved.length; i += NativePhysics.RECORD) {
            Body body = bodies.get((long) moved[i]);
            if (body == null) continue;
            body.asleep = moved[i + NativePhysics.RECORD - 1] != 0;
            body.checkAt = now + REST_CHECK_TICKS;
            body.craft.applyNative(moved, i + 1);
        }
        Perf.end("server.apply", applyStart);
    }

    /** Native COM speed limit (80 m/s × 0.05 s), plus clearance for terrain shapes extending outside their block. */
    private static final double SWEEP_MARGIN = 5.25;
    /** No corner turns faster than that limit either, so this is the farthest any point can get, with the same clearance. */
    private static final double CORNER_MARGIN = 9.25;

    /** Claims the ground the craft can reach in the next tick; false if some of it is not loaded. */
    private boolean standsOnLoadedGround(Body body) {
        CraftEntity craft = body.craft;
        // An impulse or impact can start a stationary body turning this tick. Last tick's spin is not a bound.
        Vec3 c = craft.worldCenterOfMass();
        double r = craft.hull().radius + SWEEP_MARGIN;
        AABB box = craft.getBoundingBox().inflate(CORNER_MARGIN);
        double minX = Math.max(box.minX, c.x - r), maxX = Math.min(box.maxX, c.x + r);
        double minY = Math.max(box.minY, c.y - r), maxY = Math.min(box.maxY, c.y + r);
        double minZ = Math.max(box.minZ, c.z - r), maxZ = Math.min(box.maxZ, c.z + r);
        WorldBorder border = level.getWorldBorder();
        if (minX < border.getMinX() || maxX > border.getMaxX() || minZ < border.getMinZ() || maxZ > border.getMaxZ()) return false;
        // Terrain tiles are clipped vertically below; a wide raft near bedrock must not be frozen by its horizontal reach.
        if (c.y < level.getMinY() - 24 || c.y > level.getMaxY() + 129) return false;
        int x1 = Mth.floor(minX), x2 = Mth.floor(maxX), z1 = Mth.floor(minZ), z2 = Mth.floor(maxZ);
        for (int cz = z1 >> 4; cz <= z2 >> 4; cz++) {
            for (int cx = x1 >> 4; cx <= x2 >> 4; cx++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) return false;
            }
        }
        int y1 = Math.max(level.getMinY(), Mth.floor(minY));
        int y2 = Math.min(level.getMaxY(), Mth.floor(maxY));
        claim(body, y1 > y2 ? null : new Terrain.Range(tile(x1), tile(y1), tile(z1), tile(x2), tile(y2), tile(z2)));
        return true;
    }

    private static int tile(int block) {
        return Math.floorDiv(block, Terrain.TILE);
    }

    void wind(Vec3 value) {
        wind = value;
    }

    private static final int RETRY_TICKS = 100;
    private static final int MAX_RESTARTS = 5;
    private int restarts;
    private long failedAt;

    private void fail(Throwable failure) {
        failed = true;
        failedAt = level.getGameTime();
        Aerolith.LOG.error("Aerolith physics stopped in {}; craft entities and saved blocks are kept{}", level.dimension().identifier(),
                restarts < MAX_RESTARTS ? ", restarting in 5 seconds" : ", giving up after repeated failures", failure);
        close();
        // The native world is gone, so every craft must be registered again when it comes back.
        for (Body body : crafts.values()) {
            body.id = 0;
            body.active = true;
            body.asleep = false;
            body.checkAt = 0;
        }
        bodies.clear();
        terrain.reset();
        for (var player : level.players()) {
            if (player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("message.aerolith.physics_failed"));
            }
        }
    }

    /** True while the engine runs; after a failure it is restarted a few times, then left stopped. */
    private boolean recovered() {
        if (!failed) return true;
        if (restarts < MAX_RESTARTS && level.getGameTime() - failedAt >= RETRY_TICKS) {
            restarts++;
            failed = false;
            return true;
        }
        return false;
    }

    private void close() {
        crafts.values().forEach(WorldPhysics::release);
        if (world != 0) {
            try {
                NativePhysics.destroyWorld(world);
            } catch (RuntimeException | LinkageError failure) {
                Aerolith.LOG.error("Cannot close Aerolith native world", failure);
            } finally {
                world = 0;
            }
        }
    }
}
