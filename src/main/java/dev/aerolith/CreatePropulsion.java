package dev.aerolith;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What Create's rotating machines do for a craft they are part of. Their blocks live in the craft's plot, where they run as
 * they do anywhere (this is not touched here); all that is added is that spinning air movers push back.
 * <ul>
 * <li>An <b>encased fan</b> throws air out of its front, so the craft is pushed the other way. A fan at full speed gives
 * {@link #FAN_NEWTONS}, and less with the square of its speed. A wall at its front stops it.</li>
 * <li>A <b>mechanical or windmill bearing</b> that carries a contraption makes its blocks blades, each one square metre.
 * A blade at distance r from the axis meets the air at ω·r, and pushes with ½·ρ·C·(ω·r)². Positive rotation throws air toward
 * the way the bearing faces, as a fan does; reversing it reverses the push.</li>
 * </ul>
 * shortcut: a windmill bearing makes its own rotation out of its sails, so a craft with one pushes itself with no power
 * source. That is what Create gives it; a craft that must pay for its thrust should use a mechanical bearing on a motor.
 */
final class CreatePropulsion {
    /** Thrust of a fan at 256 rpm. A Thruster block gives 10 000. */
    static final double FAN_NEWTONS = 2500.0;
    static final double FAN_FULL_RPM = 256.0;
    private static final double AIR_DENSITY = 1.2;
    /** Lift coefficient of a blade that is a block turned at a fixed pitch; tuned by flying, not derived. */
    private static final double BLADE_COEFFICIENT = 0.35;
    /** No single bearing pushes with more than this: a block-sized hub with a house on it is not a propeller. */
    private static final double MAX_BEARING_NEWTONS = 80_000.0;

    private CreatePropulsion() {}

    static List<Propulsion.Drive> scan(CraftEntity craft) {
        List<Propulsion.Drive> found = new ArrayList<>();
        ServerLevel plot = craft.plotLevel();
        if (plot == null) return found;
        for (long key : craft.chunkKeys()) {
            LevelChunk chunk = PlotChunks.find(plot, ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) continue;
            for (BlockEntity entity : chunk.getBlockEntities().values()) {
                if (entity instanceof EncasedFanBlockEntity fan) {
                    found.add(new Fan(craft, plot, fan));
                } else if (entity instanceof MechanicalBearingBlockEntity bearing) {
                    found.add(new Bearing(craft, plot, bearing));
                }
            }
        }
        return found;
    }

    private static Vec3 offset(CraftEntity craft, BlockPos pos) {
        return Vec3.atLowerCornerOf(pos.subtract(craft.plotOrigin()));
    }

    private static Vec3 step(Direction direction) {
        return new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
    }

    private static final class Fan implements Propulsion.Drive {
        private final CraftEntity craft;
        private final ServerLevel plot;
        private final EncasedFanBlockEntity fan;

        Fan(CraftEntity craft, ServerLevel plot, EncasedFanBlockEntity fan) {
            this.craft = craft;
            this.plot = plot;
            this.fan = fan;
        }

        @Override
        public boolean alive() {
            return !fan.isRemoved();
        }

        @Override
        public Vec3 force() {
            Direction flow = fan.getAirFlowDirection();
            if (flow == null) return Vec3.ZERO;
            BlockPos front = fan.getBlockPos().relative(flow);
            if (!plot.getBlockState(front).getCollisionShape(plot, front).isEmpty()) return Vec3.ZERO;
            double speed = Math.min(Math.abs(fan.getSpeed()), FAN_FULL_RPM) / FAN_FULL_RPM;
            return step(flow).scale(-FAN_NEWTONS * speed * speed);
        }

        @Override
        public Vec3 at() {
            return offset(craft, fan.getBlockPos());
        }
    }

    private static final class Bearing implements Propulsion.Drive {
        private final CraftEntity craft;
        private final ServerLevel plot;
        private final MechanicalBearingBlockEntity bearing;
        private ControlledContraptionEntity contraption;
        private int blocks = -1;
        private double bladeArea;
        private long lookedAt = -100;

        Bearing(CraftEntity craft, ServerLevel plot, MechanicalBearingBlockEntity bearing) {
            this.craft = craft;
            this.plot = plot;
            this.bearing = bearing;
        }

        @Override
        public boolean alive() {
            return !bearing.isRemoved();
        }

        private ControlledContraptionEntity find() {
            if (contraption != null && contraption.isAlive() && bearing.isAttachedTo(contraption)) return contraption;
            contraption = null;
            long now = plot.getGameTime();
            if (now - lookedAt < 10) return null;
            lookedAt = now;
            var near = plot.getEntitiesOfClass(ControlledContraptionEntity.class, new AABB(bearing.getBlockPos()).inflate(3),
                    bearing::isAttachedTo);
            if (near.isEmpty()) return null;
            blocks = -1;
            return contraption = near.getFirst();
        }

        @Override
        public Vec3 force() {
            ControlledContraptionEntity moving = find();
            if (moving == null || moving.isStalled()) return Vec3.ZERO;
            double omega = Math.toRadians(bearing.getAngularSpeed()) * 20.0;
            if (omega == 0.0) return Vec3.ZERO;
            Direction facing = bearing.getBlockState().getValue(BlockStateProperties.FACING);
            var blocks = moving.getContraption().getBlocks();
            if (blocks.size() != this.blocks) {
                this.blocks = blocks.size();
                bladeArea = 0.0;
                // The sum of r² over the blocks: how much blade there is, weighted by how fast it moves.
                for (BlockPos at : blocks.keySet()) {
                    double along = at.getX() * facing.getStepX() + at.getY() * facing.getStepY() + at.getZ() * facing.getStepZ();
                    bladeArea += at.distSqr(BlockPos.ZERO) - along * along;
                }
            }
            double newtons = 0.5 * AIR_DENSITY * BLADE_COEFFICIENT * omega * omega * bladeArea;
            return step(facing).scale(-Math.signum(omega) * Math.min(newtons, MAX_BEARING_NEWTONS));
        }

        @Override
        public Vec3 at() {
            return offset(craft, bearing.getBlockPos());
        }
    }
}
