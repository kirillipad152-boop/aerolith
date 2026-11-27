package dev.aerolith;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionfc;

final class Hull {
    /** Blocks may lie this far from the craft origin on each axis: the size of one cell of the plot dimension. */
    static final int REACH = PlotGrid.REACH;
    static final int MAX_BLOCKS = 1_000_000;
    static final int MAX_SHAPES_PER_BLOCK = 4;
    /** Density of a solid block that has no entry of its own. */
    static final double DEFAULT_DENSITY = 800.0;
    private static final Direction[] FACES = Direction.values();

    final List<CraftBlock> blocks;
    final Vec3 localCom;
    final double radius;
    final double mass;
    final Vec3 seat;
    final AABB localBounds;
    final List<AABB> collisionBoxes;
    private final List<Part> parts;
    final double[] surfaces;
    final boolean powered;

    private record Part(CraftBlock owner, AABB box, double density) {}

    /** Shapes that can be aimed at but are not solid: torches, levers, signs, and fluids. */
    private final List<Part> pickParts;
    private final List<Part> fluidParts;

    Hull(List<CraftBlock> blocks) {
        this(blocks, List.of());
    }

    /** {@code fluids} are cells that hold only fluid: no collider and no mass, but a bucket can pick them up. */
    Hull(List<CraftBlock> blocks, List<CraftBlock> fluids) {
        this.blocks = List.copyOf(blocks);
        Map<BlockPos, List<AABB>> cells = new HashMap<>();
        List<Part> parts = new ArrayList<>();
        List<Part> pickParts = new ArrayList<>();
        for (CraftBlock block : blocks) {
            Vec3 offset = Vec3.atLowerCornerOf(block.offset()).subtract(0.5, 0.5, 0.5);
            List<AABB> boxes = Materials.boxes(block.state()).stream().map(b -> b.move(offset)).toList();
            cells.put(block.offset(), boxes);
            double density = Materials.massDensity(block.state());
            for (AABB b : boxes) parts.add(new Part(block, b, density));
            if (boxes.isEmpty()) {
                for (AABB b : Materials.outline(block.state())) pickParts.add(new Part(block, b.move(offset), 0.0));
            }
        }
        List<Part> fluidParts = new ArrayList<>();
        for (CraftBlock fluid : fluids) {
            Vec3 offset = Vec3.atLowerCornerOf(fluid.offset()).subtract(0.5, 0.5, 0.5);
            fluidParts.add(new Part(fluid, new AABB(0, 0, 0, 1, 0.9, 1).move(offset), 0.0));
        }
        this.pickParts = List.copyOf(pickParts);
        this.fluidParts = List.copyOf(fluidParts);
        this.parts = List.copyOf(parts);
        collisionBoxes = new BoxIndex(parts.stream().map(Part::box).toList());
        DoubleArrayList surfaces = new DoubleArrayList(blocks.size() * 12);
        Vec3 weighted = Vec3.ZERO;
        double kg = 0.0;
        AABB bounds = null;
        for (Part part : parts) {
            AABB box = part.box;
            double mass = part.density * box.getXsize() * box.getYsize() * box.getZsize();
            kg += mass;
            weighted = weighted.add(box.getCenter().scale(mass));
            bounds = bounds == null ? box : bounds.minmax(box);
        }
        Vec3 seat = null;
        boolean powered = false;

        for (CraftBlock block : blocks) {
            BlockPos p = block.offset();
            Vec3 center = Vec3.atLowerCornerOf(p);
            if (seat == null && (block.state().is(AerolithParts.SEAT.get()) || block.state().is(Blocks.RED_WOOL))
                    && !cells.containsKey(p.above()) && !cells.containsKey(p.above(2))) {
                double top = cells.get(p).stream().mapToDouble(b -> b.maxY).max().orElse(p.getY() + 0.5);
                seat = new Vec3(center.x, top + 0.03, center.z);
            }

            Direction foilNormal = block.state().is(AerolithParts.WING.get()) || block.state().is(Blocks.LIGHT_BLUE_WOOL)
                    ? Direction.UP
                    : block.state().is(Blocks.YELLOW_WOOL) ? Direction.EAST : null;
            int foilKind = 1;
            if (block.state().getBlock() instanceof ControlSurfaceBlock control) {
                foilNormal = ControlSurfaceBlock.normal(block.state());
                foilKind = control.kind();
            }
            if (foilNormal != null && !cells.containsKey(p.relative(foilNormal))
                    && !cells.containsKey(p.relative(foilNormal.getOpposite()))) {
                // shortcut: foil-marked cubes are modeled as flat one-square-metre surfaces, upgrade to actual profiles for configurable parts.
                surface(surfaces, foilKind, center, foilNormal, 1.0);
            } else {
                List<AABB> own = cells.get(p);
                for (AABB box : own) {
                    for (Direction face : FACES) {
                        exposedFace(surfaces, box, face, own, cells.getOrDefault(p.relative(face), List.of()));
                    }
                }
            }

            boolean engine = block.state().is(AerolithParts.THRUSTER.get());
            Direction thrust = block.state().is(Blocks.ORANGE_WOOL) ? Direction.UP
                    : block.state().is(Blocks.LIME_GLAZED_TERRACOTTA)
                        ? block.state().getValue(HorizontalDirectionalBlock.FACING)
                        : engine ? block.state().getValue(BlockStateProperties.FACING) : null;
            if (thrust != null && !cells.containsKey(p.relative(thrust.getOpposite()))) {
                surface(surfaces, 2, center, thrust, engine ? 10000.0 : thrust == Direction.UP ? 9000.0 : 11000.0);
                powered = true;
            }
        }
        if (kg <= 0.0 || bounds == null) throw new IllegalArgumentException("A hull needs at least one solid block");
        this.mass = kg;
        this.localCom = weighted.scale(1.0 / kg);
        double extent = 0;
        for (AABB box : collisionBoxes) {
            Vec3 d = box.getCenter().subtract(localCom);
            extent = Math.max(extent, new Vec3(Math.abs(d.x) + box.getXsize() * 0.5,
                    Math.abs(d.y) + box.getYsize() * 0.5, Math.abs(d.z) + box.getZsize() * 0.5).length());
        }
        this.radius = extent;
        this.seat = seat;
        this.localBounds = bounds;
        this.surfaces = surfaces.toDoubleArray();
        this.powered = powered;
    }

