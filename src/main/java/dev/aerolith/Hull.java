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
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
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
    private static final AABB[] NO_BOXES = {};
    private static final double[] NO_DOUBLES = {};

    volatile List<CraftBlock> blocks;
    final Vec3 localCom;
    final double radius;
    final double mass;
    final double cavityVolume;
    final AABB localBounds;
    final List<AABB> collisionBoxes;
    private final List<Part> parts;
    final double[] surfaces;
    final double[] cavities;

    private record Part(CraftBlock owner, AABB box, double density) {}

    /** Shapes that can be aimed at but are not solid: torches, levers, signs, and fluids. */
    private volatile List<Part> pickParts;
    private final List<Part> fluidParts;

    Hull(List<CraftBlock> blocks) {
        this(blocks, List.of());
    }

    /** {@code fluids} are cells that hold only fluid: no collider and no mass, but a bucket can pick them up. */
    Hull(List<CraftBlock> blocks, List<CraftBlock> fluids) {
        this.blocks = List.copyOf(blocks);
        boolean singleBlock = blocks.size() == 1;
        var cells = singleBlock ? null : new Long2ObjectOpenHashMap<AABB[]>(blocks.size());
        var fullBlocks = singleBlock ? null : new it.unimi.dsi.fastutil.longs.LongOpenHashSet(blocks.size());
        int sealCount = 0;
        int sealMinX = Integer.MAX_VALUE, sealMinY = Integer.MAX_VALUE, sealMinZ = Integer.MAX_VALUE;
        int sealMaxX = Integer.MIN_VALUE, sealMaxY = Integer.MIN_VALUE, sealMaxZ = Integer.MIN_VALUE;
        List<Part> parts = new ArrayList<>(blocks.size());
        List<Part> pickParts = new ArrayList<>();
        for (CraftBlock block : blocks) {
            BlockPos p = block.offset();
            double x = p.getX() - 0.5, y = p.getY() - 0.5, z = p.getZ() - 0.5;
            List<AABB> shapes = Materials.boxes(block.state());
            if (shapes.isEmpty()) {
                for (AABB b : Materials.outline(block.state())) pickParts.add(new Part(block, b.move(x, y, z), 0.0));
                continue;
            }
            AABB[] boxes = new AABB[shapes.size()];
            double density = Materials.massDensity(block.state());
            for (int i = 0; i < boxes.length; i++) {
                boxes[i] = shapes.get(i).move(x, y, z);
                parts.add(new Part(block, boxes[i], density));
            }
            if (!singleBlock) {
                long key = p.asLong();
                cells.put(key, boxes);
                if (Materials.isFullBlock(block.state())) fullBlocks.add(key);
                if (Materials.sealsCavity(block.state())) {
                    sealCount++;
                    sealMinX = Math.min(sealMinX, p.getX());
                    sealMinY = Math.min(sealMinY, p.getY());
                    sealMinZ = Math.min(sealMinZ, p.getZ());
                    sealMaxX = Math.max(sealMaxX, p.getX());
                    sealMaxY = Math.max(sealMaxY, p.getY());
                    sealMaxZ = Math.max(sealMaxZ, p.getZ());
                }
            }
        }
        List<Part> fluidParts = fluids.isEmpty() ? List.of() : new ArrayList<>(fluids.size());
        for (CraftBlock fluid : fluids) {
            Vec3 offset = Vec3.atLowerCornerOf(fluid.offset()).subtract(0.5, 0.5, 0.5);
            fluidParts.add(new Part(fluid, new AABB(0, 0, 0, 1, 0.9, 1).move(offset), 0.0));
        }
        this.pickParts = List.copyOf(pickParts);
        this.fluidParts = List.copyOf(fluidParts);
        this.parts = List.copyOf(parts);
        List<AABB> cBoxes = new ArrayList<>(parts.size());
        for (Part part : parts) cBoxes.add(part.box);
        collisionBoxes = new BoxIndex(cBoxes);
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

        if (singleBlock) {
            CraftBlock only = blocks.getFirst();
            AABB[] own = parts.isEmpty() ? NO_BOXES : new AABB[parts.size()];
            for (int i = 0; i < parts.size(); i++) own[i] = parts.get(i).box;
            emitBlockSurfaces(surfaces, only, own, null, null);
        } else {
            for (CraftBlock block : blocks) {
                BlockPos p = block.offset();
                long key = p.asLong();
                AABB[] own = cells.get(key);
                Materials.AeroProperty aero = Materials.aero(block.state());
                if (own == null && aero == null) continue;
                boolean full = own != null && fullBlocks.contains(key);
                if (aero == null && full
                        && fullBlocks.contains(BlockPos.offset(key, 1, 0, 0))
                        && fullBlocks.contains(BlockPos.offset(key, -1, 0, 0))
                        && fullBlocks.contains(BlockPos.offset(key, 0, 1, 0))
                        && fullBlocks.contains(BlockPos.offset(key, 0, -1, 0))
                        && fullBlocks.contains(BlockPos.offset(key, 0, 0, 1))
                        && fullBlocks.contains(BlockPos.offset(key, 0, 0, -1))) {
                    // Completely surrounded full block has no exposed exterior faces.
                    continue;
                }
                emitBlockSurfaces(surfaces, block, own == null ? NO_BOXES : own, full ? fullBlocks : null, cells);
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
        this.localBounds = bounds;
        this.surfaces = surfaces.toDoubleArray();
        if (!singleBlock && sealCount >= 6
                && (sealMaxX - sealMinX >= 2)
                && (sealMaxY - sealMinY >= 2)
                && (sealMaxZ - sealMinZ >= 2)) {
            SealedCavities.Result result = SealedCavities.find(
                    blocks, fluids, cells, sealMinX, sealMinY, sealMinZ, sealMaxX, sealMaxY, sealMaxZ);
            this.cavities = result.boxes();
            this.cavityVolume = result.volume();
        } else {
            this.cavities = NO_DOUBLES;
            this.cavityVolume = 0.0;
        }
    }

    private static void emitBlockSurfaces(
            DoubleArrayList surfaces,
            CraftBlock block,
            AABB[] own,
            it.unimi.dsi.fastutil.longs.LongOpenHashSet fullBlocks,
            Long2ObjectOpenHashMap<AABB[]> cells
    ) {
        BlockPos p = block.offset();
        Materials.AeroProperty aero = Materials.aero(block.state());
        Direction skipFoilAxis = null;
        if (aero != null && aero.surfaceKind() != 0) {
            Vec3 center = Vec3.atLowerCornerOf(p);
            if (aero.surfaceKind() == 2) {
                surface(surfaces, 2, center, aero.direction(), aero.magnitude());
            } else {
                double areaFactor = aero.perArea() ? exposedPlanformArea(own, aero.direction(), p, cells) : 1.0;
                if (areaFactor > 1.0e-8) {
                    surface(surfaces, aero.surfaceKind(), center, aero.direction(), aero.magnitude() * areaFactor);
                }
                skipFoilAxis = Direction.getApproximateNearest(aero.direction().x, aero.direction().y, aero.direction().z);
            }
        }
        for (AABB box : own) {
            for (Direction face : FACES) {
                if (skipFoilAxis != null && face.getAxis() == skipFoilAxis.getAxis()) continue;
                long nKey = neighborKey(p, face);
                if (fullBlocks != null && fullBlocks.contains(nKey)) continue;
                AABB[] adjacent = cells == null ? NO_BOXES : cells.getOrDefault(nKey, NO_BOXES);
                exposedFace(surfaces, box, face, own, adjacent);
            }
        }
    }

    private static double exposedPlanformArea(
            AABB[] own, Vec3 dir, BlockPos p, Long2ObjectOpenHashMap<AABB[]> cells
    ) {
        if (own.length == 0) return 1.0;
        Direction face = Direction.getApproximateNearest(dir.x, dir.y, dir.z);
        Direction.Axis axis = face.getAxis();
        Direction.Axis u = axis == Direction.Axis.X ? Direction.Axis.Y : Direction.Axis.X;
        Direction.Axis v = axis == Direction.Axis.Z ? Direction.Axis.Y : Direction.Axis.Z;
        double area = 0.0;
        for (AABB box : own) area += (box.max(u) - box.min(u)) * (box.max(v) - box.min(v));
        if (cells == null) return area;
        AABB[] above = cells.get(neighborKey(p, face));
        AABB[] below = cells.get(neighborKey(p, face.getOpposite()));
        return (above != null && above.length > 0 && below != null && below.length > 0) ? 0.0 : area;
    }

    private static void surface(DoubleArrayList data, int kind, Vec3 point, Vec3 dir, double magnitude) {
        data.add(kind);
        data.add(point.x);
        data.add(point.y);
        data.add(point.z);
        data.add(dir.x);
        data.add(dir.y);
        data.add(dir.z);
        data.add(magnitude);
    }

    /**
     * Takes in blocks that have no collision shape (a placed lever, a wire, a torch): they have no mass and touch nothing, so
     * all that changes is what can be aimed at. Nothing the physics engine holds is rebuilt, which on a large craft is the
     * difference between a few microseconds and many milliseconds for every block placed.
     */
    void updateWithoutCollision(Map<BlockPos, BlockState> changes) {
        List<CraftBlock> nextBlocks = new ArrayList<>(blocks.size() + changes.size());
        for (CraftBlock block : blocks) {
            if (!changes.containsKey(block.offset())) nextBlocks.add(block);
        }
        List<Part> nextPicks = new ArrayList<>(pickParts.size() + changes.size());
        for (Part part : pickParts) {
            if (!changes.containsKey(part.owner.offset())) nextPicks.add(part);
        }
        for (Map.Entry<BlockPos, BlockState> change : changes.entrySet()) {
            CraftBlock block = new CraftBlock(change.getKey(), change.getValue());
            nextBlocks.add(block);
            Vec3 offset = Vec3.atLowerCornerOf(block.offset()).subtract(0.5, 0.5, 0.5);
            for (AABB b : Materials.outline(block.state())) nextPicks.add(new Part(block, b.move(offset), 0.0));
        }
        pickParts = List.copyOf(nextPicks);
        blocks = List.copyOf(nextBlocks);
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
        double[] out = new double[parts.size() * 7];
        int i = 0;
        for (Part part : parts) {
            AABB b = part.box;
            Vec3 center = b.getCenter();
            out[i++] = center.x;
            out[i++] = center.y;
            out[i++] = center.z;
            out[i++] = b.getXsize() * 0.5;
            out[i++] = b.getYsize() * 0.5;
            out[i++] = b.getZsize() * 0.5;
            out[i++] = part.density;
        }
        return out;
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
            AABB[] own, AABB[] adjacent) {
        Direction.Axis axis = face.getAxis();
        Direction.Axis u = axis == Direction.Axis.X ? Direction.Axis.Y : Direction.Axis.X;
        Direction.Axis v = axis == Direction.Axis.Z ? Direction.Axis.Y : Direction.Axis.Z;
        boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        double plane = positive ? box.max(axis) : box.min(axis);
        double uMin = box.min(u), uMax = box.max(u), vMin = box.min(v), vMax = box.max(v);
        double area = (uMax - uMin) * (vMax - vMin);
        double firstU = area * (uMin + uMax) * 0.5, firstV = area * (vMin + vMax) * 0.5;
        // Shape boxes have disjoint interiors. Hidden patches on the same plane can be subtracted once each.
        for (int i = 0, count = own.length + adjacent.length; i < count; i++) {
            AABB other = i < own.length ? own[i] : adjacent[i - own.length];
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
        var pending = new Long2ObjectOpenHashMap<CraftBlock>(blocks.size());
        for (CraftBlock block : blocks) pending.put(block.offset().asLong(), block);
        List<List<CraftBlock>> groups = new ArrayList<>();
        for (CraftBlock first : blocks) {
            if (pending.remove(first.offset().asLong()) == null) continue;
            List<CraftBlock> group = new ArrayList<>();
            group.add(first);
            for (int i = 0; i < group.size(); i++) {
                BlockPos offset = group.get(i).offset();
                for (Direction face : FACES) {
                    CraftBlock next = pending.remove(neighborKey(offset, face));
                    if (next != null) group.add(next);
                }
            }
            groups.add(group);
        }
        return groups;
    }

    private static long neighborKey(BlockPos p, Direction face) {
        return BlockPos.asLong(p.getX() + face.getStepX(), p.getY() + face.getStepY(), p.getZ() + face.getStepZ());
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
