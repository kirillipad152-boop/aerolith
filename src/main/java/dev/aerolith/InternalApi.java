package dev.aerolith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;
import dev.aerolith.api.AerolithEvents;
import dev.aerolith.api.BlockPhysicsProperties;
import dev.aerolith.api.CraftHandle;
import dev.aerolith.api.CraftHit;
import dev.aerolith.api.SurfacePreset;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import org.joml.Quaternionf;

/**
 * Internal bridge backing {@code dev.aerolith.api}. External mods should use classes under {@code dev.aerolith.api}.
 */
public final class InternalApi {
    private InternalApi() {}

    public static CraftHandle handle(CraftEntity craft) {
        return new Handle(craft);
    }

    public static List<CraftHandle> crafts(ServerLevel level) {
        return WorldPhysics.of(level).handles();
    }

    public static List<CraftHandle> craftsInBox(Level level, AABB box) {
        CraftSections sections = HullCollisions.sectionsIfPresent(level);
        if (sections == null) return List.of();
        List<CraftHandle> out = new ArrayList<>();
        sections.forEachNear(box, craft -> {
            if (!craft.isRemoved() && craft.getBoundingBox().intersects(box)) {
                out.add(handle(craft));
            }
        });
        return out;
    }

    @Nullable
    public static CraftHit raycast(Level level, Vec3 origin, Vec3 direction, double maxDistance, boolean includeFluids) {
        CraftEditing.Located hit = CraftEditing.nearest(level, origin, direction, maxDistance, 1.0f, includeFluids);
        if (hit == null) return null;
        CraftEntity craft = hit.craft();
        CraftEditing.Target target = hit.target();
        Hull.Pick pick = target.pick();
        Vec3 worldHit = craft.position().add(Hull.rotate(craft.orientation(1.0f), target.localHit()));
        return new CraftHit(handle(craft), pick.block().offset(), pick.block().state(),
                pick.face(), target.localHit(), worldHit, pick.distance());
    }

    @Nullable
    public static CraftHandle assemble(ServerLevel level, BlockPos from, BlockPos to, @Nullable ServerPlayer actor) {
        ServerPlayer resolved = actor != null ? actor : FakePlayerFactory.getMinecraft(level);
        CraftEntity craft = StructureTransfer.assemble(level, resolved, from, to);
        return craft == null ? null : handle(craft);
    }

    @Nullable
    public static CraftHandle assembleConnected(ServerLevel level, BlockPos seed, int range, @Nullable ServerPlayer actor) {
        ServerPlayer resolved = actor != null ? actor : FakePlayerFactory.getMinecraft(level);
        CraftEntity craft = StructureTransfer.assembleConnected(level, resolved, seed, range);
        return craft == null ? null : handle(craft);
    }

    @Nullable
    public static CraftHandle createFromBlocks(
            ServerLevel level, Map<BlockPos, BlockState> blocks, Vec3 worldOrigin, @Nullable UUID owner
    ) {
        List<CraftBlock> list = new ArrayList<>(blocks.size());
        for (var entry : blocks.entrySet()) {
            BlockPos offset = entry.getKey().immutable();
            BlockState state = entry.getValue();
            if (!PlotGrid.validOffset(offset) || state == null || state.isAir() || CraftEntity.isFluid(state)) continue;
            list.add(new CraftBlock(offset, state));
        }
        if (list.isEmpty()) return null;
        CraftEntity craft = StructureTransfer.create(level, list, worldOrigin, owner != null ? owner : new UUID(0L, 0L));
        return craft == null ? null : handle(craft);
    }

    public static boolean disassemble(ServerLevel level, CraftHandle craft, @Nullable ServerPlayer actor) {
        if (!(craft.entity().level() == level)) return false;
        ServerPlayer resolved = actor != null ? actor : FakePlayerFactory.getMinecraft(level);
        return StructureTransfer.dock(level, resolved, craft.entity());
    }

    public static void setWind(ServerLevel level, Vec3 wind) {
        WorldPhysics.of(level).wind(wind);
    }

    public static Vec3 getWind(ServerLevel level) {
        return WorldPhysics.of(level).wind();
    }

    public static int craftCount(ServerLevel level) {
        return WorldPhysics.of(level).craftCount();
    }

    public static int remainingCapacity(ServerLevel level) {
        return WorldPhysics.of(level).space();
    }

    public static void registerMaterial(Block block, BlockPhysicsProperties props) {
        Materials.Surface surface = props.preset() == null ? null : switch (props.preset()) {
            case DEFAULT -> Materials.Surface.DEFAULT;
            case WOOD -> Materials.Surface.WOOD;
            case STONE -> Materials.Surface.STONE;
            case METAL -> Materials.Surface.METAL;
            case GLASS -> Materials.Surface.GLASS;
            case ICE -> Materials.Surface.ICE;
            case WOOL -> Materials.Surface.WOOL;
            case SLIME -> Materials.Surface.SLIME;
        };
        Materials.AeroProperty aero = props.aerodynamicBehavior() == null ? null : new Materials.AeroProperty(
                props.aerodynamicBehavior().nativeKind(),
                props.aerodynamicDirection(),
                props.aerodynamicMagnitude(),
                props.scaleAerodynamicByArea()
        );
        Materials.registerCustom(block, new Materials.CustomProperties(
                props.density(), surface, props.friction(), props.restitution(), props.sealsCavity(), aero));
    }