    private double[] displayBoxes;

    /** Center, half extents, friction (MIXED for a cell grid), restitution. */
    static final int COLLIDER = 8;
    static final double MIXED = -1.0;
    record Colliders(double[] boxes, double[] frictionCells) {}

    /** The colliders the native engine gets, with default materials, for the debug view. */
    double[] displayBoxes() {
        if (displayBoxes == null) displayBoxes = colliders(new FrictionConfig(List.of())).boxes();
        return displayBoxes;
    }

    /** Where the mass is: center, half extents and density of every solid shape. */
    double[] masses() {
        DoubleArrayList out = new DoubleArrayList(parts.size() * 7);
        for (Part part : parts) {
            Vec3 center = part.box.getCenter();
            out.add(center.x);
            out.add(center.y);
            out.add(center.z);
            out.add(part.box.getXsize() * 0.5);
            out.add(part.box.getYsize() * 0.5);
            out.add(part.box.getZsize() * 0.5);
            out.add(part.density);
        }
        return out.toDoubleArray();
    }

    /** A box grown out of solid parts. Only parts inside their owner's cell can use block-grid friction. */
    private static final class Solid {
        final double[] min;
        final double[] max;
        double mu;
        boolean inCells;
        final double restitution;

        Solid(Part part, FrictionConfig.Contact material) {
            AABB box = part.box;
            BlockPos p = part.owner.offset();
            min = new double[] {box.minX, box.minY, box.minZ};
            max = new double[] {box.maxX, box.maxY, box.maxZ};
            inCells = box.minX >= p.getX() - 0.5 && box.maxX <= p.getX() + 0.5
                    && box.minY >= p.getY() - 0.5 && box.maxY <= p.getY() + 0.5
                    && box.minZ >= p.getZ() - 0.5 && box.maxZ <= p.getZ() + 0.5;
            mu = material.mu();
            restitution = material.restitution();
        }

        boolean extend(Solid next, int axis) {
            if (mu != next.mu) {
                if (!inCells || !next.inCells) return false;
                mu = MIXED;
            }
            inCells &= next.inCells;
            max[axis] = next.max[axis];
            return true;
        }
    }

    private record CrossSection(long a1, long a2, long b1, long b2, double restitution) {}

