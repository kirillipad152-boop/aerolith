package dev.aerolith;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.Quaternionf;

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
    final CraftSections sections;
    private final Map<CraftEntity, Body> crafts = new LinkedHashMap<>();
    private final Long2ObjectOpenHashMap<Body> bodies = new Long2ObjectOpenHashMap<>();
    private long world;
    /**
     * The engine steps on a thread of its own while the rest of the server tick goes on, and the crafts take its poses a tick
     * later. Nothing may touch the native world while a step is in flight, which is only ever the case between a tick and
     * the next: everything the engine is given is given in {@link #tick}, after the last step has been collected.
     */
    private static final ExecutorService STEPPER = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "aerolith-physics-step");
        thread.setDaemon(true);
        return thread;
    });
    /** {@code -Daerolith.syncPhysics=true} steps on the server thread again, for finding out whether a problem is the threading's. */
    private static final boolean SYNC = Boolean.getBoolean("aerolith.syncPhysics");
    /** How long the server waits for a step that is late before it goes on without it (milliseconds). */
    private static final long GRACE_MS = 15;
    private Future<double[]> flight;
    /** What the last step found, until the crafts have been moved to it. */
    private double[] landed;
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

    private static final class Grab {
        final ServerPlayer player;
        final ItemStack staff;
        final Vec3 localPoint;
        final Quaternionf rotation;
        double distance;

        Grab(ServerPlayer player, ItemStack staff, CraftEditing.Target target, Quaternionf rotation) {
            this.player = player;
            this.staff = staff;
            this.localPoint = target.localHit();
            this.rotation = rotation;
            this.distance = Math.max(TowStaffItem.MIN_DISTANCE, target.pick().distance());
        }
    }

    private WorldPhysics(ServerLevel level) {
        this.level = level;
        this.sections = HullCollisions.sections(level);
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
            quiesce();
            Hull hull = craft.hull();
            Hull.Colliders geometry = hull.colliders(materials);
            body.id = NativePhysics.addBody(world, craft.nativeState(), geometry.boxes(), geometry.frictionCells(), hull.masses(), hull.surfaces);
            bodies.put(body.id, body);
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
        quiesce();
        NativePhysics.impulseAt(world, body.id, impulse.x, impulse.y, impulse.z, point.x, point.y, point.z);
        return true;
    }

    void staffInput(ServerPlayer player, TowStaffInput input) {
        ItemStack staff = TowStaffItem.held(player);
        if (!input.valid() || staff.isEmpty() || !player.isAlive() || player.level() != level || player.isPassenger()) return;
        if (!player.isCreative()) {
            if (input.action() != TowStaffInput.ADJUST) AssemblerItem.message(player, "staff_creative");
            return;
        }

        Body held = null;
        for (Body body : crafts.values()) {
            if (body.grab != null && body.grab.player == player) {
                if (body.grab.staff == staff && gripInRange(body)) held = body;
                else release(body);
                break;
            }
        }
        if (input.action() == TowStaffInput.ADJUST) {
            if (held != null) adjustGrip(held.grab, input);
            return;
        }
        if (held != null && input.action() == TowStaffInput.TOGGLE.action()) {
            release(held);
            AssemblerItem.message(player, "staff_released");
            return;
        }

        // The server picks the first reachable hull and its grip point, not a target sent by the client.
        var aimed = held == null ? TowStaffItem.aimed(player, 1f) : null;
        if (held == null && aimed == null) return;
        CraftEntity craft = held == null ? aimed.craft() : held.craft;
        if (craft.isRemoved() || craft.hull() == null) return;
        if (!craft.canEdit(player)) {
            AssemblerItem.message(player, "owner");
            return;
        }
        Body body = crafts.get(craft);
        if (input.action() == TowStaffInput.PIN.action()) {
            if (body != null) release(body);
            craft.setAnchored(!craft.anchored());
            // Re-register with zero momentum before making it fixed; a normal unloaded body keeps its motion.
            if (craft.anchored()) rebuild(craft);
            AssemblerItem.message(player, craft.anchored() ? "staff_anchored" : "staff_unanchored");
            return;
        }
        if (failed || body == null || body.grab != null || body.id == 0
                || (!body.active && !craft.anchored()) || !level.isPositionEntityTicking(craft.blockPosition())
                || craft.isVehicle() || craft.loads.supports(player)) {
            AssemblerItem.message(player, "staff_unavailable");
            return;
        }
        craft.setAnchored(false);
        body.grab = new Grab(player, staff, aimed.target(), craft.orientation(1f));
        craft.setStaffGrip(player.getId(), body.grab.localPoint);
        AssemblerItem.message(player, "staff_hold");
    }

    private static void adjustGrip(Grab grab, TowStaffInput input) {
        grab.distance = Mth.clamp(grab.distance + input.distance(), TowStaffItem.MIN_DISTANCE, TowStaffItem.RANGE);
        float yaw = Mth.clamp(input.yaw(), -0.75f, 0.75f);
        float pitch = Mth.clamp(input.pitch(), -0.75f, 0.75f);
        if (yaw == 0 && pitch == 0) return;
        Vec3 right = Vec3.directionFromRotation(0f, grab.player.getYRot() + 90);
        Vec3 up = right.cross(grab.player.getViewVector(1)).normalize();
        grab.rotation.premul(new Quaternionf().rotateAxis(yaw, (float) up.x, (float) up.y, (float) up.z)
                .rotateAxis(pitch, (float) right.x, (float) right.y, (float) right.z)).normalize();
    }

    private static Vec3 gripPoint(Body body) {
        return body.craft.position().add(Hull.rotate(body.craft.orientation(1f), body.grab.localPoint));
    }

    private static boolean gripInRange(Body body) {
        double range = TowStaffItem.RANGE + 8;
        return body.grab.player.getEyePosition().distanceToSqr(gripPoint(body)) <= range * range;
    }

    private static void release(Body body) {
        if (body.grab == null) return;
        body.grab = null;
        body.craft.setStaffGrip(-1, Vec3.ZERO);
    }

    private void pull(CraftEntity craft, Body body, boolean simulated) {
        Grab grab = body.grab;
        ServerPlayer player = grab.player;
        if (!simulated || player.isRemoved() || !player.isAlive() || player.level() != level || !player.isCreative()
                || TowStaffItem.held(player) != grab.staff
                || !craft.canEdit(player) || player.isPassenger() || craft.isVehicle() || craft.loads.supports(player)) {
            release(body);
            return;
        }
        Vec3 point = gripPoint(body);
        Vec3 eye = player.getEyePosition();
        double range = TowStaffItem.RANGE + 8;
        if (eye.distanceToSqr(point) > range * range) {
            release(body);
            return;
        }
        // The grip closes on the cursor in about two ticks and follows it at up to 60 m/s (the engine's own cap is 80).
        Vec3 velocity = eye.add(player.getViewVector(1).scale(grab.distance)).subtract(point).scale(10);
        if (velocity.lengthSqr() > 3600) velocity = velocity.normalize().scale(60);
        Vec3 impulse = velocity.subtract(craft.comVelocity()).scale(0.9).add(0, 9.81 * 0.05, 0).scale(craft.hull().mass);
        Quaternionf rotation = grab.rotation;
        NativePhysics.staffPull(world, body.id, impulse.x, impulse.y, impulse.z, rotation.x, rotation.y, rotation.z, rotation.w);
    }

    private void remove(Body body) {
        if (body == null) return;
        detach(body);
        claim(body, null);
    }

    private void detach(Body body) {
        release(body);
        if (body.id != 0) {
            quiesce();
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

    /** Waits for the step in flight, if there is one, and keeps what it found for the next tick. */
    private void quiesce() {
        Future<double[]> step = flight;
        if (step == null) return;
        long start = Perf.begin();
        try {
            landed = step.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while the engine was stepping", interrupted);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } finally {
            flight = null;
            Perf.end("server.wait", start);
        }
    }

    /**
     * A step a few milliseconds over its tick is waited for, rather than skipped: a skipped tick shows as a craft standing
     * still for a tick and then jumping, which is what a step now and then too slow looks like.
     */
    private static boolean finishesSoon(Future<double[]> step) {
        long start = Perf.begin();
        try {
            step.get(GRACE_MS, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException late) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return true;
        } catch (ExecutionException failure) {
            return true; // reported by the next quiesce()
        } finally {
            Perf.end("server.grace", start);
        }
    }

    /** Gives up a step in flight without looking at what it found; the world is about to be destroyed or restarted. */
    private void drain() {
        Future<double[]> step = flight;
        flight = null;
        landed = null;
        if (step == null) return;
        try {
            step.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ignored) {
            // The failure that matters has been reported, or will be by whoever asked for the step.
        }
    }

    private void tick() {
        if (flight != null) {
            // A step that is not done yet is slower than a tick: the crafts keep their poses and the game carries on, so an
            // overloaded engine runs in slow motion instead of holding the whole server up.
            if (!SYNC && !flight.isDone() && !finishesSoon(flight)) {
                Perf.end("server.late", Perf.begin());
                return;
            }
            quiesce();
        }
        applyLanded();
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
                craft.loads.apply(craft, world, body.id, body.asleep);
                craft.applyPushes(world, body.id);
            }
            if (body.grab != null) pull(craft, body, body.id != 0 && body.active);
        }
        long terrainStart = Perf.begin();
        terrain.sync(world);
        Perf.end("server.terrain", terrainStart);
        if (bodies.isEmpty()) {
            awake = 0;
            return;
        }
        long w = world;
        double windX = wind.x, windY = wind.y, windZ = wind.z;
        flight = STEPPER.submit(() -> {
            long stepStart = Perf.begin();
            try {
                return NativePhysics.step(w, windX, windY, windZ);
            } finally {
                Perf.end("server.native-step", stepStart);
            }
        });
        if (SYNC) {
            quiesce();
            applyLanded();
        }
    }

    private void applyLanded() {
        double[] moved = landed;
        if (moved == null) return;
        landed = null;
        long now = level.getGameTime();
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
        // An impulse or impact can start a stationary body turning this tick. Last tick's spin is not a bound, nor its speed:
        // a margin scaled by speed lets the first impulse carry a resting craft through a wall it never claimed.
        Vec3 c = craft.worldCenterOfMass();
        double r = craft.hull().radius + SWEEP_MARGIN;
        AABB box = craft.getBoundingBox().inflate(CORNER_MARGIN);
        double minX = Math.max(box.minX, c.x - r), maxX = Math.min(box.maxX, c.x + r);
        double minY = Math.max(box.minY, c.y - r), maxY = Math.min(box.maxY, c.y + r);
        double minZ = Math.max(box.minZ, c.z - r), maxZ = Math.min(box.maxZ, c.z + r);
        WorldBorder border = level.getWorldBorder();
        if (minX < border.getMinX() || maxX > border.getMaxX() || minZ < border.getMinZ() || maxZ > border.getMaxZ()) return false;
        // There is no limit of height: above and below the world there is simply no terrain to claim.
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
        drain();
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