    public static double density(BlockState state) {
        return Materials.massDensity(state);
    }

    public static double friction(ServerLevel level, BlockState state) {
        return WorldPhysics.of(level).contact(state).mu();
    }

    public static double restitution(ServerLevel level, BlockState state) {
        return WorldPhysics.of(level).contact(state).restitution();
    }

    public static boolean sealsCavity(BlockState state) {
        return Materials.sealsCavity(state);
    }

    static void notifyPostStep(ServerLevel level) {
        AerolithEvents.dispatchPostStep(level);
    }

    private record Handle(CraftEntity entity) implements CraftHandle {
        @Override public int id() { return entity.getId(); }
        @Override public UUID uuid() { return entity.getUUID(); }
        @Override @Nullable public UUID owner() { return entity.ownerId(); }
        @Override public Level level() { return entity.level(); }
        @Override public boolean isValid() { return !entity.isRemoved(); }

        @Override public boolean isSimulated() {
            return entity.level() instanceof ServerLevel server && WorldPhysics.of(server).isSimulated(entity);
        }

        @Override public Vec3 position() { return entity.position(); }
        @Override public Vec3 centerOfMass() {
            return entity.hull() != null ? entity.worldCenterOfMass() : entity.position();
        }
        @Override public Quaternionf orientation() { return entity.orientation(1.0f); }
        @Override public Vec3 linearVelocity() { return entity.comVelocity(); }
        @Override public Vec3 angularVelocity() { return entity.angularVelocity(); }
        @Override public double speed() { return entity.speed(); }
        @Override public double spin() { return entity.spin(); }
        @Override public double mass() { Hull h = entity.hull(); return h != null ? h.mass : 0.0; }
        @Override public double boundingRadius() { Hull h = entity.hull(); return h != null ? h.radius : 0.0; }
        @Override public double sealedCavityVolume() { Hull h = entity.hull(); return h != null ? h.cavityVolume : 0.0; }
        @Override public AABB localBounds() { Hull h = entity.hull(); return h != null ? h.localBounds : new AABB(0, 0, 0, 0, 0, 0); }
        @Override public int blockCount() { Hull h = entity.hull(); return h != null ? h.blocks.size() : 0; }

        @Override @Nullable public BlockState getBlockState(BlockPos localOffset) {
            return entity.cells().get(localOffset);
        }

        @Override public Vec3 localToWorld(Vec3 localPoint) {
            return entity.position().add(Hull.rotate(entity.orientation(1.0f), localPoint));
        }

        @Override public Vec3 worldToLocal(Vec3 worldPoint) {
            return Hull.rotate(entity.orientation(1.0f).conjugate(), worldPoint.subtract(entity.position()));
        }

        @Override public boolean isAnchored() { return entity.anchored(); }
        @Override public void setAnchored(boolean anchored) { entity.setAnchored(anchored); }

        @Override public boolean wake() {
            return entity.level() instanceof ServerLevel server && WorldPhysics.of(server).wake(entity);
        }

        @Override public boolean applyImpulse(Vec3 impulse) {
            return entity.level() instanceof ServerLevel server && WorldPhysics.of(server).impulse(entity, impulse);
        }

        @Override public boolean applyImpulseAt(Vec3 impulse, Vec3 worldPoint) {
            return entity.level() instanceof ServerLevel server && WorldPhysics.of(server).impulseAt(entity, impulse, worldPoint);
        }

        @Override public boolean queueLoad(Vec3 impulse, Vec3 worldPoint) {
            if (!(entity.level() instanceof ServerLevel)) return false;
            entity.queuePush(worldPoint, impulse);
            return true;
        }

        @Override public boolean setThrottle(double throttle) {
            return entity.level() instanceof ServerLevel server && WorldPhysics.of(server).setThrottle(entity, throttle);
        }

        @Override public boolean setControls(double pitch, double yaw, double roll) {
            return entity.level() instanceof ServerLevel server && WorldPhysics.of(server).setControls(entity, pitch, yaw, roll);
        }

        @Override public boolean setFlaps(double flaps) {
            return entity.level() instanceof ServerLevel server && WorldPhysics.of(server).setFlaps(entity, flaps);
        }

        @Override public boolean setBlock(BlockPos localOffset, BlockState state) {
            return entity.setBlockAtOffset(localOffset, state);
        }

        @Override public void dissolveWithDrops() {
            if (entity.level() instanceof ServerLevel) entity.dissolve();
        }
    }
}