    /** Only mixed boxes have a grid: one coefficient per intersected block cell, X fastest, then Y, then Z. */
    Colliders colliders(FrictionConfig settings) {
        List<Solid> solids = new ArrayList<>(parts.size());
        for (Part part : parts) solids.add(new Solid(part, settings.contact(part.owner.state())));
        for (int axis : new int[] {0, 2, 1}) solids = merge(solids, axis);
        DoubleArrayList out = new DoubleArrayList(solids.size() * COLLIDER);
        DoubleArrayList grid = new DoubleArrayList();
        Map<BlockPos, FrictionConfig.Contact> cells = null;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Solid solid : solids) {
            for (int axis = 0; axis < 3; axis++) out.add((solid.min[axis] + solid.max[axis]) * 0.5);
            for (int axis = 0; axis < 3; axis++) out.add((solid.max[axis] - solid.min[axis]) * 0.5);
            out.add(solid.mu);
            out.add(solid.restitution);
            if (solid.mu != MIXED) continue;
            if (cells == null) {
                cells = new HashMap<>();
                for (CraftBlock b : blocks) cells.put(b.offset(), settings.contact(b.state()));
            }
            // Inset well below the minimum part width (0.002 m) so a face on a cell boundary counts its inside only.
            int x0 = firstCell(solid.min[0]), x1 = lastCell(solid.max[0]);
            int y0 = firstCell(solid.min[1]), y1 = lastCell(solid.max[1]);
            int z0 = firstCell(solid.min[2]), z1 = lastCell(solid.max[2]);
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    for (int x = x0; x <= x1; x++) grid.add(cells.get(cursor.set(x, y, z)).mu());
                }
            }
        }
        return new Colliders(out.toDoubleArray(), grid.toDoubleArray());
    }

    private static int firstCell(double min) { return (int) Math.floor(min + 0.5 + 1e-6); }
    private static int lastCell(double max) { return (int) Math.floor(max + 0.5 - 1e-6); }

    private static List<Solid> merge(List<Solid> solids, int axis) {
        int a = (axis + 1) % 3, b = (axis + 2) % 3;
        Map<CrossSection, List<Solid>> rows = new LinkedHashMap<>();
        for (Solid solid : solids) {
            rows.computeIfAbsent(new CrossSection(micro(solid.min[a]), micro(solid.max[a]), micro(solid.min[b]), micro(solid.max[b]),
                    solid.restitution), key -> new ArrayList<>()).add(solid);
        }
        List<Solid> out = new ArrayList<>();
        for (List<Solid> row : rows.values()) {
            row.sort(Comparator.comparingDouble(solid -> solid.min[axis]));
            Solid last = null;
            for (Solid solid : row) {
                if (last == null || micro(last.max[axis]) != micro(solid.min[axis]) || !last.extend(solid, axis)) {
                    out.add(last = solid);
                }
            }
        }
        return out;
    }

    private static long micro(double value) {
        return Math.round(value * 1e6);
    }

    private static void exposedFace(DoubleArrayList surfaces, AABB box, Direction face,
            List<AABB> own, List<AABB> adjacent) {
        Direction.Axis axis = face.getAxis();
        Direction.Axis u = axis == Direction.Axis.X ? Direction.Axis.Y : Direction.Axis.X;
        Direction.Axis v = axis == Direction.Axis.Z ? Direction.Axis.Y : Direction.Axis.Z;
        boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        double plane = positive ? box.max(axis) : box.min(axis);
        double uMin = box.min(u), uMax = box.max(u), vMin = box.min(v), vMax = box.max(v);
        double area = (uMax - uMin) * (vMax - vMin);
        double firstU = area * (uMin + uMax) * 0.5, firstV = area * (vMin + vMax) * 0.5;
        // Shape boxes have disjoint interiors. Hidden patches on the same plane can be subtracted once each.
        for (int i = 0, count = own.size() + adjacent.size(); i < count; i++) {
            AABB other = i < own.size() ? own.get(i) : adjacent.get(i - own.size());
            if (plane != (positive ? other.min(axis) : other.max(axis))) continue;
            double loU = Math.max(uMin, other.min(u)), hiU = Math.min(uMax, other.max(u));
            double loV = Math.max(vMin, other.min(v)), hiV = Math.min(vMax, other.max(v));
            if (hiU <= loU || hiV <= loV) continue;
            double hidden = (hiU - loU) * (hiV - loV);
            area -= hidden;
            firstU -= hidden * (loU + hiU) * 0.5;
            firstV -= hidden * (loV + hiV) * 0.5;
        }
        if (area <= 1e-8) return;
        double cu = firstU / area, cv = firstV / area;
        Vec3 point = switch (axis) {
            case X -> new Vec3(plane, cu, cv);
            case Y -> new Vec3(cu, plane, cv);
            case Z -> new Vec3(cu, cv, plane);
        };
        surface(surfaces, 0, point, face, area);
    }

    private static void surface(DoubleArrayList data, int kind, Vec3 point, Direction dir, double magnitude) {
        data.add(kind);
        data.add(point.x);
        data.add(point.y);
        data.add(point.z);
        data.add(dir.getStepX());
        data.add(dir.getStepY());
        data.add(dir.getStepZ());
        data.add(magnitude);
    }

    record Pick(CraftBlock block, Direction face, double distance, AABB box) {}

    /** Nearest block shape hit by a ray given in craft-local coordinates, or null. */
    Pick pick(Vec3 origin, Vec3 direction, double reach) {
        return pick(origin, direction, reach, false);
    }

    /** As {@link #pick(Vec3, Vec3, double)}; with {@code fluids}, cells of pure fluid can be hit too, as a bucket does. */
    Pick pick(Vec3 origin, Vec3 direction, double reach, boolean fluids) {
        Pick best = null;
        Vec3 end = origin.add(direction.scale(reach));
        // Solid parts are found through the index; the rest are few.
        for (int index : ((BoxIndex) collisionBoxes).near(new AABB(origin, end).inflate(1.0e-6))) {
            best = closer(best, parts.get(index), origin, end);
        }
        for (Part part : pickParts) best = closer(best, part, origin, end);
        if (fluids) {
            for (Part part : fluidParts) best = closer(best, part, origin, end);
        }
        return best;
    }

    private static Pick closer(Pick best, Part part, Vec3 origin, Vec3 end) {
        Vec3 hit = part.box.clip(origin, end).orElse(null);
        if (hit == null) return best;
        double distance = origin.distanceTo(hit);
        return best == null || distance < best.distance ? new Pick(part.owner, faceAt(part.box, hit), distance, part.box) : best;
    }

    private static Direction faceAt(AABB box, Vec3 hit) {
        double[] gap = {Math.abs(hit.x - box.minX), Math.abs(hit.x - box.maxX), Math.abs(hit.y - box.minY),
                Math.abs(hit.y - box.maxY), Math.abs(hit.z - box.minZ), Math.abs(hit.z - box.maxZ)};
        Direction[] faces = {Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};
        int best = 0;
        for (int i = 1; i < gap.length; i++) if (gap[i] < gap[best]) best = i;
        return faces[best];
    }

    /** Groups of blocks that touch face to face. */
    static List<List<CraftBlock>> components(List<CraftBlock> blocks) {
        Map<BlockPos, CraftBlock> pending = new HashMap<>();
        for (CraftBlock block : blocks) pending.put(block.offset(), block);
        List<List<CraftBlock>> groups = new ArrayList<>();
        for (CraftBlock first : blocks) {
            if (pending.remove(first.offset()) == null) continue;
            List<CraftBlock> group = new ArrayList<>();
            ArrayDeque<CraftBlock> queue = new ArrayDeque<>();
            queue.add(first);
            while (!queue.isEmpty()) {
                CraftBlock block = queue.remove();
                group.add(block);
                for (Direction face : FACES) {
                    CraftBlock next = pending.remove(block.offset().relative(face));
                    if (next != null) queue.add(next);
                }
            }
            groups.add(group);
        }
        return groups;
    }

    static boolean connected(List<CraftBlock> blocks) {
        Set<BlockPos> remaining = new HashSet<>();
        for (CraftBlock block : blocks) {
            remaining.add(block.offset());
        }
        if (remaining.isEmpty() || remaining.size() != blocks.size()) {
            return false;
        }
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        BlockPos first = blocks.getFirst().offset();
        queue.add(first);
        remaining.remove(first);
        while (!queue.isEmpty()) {
            BlockPos p = queue.remove();
            for (Direction face : FACES) {
                BlockPos next = p.relative(face);
                if (remaining.remove(next)) {
                    queue.add(next);
                }
            }
        }
        return remaining.isEmpty();
    }

    static boolean validOffset(BlockPos offset) {
        return PlotGrid.validOffset(offset);
    }

    static Vec3 rotate(Quaternionfc q, Vec3 v) {
        Vec3 axis = new Vec3(q.x(), q.y(), q.z());
        Vec3 t = axis.cross(v).scale(2);
        return v.add(t.scale(q.w())).add(axis.cross(t));
    }
}
