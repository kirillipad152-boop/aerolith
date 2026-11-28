package dev.aerolith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

@EventBusSubscriber(modid = Aerolith.ID)
public final class HullCollisions {
    private static final double EPS = 1.0e-6;
    private static final double SUPPORT_DISTANCE = 1.0 / 32;
    private static final double FLOOR_NORMAL = 0.65;
    private static final double DEEP_OVERLAP = 0.2;
    private static final double POSE_STEP = 0.25;
    private static final int MAX_POSE_STEPS = 256;
    private static final double MAX_ESCAPE_DEPTH = 0.65;
    private static final Map<Level, Set<CraftEntity>> CRAFTS = new ConcurrentHashMap<>();
    private static final Map<Level, CraftSections> SECTIONS = new ConcurrentHashMap<>();
    private static final Vec3[] WORLD = {new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1)};
    private static final List<Axis> ALIGNED = List.of(new Axis(WORLD[0], WORLD), new Axis(WORLD[1], WORLD),
            new Axis(WORLD[2], WORLD));

    private HullCollisions() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof CraftEntity craft) {
            CRAFTS.compute(event.getLevel(), (unused, crafts) -> {
                if (crafts == null) crafts = new CopyOnWriteArraySet<>();
                crafts.add(craft);
                return crafts;
            });
        }
    }

    /**
     * Every loaded craft of a level. Vanilla's area queries look at the section holding an entity's origin plus two
     * blocks around the query, so a large craft is missed when the block aimed at is far from its centre.
     */
    static Set<CraftEntity> crafts(Level level) {
        Set<CraftEntity> crafts = CRAFTS.get(level);
        return crafts == null ? Set.of() : crafts;
    }

    static CraftSections sections(Level level) {
        return SECTIONS.computeIfAbsent(level, unused -> new CraftSections());
    }

    static CraftSections sectionsIfPresent(Level level) {
        return SECTIONS.get(level);
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        // A server entity can stop being tracked and become visible again without another join event.
        if (event.getEntity() instanceof CraftEntity craft && craft.isRemoved()) {
            forget(event.getLevel(), craft);
        }
    }

    /**
     * A solid block placed into the space a craft occupies would be assembled into a second craft there and the two would
     * be flung apart, so it is refused up front.
     */
    @SubscribeEvent
    public static void onPlace(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) || StructureTransfer.docking) return;
        var state = event.getPlacedBlock();
        BlockPos pos = event.getPos();
        if (state.getCollisionShape(level, pos).isEmpty()) return;
        if (overlapsCraft(level, new AABB(pos).deflate(0.01), null)) {
            event.setCanceled(true);
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                AssemblerItem.at(player, "obstructed", pos);
            }
        }
    }

    @SubscribeEvent
    public static void onUnload(LevelEvent.Unload event) {
        CRAFTS.remove(event.getLevel());
        SECTIONS.remove(event.getLevel());
    }

    /** Receives the push of the local player against a hull so it can be sent to the server. */
    interface PushSink {
        void push(CraftEntity craft, Vec3 point, Vec3 direction, double strength);
    }

    static volatile PushSink pushSink = (craft, point, direction, strength) -> {};

    private static final double LEAN = 0.01;
    private static final double SLOPE_NORMAL = 0.5;
    private static final double STEP_HEIGHT = 0.6;
    private static final double SNAP_DOWN = 0.35;

    /**
     * Resolves hull contact like vanilla resolves blocks: vertical first, then horizontal. Floors of any slope that a
     * player can stand on are walked along without losing horizontal speed, low ledges are stepped onto, and walls
     * slide. Whatever a wall stops is reported as a push.
     */
    public static Vec3 clipMovement(Entity entity, Vec3 vanilla) {
        if (!eligible(entity) || !CRAFTS.containsKey(entity.level()) || negligible(vanilla)) return vanilla;
        AABB start = entity.getBoundingBox();
        Sweep initial = new Sweep(start, vanilla);
        Hit initialHit = hitCrafts(entity, initial, null);
        if (initialHit == null && !standsOnHull(entity, start, vanilla)) return vanilla;

        AABB box = start;
        Vec3 total = Vec3.ZERO;
        boolean grounded = false;
        CraftEntity supportCraft = null;
        if (Math.abs(vanilla.y) > EPS) {
            Vec3 vertical = new Vec3(0, vanilla.y, 0);
            Hit hit = blocking(entity, new Sweep(box, vertical));
            Vec3 advance = hit == null ? vertical : beforeContact(vertical, hit);
            grounded = hit != null && vanilla.y < 0 && isFloor(hit);
            if (grounded) supportCraft = hit.craft;
            total = advance;
            box = box.move(advance);
        } else {
            Hit floor = hitCrafts(entity, new Sweep(box, new Vec3(0, -SNAP_DOWN, 0)), null);
            if (isFloor(floor)) supportCraft = floor.craft;
        }

        Vec3 rest = new Vec3(vanilla.x, 0, vanilla.z);
        for (int pass = 0; pass < 5 && !negligible(rest); pass++) {
            Hit hit = pass == 0 && vanilla.y == 0 ? earlier(initialHit, hitWorld(entity, initial))
                    : blocking(entity, new Sweep(box, rest));
            if (hit == null) {
                total = total.add(rest);
                box = box.move(rest);
                rest = Vec3.ZERO;
                break;
            }
            Vec3 advance = beforeContact(rest, hit);
            total = total.add(advance);
            box = box.move(advance);
            rest = rest.subtract(advance);
            Vec3 normal = hit.normal;
            if (hit.craft != null && normal.y >= SLOPE_NORMAL) {
                // A floor in the way: keep the horizontal step and follow the surface up.
                rest = new Vec3(rest.x, -(normal.x * rest.x + normal.z * rest.z) / normal.y, rest.z);
                grounded = true;
                supportCraft = hit.craft;
            } else {
                Vec3 up = stepUp(entity, box, rest);
                if (up != null) {
                    total = total.add(up);
                    box = box.move(up);
                    grounded = true;
                    continue;
                }
                Vec3 wall = new Vec3(normal.x, 0, normal.z);
                if (wall.lengthSqr() < EPS) break;
                wall = wall.normalize();
                if (hit.craft != null && hit.craft != supportCraft) {
                    reportPush(entity, hit.craft, box, wall, -rest.dot(wall));
                }
                rest = rest.subtract(wall.scale(rest.dot(wall)));
            }
        }

        if (grounded && vanilla.y <= 0) {
            // Stay on a descending surface instead of hopping down it a gravity step at a time.
            Sweep down = new Sweep(box, new Vec3(0, -SNAP_DOWN, 0));
            Hit floor = hitCrafts(entity, down, null);
            if (isFloor(floor) && floor.time > EPS && hitWorld(entity, down) == null) {
                Vec3 drop = down.motion.scale(Math.max(0, floor.time - 2 * EPS / SNAP_DOWN));
                total = total.add(drop);
            }
        }
        return retainY(total, vanilla);
    }

    private static Hit blocking(Entity entity, Sweep path) {
        return earlier(hitCrafts(entity, path, null), hitWorld(entity, path));
    }

    private static boolean standsOnHull(Entity entity, AABB box, Vec3 vanilla) {
        return vanilla.y <= 0 && (hitCrafts(entity, new Sweep(box, new Vec3(0, -SNAP_DOWN, 0)), null) != null);
    }

    /** Lift needed to carry the box over a low ledge in the way of {@code rest}, or null. */
    private static Vec3 stepUp(Entity entity, AABB box, Vec3 rest) {
        if (!entity.onGround() && entity.getDeltaMovement().y > 0) return null;
        for (double height = 0.15; height <= STEP_HEIGHT + EPS; height += 0.15) {
            Vec3 lift = new Vec3(0, height, 0);
            if (blocking(entity, new Sweep(box, lift)) != null) return null;
            Hit ahead = blocking(entity, new Sweep(box.move(lift), rest));
            if (ahead == null || ahead.time > 0.6) return lift;
        }
        return null;
    }

    private static void reportPush(Entity entity, CraftEntity craft, AABB box, Vec3 wall, double blocked) {
        if (blocked < 0.002) return;
        // Leaning is all-or-nothing: on ice a player barely gains speed, yet still pushes with full effort.
        double strength = Math.min(1.0, blocked / LEAN);
        if (strength < 0.05) return;
        Vec3 contact = new Vec3(box.getCenter().x, box.minY + 0.4, box.getCenter().z).subtract(wall.scale(entity.getBbWidth() * 0.5));
        Vec3 direction = wall.scale(-1);
        if (entity.level().isClientSide()) {
            if (entity instanceof net.minecraft.world.entity.player.Player p && p.isLocalPlayer()) {
                pushSink.push(craft, contact, direction, strength);
            }
        } else if (!(entity instanceof net.minecraft.server.level.ServerPlayer)) {
            craft.queuePush(contact, direction.scale(strength * CraftLoads.pushImpulse(entity)));
        }
    }

    /**
     * The client resolves hull contact itself and its view of a moving hull lags the server's by a few ticks, so the
     * server only rejects a move that ends deep inside a hull. A strict check turned resting contact into a teleport
     * every tick.
     */
    public static boolean blocksPlayerMove(Entity player, AABB start, Vec3 destination) {
        CraftSections sections = SECTIONS.get(player.level());
        if (!eligible(player) || sections == null) return false;
        Vec3 center = start.getCenter();
        Vec3 delta = new Vec3(destination.x - center.x, destination.y - start.minY, destination.z - center.z);
        AABB end = start.move(delta);
        AABB swept = start.minmax(end);
        AABB coreStart = start.deflate(
                Math.min(DEEP_OVERLAP, start.getXsize() * 0.45),
                Math.min(DEEP_OVERLAP, start.getYsize() * 0.45),
                Math.min(DEEP_OVERLAP, start.getZsize() * 0.45));
        Sweep coreSweep = new Sweep(coreStart, delta);
        boolean moved = delta.lengthSqr() > EPS * EPS;
        return sections.anyNear(swept, craft -> {
            if (craft.isRemoved() || craft.collisionBoxes().isEmpty() || !craft.getBoundingBox().intersects(swept)) return false;
            Pose pose = craft.collisionPose();
            List<AABB> boxes = craft.collisionBoxes();
            if (overlapAt(coreStart, pose, boxes) != null) return false;
            Hit overlap = overlapAt(end, pose, boxes);
            if (overlap != null && overlap.depth > DEEP_OVERLAP) return true;
            return moved && hitPose(coreSweep, pose, boxes, null) != null;
        });
    }

    public static boolean hasSupport(Entity entity, AABB box, double distance) {
        return eligible(entity) && SECTIONS.containsKey(entity.level())
                && isFloor(hitCrafts(entity, new Sweep(box, new Vec3(0, -distance, 0)), null));
    }

    /** Whether the box overlaps the real block shapes (not the bounds) of any craft other than {@code except}. */
    static boolean overlapsCraft(Level level, AABB box, CraftEntity except) {
        CraftSections sections = SECTIONS.get(level);
        return sections != null && sections.anyNear(box, craft -> overlaps(craft, box, except));
    }

    private static boolean overlaps(CraftEntity craft, AABB box, CraftEntity except) {
        if (craft == except || craft.isRemoved() || !craft.getBoundingBox().intersects(box)) return false;
        List<AABB> parts = craft.collisionBoxes();
        return !parts.isEmpty() && overlapAt(box, craft.collisionPose(), parts) != null;
    }

    record Support(Entity entity, Vec3 point) {}

    /** Whether anything can be standing on a craft at rest: something moved against it lately, or a player is at it. */
    static boolean mayBeOccupied(CraftEntity craft) {
        if (craft.touchedAt >= craft.level().getGameTime() - 2) return true;
        // The server does not move players itself, so they are looked for.
        for (var player : craft.level().players()) {
            if (craft.getBoundingBox().inflate(0.5).intersects(player.getBoundingBox())) return true;
        }
        return false;
    }

    /** Living entities that stand on this craft's deck, with the contact point under their feet. */
    static List<Support> supported(CraftEntity craft) {
        List<AABB> boxes = craft.collisionBoxes();
        if (boxes.isEmpty()) return List.of();
        List<Entity> near = entitiesNear(craft, craft.getBoundingBox().inflate(0.5),
                entity -> entity instanceof LivingEntity && entity.onGround() && eligible(entity));
        if (near.isEmpty()) return List.of();
        Pose pose = craft.collisionPose();
        List<Support> result = new ArrayList<>();
        for (Entity entity : near) {
            Sweep probe = new Sweep(entity.getBoundingBox(), new Vec3(0, -2 * SUPPORT_DISTANCE, 0));
            Hit floor = hitPose(probe, pose, boxes, null);
            if (isFloor(floor)) result.add(new Support(entity, entity.position().add(probe.motion.scale(floor.time))));
        }
        return result;
    }

    static Vec3 localPoint(CraftEntity craft, Vec3 world) {
        return craft.collisionPose().localPoint(world);
    }

    public static void onPoseChanged(CraftEntity craft, Vec3 previousPos, Quaternionfc previousRot) {
        Crowd few = crowd;
        if (few != null && few.level == craft.level() && few.entities.isEmpty()) return;
        List<AABB> boxes = craft.collisionBoxes();
        if (boxes.isEmpty() || craft.level().getEntity(craft.getId()) != craft) return;
        Vec3 position = craft.position();
        Quaternionf rotation = craft.orientation(1.0f);
        Quaternionf difference = new Quaternionf(previousRot).conjugate().mul(rotation);
        double angle = 2 * Math.atan2(new Vec3(difference.x, difference.y, difference.z).length(), Math.abs(difference.w));
        AABB local = craft.hull().localBounds;
        double angularTravel = angle * absolute(local.getCenter()).add(halfSize(local)).length();
        double travel = previousPos.distanceTo(position) + angularTravel;
        if (travel <= EPS) return;
        int steps = (int) Math.ceil(travel / POSE_STEP);
        // shortcut: angular contact is sampled, not rotational CCD; leave fast/flipping hulls to normal entity movement.
        if (steps > MAX_POSE_STEPS) return;
        Pose before = new Pose(previousPos, previousRot);
        AABB region = before.worldBounds(local).minmax(craft.getBoundingBox()).inflate(angularTravel + SUPPORT_DISTANCE + EPS);
        // On the client only the local player is moved here; other entities arrive from the server already carried.
        List<Entity> nearby = entitiesNear(craft, region, entity -> eligible(entity)
                && (!craft.level().isClientSide() || entity instanceof net.minecraft.world.entity.player.Player p && p.isLocalPlayer()));
        if (nearby.isEmpty()) return;

        for (int step = 1; step <= steps; step++) {
            float fraction = (float) step / steps;
            Pose after = before.at(previousPos.lerp(position, fraction), new Quaternionf(previousRot).slerp(rotation, fraction));
            Vec3 translation = after.origin.subtract(before.origin);
            for (Entity entity : nearby) carryOrPush(craft, entity, boxes, before, after, translation);
            before = after;
        }
    }

    private static void carryOrPush(CraftEntity craft, Entity entity, List<AABB> boxes, Pose before, Pose after, Vec3 translation) {
        AABB start = entity.getBoundingBox();
        Vec3 desired = Vec3.ZERO;
        Sweep probe = new Sweep(start, new Vec3(0, -SUPPORT_DISTANCE, 0));
        Hit floor = hitPose(probe, before, boxes, null);
        boolean supported = isFloor(floor) && (entity.onGround() || entity.getDeltaMovement().y <= EPS);
        if (supported) {
            Hit terrain = hitWorld(entity, probe);
            supported = terrain == null || terrain.time > floor.time + EPS;
        }
        if (supported) {
            Vec3 foot = entity.position().add(probe.motion.scale(floor.time));
            desired = after.worldPoint(before.localPoint(foot)).subtract(foot);
        }

        // Relative motion against the old pose handles translating hulls without treating their swept bounds as solid.
        Vec3 relative = desired.subtract(translation);
        Hit bump = hitPose(new Sweep(start, relative), before, boxes, null);
        if (bump != null) desired = translation.add(beforeContact(relative, bump));
        Vec3 accepted = safeCarry(entity, new Sweep(start, desired), craft, null);

        // Small rotation or a shortened carry can leave the new pose slightly inside the entity.
        AABB end = start.move(accepted);
        Hit overlap = overlapAt(end, after, boxes);
        for (int i = 0; i < 6 && overlap != null && overlap.depth <= MAX_ESCAPE_DEPTH; i++) {
            Sweep escape = new Sweep(end, overlap.normal.scale(overlap.depth + 2 * EPS));
            Hit ownHull = earlier(hitPose(escape, after, boxes, null),
                    hitPose(new Sweep(end.move(translation.scale(-1)), escape.motion), before, boxes, null));
            Vec3 correction = safeCarry(entity, escape, craft, ownHull);
            if (negligible(correction) && escape.motion.y < -EPS) {
                double horiz = Math.hypot(overlap.normal.x, overlap.normal.z);
                if (horiz > 0.1) {
                    double scale = Math.min(MAX_ESCAPE_DEPTH, (overlap.depth + 2 * EPS) / horiz);
                    Sweep flatEscape = new Sweep(end, new Vec3(overlap.normal.x / horiz * scale, 0.0, overlap.normal.z / horiz * scale));
                    Hit flatOwn = earlier(hitPose(flatEscape, after, boxes, null),
                            hitPose(new Sweep(end.move(translation.scale(-1)), flatEscape.motion), before, boxes, null));
                    correction = safeCarry(entity, flatEscape, craft, flatOwn);
                }
            }
            if (negligible(correction)) break;
            accepted = accepted.add(correction);
            end = start.move(accepted);
            overlap = overlapAt(end, after, boxes);
        }
        if (negligible(accepted)) return;
        if (overlap != null && overlapAt(start, after, boxes) == null) return;
        entity.setPos(entity.position().add(accepted));
        if (!entity.level().isClientSide()) entity.needsSync = true;
        if (supported && isFloor(hitPose(new Sweep(end, probe.motion), after, boxes, null))) entity.setOnGround(true);
    }

    private static Vec3 safeCarry(Entity entity, Sweep path, CraftEntity craft, Hit ownHull) {
        if (negligible(path.motion)) return Vec3.ZERO;
        AABB swept = path.bounds;
        if (!entity.level().isClientSide() && !entity.level().hasChunksAt(BlockPos.containing(swept.minX, swept.minY, swept.minZ),
                BlockPos.containing(swept.maxX, swept.maxY, swept.maxZ))) return Vec3.ZERO;
        Hit hit = earlier(ownHull, earlier(hitWorld(entity, path), hitCrafts(entity, path, craft)));
        return hit == null ? path.motion : beforeContact(path.motion, hit);
    }

    private static boolean eligible(Entity entity) {
        return (entity instanceof LivingEntity || entity instanceof ItemEntity || entity instanceof PrimedTnt)
                && entity.isAlive() && !entity.noPhysics && !entity.isPassenger() && !entity.isSpectator();
    }

    /** Who may be near a craft, if few enough to be looked through; only while {@link #step} runs in that level. */
    private record Crowd(Level level, List<Entity> entities) {}

    private static final int CROWD = 24;
    private static volatile Crowd crowd;

    /** Runs a step of many crafts. With few creatures and items about, finding them once is cheaper than for every craft. */
    static void step(net.minecraft.server.level.ServerLevel level, boolean many, Runnable work) {
        if (many) {
            List<Entity> found = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (eligible(entity)) {
                    found.add(entity);
                    if (found.size() > CROWD) break;
                }
            }
            if (found.size() <= CROWD) crowd = new Crowd(level, found);
        }
        try {
            work.run();
        } finally {
            crowd = null;
        }
    }

    private static List<Entity> entitiesNear(CraftEntity craft, AABB region, java.util.function.Predicate<Entity> wanted) {
        Crowd few = crowd;
        if (few == null || few.level != craft.level()) return craft.level().getEntities(craft, region, wanted);
        List<Entity> near = List.of();
        for (Entity entity : few.entities) {
            if (entity.getBoundingBox().intersects(region) && wanted.test(entity)) {
                if (near.isEmpty()) near = new ArrayList<>();
                near.add(entity);
            }
        }
        return near;
    }

    private static boolean negligible(Vec3 motion) {
        return motion.lengthSqr() <= EPS * EPS;
    }

    private static boolean isFloor(Hit hit) {
        return hit != null && hit.normal.y >= FLOOR_NORMAL;
    }

    private static Vec3 retainY(Vec3 motion, Vec3 vanilla) {
        // Entity.move uses exact Y equality to set its vertical collision flag.
        return Math.abs(motion.y - vanilla.y) <= EPS ? new Vec3(motion.x, vanilla.y, motion.z) : motion;
    }

    private static Vec3 beforeContact(Vec3 movement, Hit hit) {
        return hit.time <= 0 ? Vec3.ZERO : movement.scale(Math.max(0, hit.time - 2 * EPS / movement.length()));
    }

    private static Hit hitCrafts(Entity entity, Sweep path, CraftEntity excluded) {
        Level level = entity.level();
        CraftSections sections = SECTIONS.get(level);
        if (sections == null) return null;
        Hit[] best = {null};
        List<CraftEntity> gone = new ArrayList<>(0);
        sections.forEachNear(path.bounds, craft -> {
            // Removing from the index while it is being walked would skip neighbours of the removed craft.
            if (craft.isRemoved()) {
                gone.add(craft);
            } else if (craft != excluded && craft.getBoundingBox().intersects(path.bounds)
                    && level.getEntity(craft.getId()) == craft && !craft.collisionBoxes().isEmpty()) {
                craft.touchedAt = level.getGameTime();
                Hit hit = hitPose(path, craft.collisionPose(), craft.collisionBoxes(), null);
                if (hit != null) best[0] = earlier(best[0], new Hit(hit.time, hit.normal, hit.depth, craft));
            }
        });
        for (CraftEntity craft : gone) {
            forget(level, craft);
            sections.remove(craft);
        }
        return best[0];
    }

    private static void forget(Level level, CraftEntity craft) {
        CRAFTS.computeIfPresent(level, (unused, crafts) -> {
            crafts.remove(craft);
            return crafts.isEmpty() ? null : crafts;
        });
    }

    private static Hit hitPose(Sweep path, Pose pose, List<AABB> boxes, Hit best) {
        if (path.motion.lengthSqr() == 0) return best;
        AABB broad = pose.localBounds(path.bounds);
        var near = boxes instanceof BoxIndex index ? index.near(broad) : null;
        for (int i = 0, size = near == null ? boxes.size() : near.size(); i < size; i++) {
            AABB box = boxes.get(near == null ? i : near.getInt(i));
            if (near != null || box.intersects(broad)) {
                Hit hit = sweep(path, pose.worldPoint(box.getCenter()), halfSize(box), pose.axes);
                best = earlier(best, opposing(path, hit));
            }
        }
        return best;
    }

    private static Hit overlapAt(AABB box, Pose pose, List<AABB> boxes) {
        Sweep path = new Sweep(box, Vec3.ZERO);
        AABB broad = pose.localBounds(path.bounds);
        var near = boxes instanceof BoxIndex index ? index.near(broad) : null;
        Hit deepest = null;
        for (int i = 0, size = near == null ? boxes.size() : near.size(); i < size; i++) {
            AABB part = boxes.get(near == null ? i : near.getInt(i));
            if (near != null || part.intersects(broad)) {
                Hit overlap = sweep(path, pose.worldPoint(part.getCenter()), halfSize(part), pose.axes);
                if (overlap != null && (deepest == null || overlap.depth > deepest.depth)) deepest = overlap;
            }
        }
        return deepest;
    }

    private static Hit hitWorld(Entity entity, Sweep path) {
        Level level = entity.level();
        Hit best = null;
        for (VoxelShape shape : level.getBlockCollisions(entity, path.bounds)) best = hitVoxels(path, shape, best);
        for (VoxelShape shape : level.getEntityCollisions(entity, path.bounds)) best = hitVoxels(path, shape, best);
        WorldBorder border = level.getWorldBorder();
        if (border.isInsideCloseToBorder(entity, path.bounds)) best = hitVoxels(path, border.getCollisionShape(), best);
        return best;
    }

    private static Hit hitVoxels(Sweep path, VoxelShape shape, Hit best) {
        for (AABB box : shape.toAabbs()) {
            if (box.intersects(path.bounds)) {
                // World-border shapes have infinite coordinates.
                AABB finite = box.intersect(path.bounds.inflate(1));
                best = earlier(best, opposing(path, sweep(path, finite.getCenter(), halfSize(finite), ALIGNED)));
            }
        }
        return best;
    }

    private static Hit opposing(Sweep path, Hit hit) {
        return hit != null && (hit.depth == 0 || path.motion.dot(hit.normal) < -EPS) ? hit : null;
    }

    private static Hit earlier(Hit a, Hit b) {
        return a == null || b != null && b.time < a.time ? b : a;
    }

    private static Hit sweep(Sweep path, Vec3 center, Vec3 half, List<Axis> axes) {
        Vec3 offset = path.center.subtract(center);
        double enter = Double.NEGATIVE_INFINITY;
        double exit = Double.POSITIVE_INFINITY;
        double depth = Double.POSITIVE_INFINITY;
        Vec3 normal = null;
        Vec3 escape = null;
        double normalSign = 1, escapeSign = 1;
        // Intersect the time intervals of all separating axes, not just either box's face axes.
        for (Axis axis : axes) {
            double radius = path.half.dot(axis.world) + half.dot(axis.local) - EPS;
            double start = offset.dot(axis.normal);
            double velocity = path.motion.dot(axis.normal);
            double penetration = radius - Math.abs(start);
            if (penetration < depth) {
                depth = penetration;
                escape = axis.normal;
                escapeSign = Math.copySign(1, start == 0 ? velocity : start);
            }
            if (Math.abs(velocity) < 1.0e-12) {
                if (penetration <= 0) return null;
                continue;
            }
            double t0 = (-radius - start) / velocity;
            double t1 = (radius - start) / velocity;
            double near = Math.min(t0, t1);
            if (near > enter) {
                enter = near;
                normal = axis.normal;
                normalSign = -Math.signum(velocity);
            }
            exit = Math.min(exit, Math.max(t0, t1));
            if (enter >= exit || enter >= 1 || exit <= 0) return null;
        }
        if (depth > 0) return new Hit(0, escape.scale(escapeSign), depth, null);
        return new Hit(Math.max(0, enter), normal.scale(normalSign), 0, null);
    }

    private static Vec3 halfSize(AABB box) {
        return new Vec3(box.getXsize(), box.getYsize(), box.getZsize()).scale(0.5);
    }

    private static Vec3 absolute(Vec3 v) {
        return new Vec3(Math.abs(v.x), Math.abs(v.y), Math.abs(v.z));
    }

    private record Hit(double time, Vec3 normal, double depth, CraftEntity craft) {}

    private record Sweep(AABB bounds, Vec3 center, Vec3 half, Vec3 motion) {
        Sweep(AABB box, Vec3 motion) {
            this(box.expandTowards(motion).inflate(4 * EPS), box.getCenter(), halfSize(box), motion);
        }
    }

    private record Axis(Vec3 normal, Vec3 world, Vec3 local) {
        Axis(Vec3 normal, Vec3[] basis) {
            this(normal, absolute(normal), absolute(new Vec3(normal.dot(basis[0]), normal.dot(basis[1]), normal.dot(basis[2]))));
        }
    }

    static final class Pose {
        final Vec3 origin;
        final Vec3[] basis;
        final List<Axis> axes;
        private final float x, y, z, w;

        Pose(Vec3 origin, Quaternionfc rotation) {
            this(origin, rotation, null);
        }

        private Pose(Vec3 origin, Quaternionfc rotation, Pose sameRotation) {
            this.origin = origin;
            x = rotation.x(); y = rotation.y(); z = rotation.z(); w = rotation.w();
            if (sameRotation != null) {
                basis = sameRotation.basis;
                axes = sameRotation.axes;
                return;
            }
            if (x == 0 && y == 0 && z == 0 && w != 0) {
                basis = WORLD;
                axes = ALIGNED;
                return;
            }
            basis = new Vec3[3];
            axes = new ArrayList<>(15);
            Vec3 imaginary = new Vec3(x, y, z);
            double real = w;
            double factor = 2 / (imaginary.lengthSqr() + real * real);
            for (int i = 0; i < 3; i++) {
                Vec3 t = imaginary.cross(WORLD[i]).scale(factor);
                basis[i] = WORLD[i].add(t.scale(real)).add(imaginary.cross(t));
            }
            for (Vec3 axis : WORLD) axes.add(new Axis(axis, basis));
            for (Vec3 axis : basis) axes.add(new Axis(axis, basis));
            for (Vec3 a : WORLD) {
                for (Vec3 b : basis) {
                    Vec3 cross = a.cross(b);
                    double lengthSqr = cross.lengthSqr();
                    if (lengthSqr > 1.0e-18) axes.add(new Axis(cross.scale(1 / Math.sqrt(lengthSqr)), basis));
                }
            }
        }

        /** A translation changes no separating axis; reuse them regardless of how many entities query this pose. */
        Pose at(Vec3 position, Quaternionfc rotation) {
            boolean sameRotation = x == rotation.x() && y == rotation.y() && z == rotation.z() && w == rotation.w();
            if (sameRotation && origin.equals(position)) return this;
            return new Pose(position, rotation, sameRotation ? this : null);
        }

        Vec3 worldPoint(Vec3 local) {
            if (basis == WORLD) return origin.add(local);
            return origin.add(basis[0].scale(local.x)).add(basis[1].scale(local.y)).add(basis[2].scale(local.z));
        }

        Vec3 localPoint(Vec3 world) {
            Vec3 offset = world.subtract(origin);
            if (basis == WORLD) return offset;
            return new Vec3(offset.dot(basis[0]), offset.dot(basis[1]), offset.dot(basis[2]));
        }

        AABB worldBounds(AABB local) {
            if (basis == WORLD) return local.move(origin);
            Vec3 center = worldPoint(local.getCenter());
            Vec3 half = halfSize(local);
            Vec3 extent = new Vec3(half.dot(axes.get(0).local), half.dot(axes.get(1).local), half.dot(axes.get(2).local));
            return new AABB(center.subtract(extent), center.add(extent));
        }

        AABB localBounds(AABB world) {
            if (basis == WORLD) return world.move(-origin.x, -origin.y, -origin.z);
            Vec3 center = localPoint(world.getCenter());
            Vec3 half = halfSize(world);
            Vec3 extent = new Vec3(half.dot(axes.get(3).world), half.dot(axes.get(4).world), half.dot(axes.get(5).world));
            return new AABB(center.subtract(extent), center.add(extent));
        }
    }
}
