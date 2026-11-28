package dev.aerolith;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

final class Materials {
    enum Surface {
        DEFAULT(0.65), WOOD(0.55), STONE(0.70), METAL(0.45), GLASS(0.32), ICE(0.035), WOOL(0.80), SLIME(0.35);

        final double coefficient;
        Surface(double coefficient) { this.coefficient = coefficient; }
    }

    private static final Map<Block, Double> DENSITY = Map.ofEntries(
            Map.entry(Blocks.STONE, 2450.0), Map.entry(Blocks.SMOOTH_STONE, 2450.0),
            Map.entry(Blocks.COBBLESTONE, 2350.0), Map.entry(Blocks.STONE_BRICKS, 2400.0),
            Map.entry(Blocks.DEEPSLATE, 2750.0), Map.entry(Blocks.COBBLED_DEEPSLATE, 2670.0),
            Map.entry(Blocks.DEEPSLATE_BRICKS, 2670.0), Map.entry(Blocks.CLAY, 1700.0),
            Map.entry(Blocks.BRICKS, 1900.0), Map.entry(Blocks.QUARTZ_BLOCK, 2650.0),
            Map.entry(Blocks.GLASS, 2500.0), Map.entry(Blocks.DIRT, 1350.0),
            Map.entry(Blocks.IRON_BLOCK, 7870.0), Map.entry(Blocks.GOLD_BLOCK, 19300.0),
            Map.entry(Blocks.PACKED_ICE, 920.0), Map.entry(Blocks.BLUE_ICE, 925.0), Map.entry(Blocks.ICE, 917.0),
            Map.entry(Blocks.FROSTED_ICE, 917.0), Map.entry(Blocks.SLIME_BLOCK, 1150.0),
            Map.entry(Blocks.GRASS_BLOCK, 1350.0), Map.entry(Blocks.COARSE_DIRT, 1350.0),
            Map.entry(Blocks.PODZOL, 1350.0), Map.entry(Blocks.MYCELIUM, 1350.0), Map.entry(Blocks.FARMLAND, 1350.0),
            Map.entry(Blocks.COAL_BLOCK, 1350.0), Map.entry(Blocks.DIAMOND_BLOCK, 3510.0),
            Map.entry(Blocks.EMERALD_BLOCK, 2720.0), Map.entry(Blocks.LAPIS_BLOCK, 2750.0),
            Map.entry(Blocks.RAW_IRON_BLOCK, 4600.0), Map.entry(Blocks.RAW_COPPER_BLOCK, 4800.0),
            Map.entry(Blocks.RAW_GOLD_BLOCK, 7500.0), Map.entry(Blocks.REDSTONE_BLOCK, 2600.0),
            Map.entry(Blocks.HAY_BLOCK, 160.0), Map.entry(Blocks.DRIED_KELP_BLOCK, 250.0),
            Map.entry(Blocks.CHEST, 200.0), Map.entry(Blocks.TRAPPED_CHEST, 200.0), Map.entry(Blocks.BARREL, 220.0),
            Map.entry(Blocks.GLOWSTONE, 1400.0)
    );
    // The registered sounds group most vanilla variants by material; explicit entries above win when they don't.
    private static final Map<SoundType, Double> MATERIAL_DENSITY = Map.ofEntries(
            Map.entry(SoundType.WOOD, 480.0), Map.entry(SoundType.NETHER_WOOD, 480.0),
            Map.entry(SoundType.CHERRY_WOOD, 480.0), Map.entry(SoundType.BAMBOO_WOOD, 480.0),
            Map.entry(SoundType.SHELF, 480.0), Map.entry(SoundType.LADDER, 480.0),
            Map.entry(SoundType.STONE, 2450.0), Map.entry(SoundType.NETHERRACK, 1750.0),
            Map.entry(SoundType.NETHER_BRICKS, 2000.0), Map.entry(SoundType.CALCITE, 2700.0),
            Map.entry(SoundType.DEEPSLATE, 2750.0), Map.entry(SoundType.POLISHED_DEEPSLATE, 2750.0),
            Map.entry(SoundType.DEEPSLATE_BRICKS, 2670.0), Map.entry(SoundType.DEEPSLATE_TILES, 2670.0),
            Map.entry(SoundType.TUFF, 2450.0), Map.entry(SoundType.TUFF_BRICKS, 2450.0),
            Map.entry(SoundType.POLISHED_TUFF, 2450.0), Map.entry(SoundType.DRIPSTONE_BLOCK, 2500.0),
            Map.entry(SoundType.POINTED_DRIPSTONE, 2500.0), Map.entry(SoundType.AMETHYST, 2650.0),
            Map.entry(SoundType.METAL, 7870.0), Map.entry(SoundType.IRON, 7870.0),
            Map.entry(SoundType.CHAIN, 7870.0), Map.entry(SoundType.ANVIL, 7870.0),
            Map.entry(SoundType.COPPER, 8960.0), Map.entry(SoundType.COPPER_GRATE, 8960.0),
            Map.entry(SoundType.COPPER_BULB, 4800.0), Map.entry(SoundType.NETHERITE_BLOCK, 18000.0),
            Map.entry(SoundType.GLASS, 2500.0), Map.entry(SoundType.WOOL, 55.0),
            Map.entry(SoundType.SAND, 1600.0), Map.entry(SoundType.SUSPICIOUS_SAND, 1600.0),
            Map.entry(SoundType.GRAVEL, 1700.0), Map.entry(SoundType.SUSPICIOUS_GRAVEL, 1700.0),
            Map.entry(SoundType.SNOW, 300.0), Map.entry(SoundType.ROOTED_DIRT, 1350.0),
            Map.entry(SoundType.MUD, 1500.0), Map.entry(SoundType.PACKED_MUD, 1800.0), Map.entry(SoundType.MUD_BRICKS, 1850.0),
            Map.entry(SoundType.SOUL_SAND, 1400.0), Map.entry(SoundType.SOUL_SOIL, 1350.0),
            Map.entry(SoundType.MOSS, 180.0), Map.entry(SoundType.MOSS_CARPET, 180.0),
            Map.entry(SoundType.SPONGE, 85.0), Map.entry(SoundType.WET_SPONGE, 950.0),
            Map.entry(SoundType.HONEY_BLOCK, 1420.0), Map.entry(SoundType.BONE_BLOCK, 1900.0),
            Map.entry(SoundType.RESIN, 1100.0), Map.entry(SoundType.RESIN_BRICKS, 1450.0),
            Map.entry(SoundType.CORAL_BLOCK, 1400.0), Map.entry(SoundType.DECORATED_POT, 900.0),
            Map.entry(SoundType.CANDLE, 900.0), Map.entry(SoundType.STRAW_BED, 180.0),
            Map.entry(SoundType.WART_BLOCK, 450.0), Map.entry(SoundType.SCULK, 350.0),
            Map.entry(SoundType.FROGLIGHT, 700.0), Map.entry(SoundType.SHROOMLIGHT, 550.0),
            Map.entry(SoundType.SULFUR, 2000.0), Map.entry(SoundType.POTENT_SULFUR, 2100.0),
            Map.entry(SoundType.SULFUR_SPIKE, 2000.0), Map.entry(SoundType.CINNABAR, 6000.0)
    );
    private static final Set<String> ROCK = Set.of("andesite", "polished_andesite", "diorite", "polished_diorite",
            "granite", "polished_granite", "tuff", "polished_tuff", "blackstone", "polished_blackstone",
            "basalt", "smooth_basalt", "polished_basalt", "sandstone", "cut_sandstone", "smooth_sandstone",
            "red_sandstone", "cut_red_sandstone", "smooth_red_sandstone", "obsidian", "crying_obsidian", "purpur_block");
    private static final Set<String> WOODS = Set.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove",
            "cherry", "crimson", "warped", "bamboo", "bamboo_mosaic");

    // The stock models have real posts and two rails; vanilla movement adds an invisible barrier over them.
    private static final VoxelShape FENCE_POST = Block.column(4, 0, 16);
    private static final VoxelShape FENCE_RAIL_HEIGHTS = Shapes.or(Block.box(0, 6, 0, 16, 9, 16), Block.box(0, 12, 0, 16, 15, 16));
    private static final VoxelShape GATE_POSTS = Shapes.or(Block.box(0, 5, 7, 2, 16, 9), Block.box(14, 5, 7, 16, 16, 9));
    private static final Map<Direction, VoxelShape> GATE_CLOSED = Shapes.rotateHorizontal(Shapes.or(GATE_POSTS,
            Block.box(6, 6, 7, 10, 15, 9), Block.box(2, 6, 7, 14, 9, 9), Block.box(2, 12, 7, 14, 15, 9)));
    private static final Map<Direction, VoxelShape> GATE_OPEN = Shapes.rotateHorizontal(Shapes.or(GATE_POSTS,
            Block.box(0, 6, 13, 2, 15, 15), Block.box(14, 6, 13, 16, 15, 15),
            Block.box(0, 6, 9, 2, 9, 13), Block.box(14, 6, 9, 16, 9, 13),
            Block.box(0, 12, 9, 2, 15, 13), Block.box(14, 12, 9, 16, 15, 13)));

    private Materials() {}

    @SuppressWarnings("deprecation") // Use the registered material sound, not a contextual sound effect.
    static double density(BlockState state) {
        Block block = state.getBlock();
        Double known = DENSITY.get(block);
        if (known != null) return known;
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        String path = id.getPath();
        if (!id.getNamespace().equals("minecraft")) return 0.0;
        if (path.endsWith("_wool")) return 55.0;
        if (path.endsWith("_planks") || path.equals("bamboo_mosaic")) return 480.0;
        if (log(path)) return 650.0;
        if (path.endsWith("_leaves")) return 150.0;
        if (path.endsWith("_ore")) return path.startsWith("deepslate_") ? 2850.0 : 2600.0;
        if (path.equals("terracotta") || path.endsWith("_terracotta")) return 1900.0;
        if (path.endsWith("_concrete")) return 2350.0;
        if (path.endsWith("_concrete_powder")) return 1650.0;
        if (copper(path)) return 8960.0;
        if (path.startsWith("quartz_") || path.startsWith("smooth_quartz") || path.equals("chiseled_quartz_block")) return 2650.0;
        return MATERIAL_DENSITY.getOrDefault(state.getSoundType(), 0.0);
    }

    /** Density for the mass of a solid block: its own if known, otherwise a middling value. */
    /** Cached by state: a craft has a handful of different states and thousands of blocks. */
    static double massDensity(BlockState state) {
        return signature(state).density();
    }

    private static double computeMassDensity(BlockState state) {
        double known = density(state);
        return known > 0.0 ? known : Hull.DEFAULT_DENSITY;
    }

    /** What can be aimed at on a block that has no collision shape. */
    static List<AABB> outline(BlockState state) {
        Signature known = signature(state);
        return known.boxes().isEmpty() ? known.outline() : computeOutline(state);
    }

    private static List<AABB> computeOutline(BlockState state) {
        return state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs();
    }

    /** Whether the old fixed-shape rules accept {@code state}; plot-backed crafts accept every block. */
    static boolean supported(BlockState state) {
        if (state.hasBlockEntity() || !state.getFluidState().isEmpty() || density(state) <= 0.0) return false;
        List<AABB> boxes = boxes(state);
        return !boxes.isEmpty() && boxes.size() <= Hull.MAX_SHAPES_PER_BLOCK && boxes.stream().allMatch(b ->
                b.minX >= 0 && b.minY >= 0 && b.minZ >= 0 && b.maxX <= 1 && b.maxY <= 1 && b.maxZ <= 1
                        && b.getXsize() >= 0.002 && b.getYsize() >= 0.002 && b.getZsize() >= 0.002);
    }

    /** Everything a hull takes from a block: two states with the same signature give the same hull. */
    private record Signature(Block block, double density, List<AABB> boxes, List<AABB> entityBoxes, List<AABB> outline, boolean fullBlock) {}

    private static final Map<BlockState, Signature> SIGNATURES = new ConcurrentHashMap<>();

    private static Signature signature(BlockState state) {
        return SIGNATURES.computeIfAbsent(state, s -> {
            VoxelShape entity = s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            VoxelShape physical = physicalShape(s, entity);
            List<AABB> boxes = computeBoxes(physical);
            boolean full = boxes.size() == 1 && boxes.getFirst().getXsize() >= 1.0 - 1.0e-6
                    && boxes.getFirst().getYsize() >= 1.0 - 1.0e-6 && boxes.getFirst().getZsize() >= 1.0 - 1.0e-6;
            return new Signature(s.getBlock(), computeMassDensity(s), boxes, physical == entity ? boxes : computeBoxes(entity),
                    boxes.isEmpty() ? List.copyOf(computeOutline(s)) : List.of(), full);
        });
    }

    static boolean isFullBlock(BlockState state) {
        return signature(state).fullBlock();
    }

    /**
     * Whether a change from {@code a} to {@code b} leaves the hull as it was: a lever thrown, a lamp lit, redstone dust
     * that gains or loses power. Its mass, colliders, friction and the shape one aims at are the same.
     */
    static boolean samePhysics(BlockState a, BlockState b) {
        return a == b || signature(a).equals(signature(b));
    }

    /** Whether the block has a physical shape for the craft solver. */
    static boolean hasCollision(BlockState state) {
        return !signature(state).boxes().isEmpty();
    }

    /** Rigid contacts, mass and aiming; excludes entity-only barriers. */
    static List<AABB> boxes(BlockState state) {
        return signature(state).boxes();
    }

    static List<AABB> entityBoxes(BlockState state) {
        return signature(state).entityBoxes();
    }

    static boolean fenceLike(BlockState state) {
        Class<?> type = state.getBlock().getClass();
        return type == FenceBlock.class || type == FenceGateBlock.class || type == WallBlock.class;
    }

    private static VoxelShape physicalShape(BlockState state, VoxelShape entity) {
        if (!fenceLike(state)) return entity;
        return switch (state.getBlock()) {
            case FenceBlock ignored -> Shapes.or(FENCE_POST, Shapes.join(state.getOcclusionShape(), FENCE_RAIL_HEIGHTS, BooleanOp.AND));
            case WallBlock ignored -> state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            case FenceGateBlock ignored -> {
                var shapes = state.getValue(FenceGateBlock.OPEN) ? GATE_OPEN : GATE_CLOSED;
                VoxelShape shape = shapes.get(state.getValue(FenceGateBlock.FACING).getOpposite()); // The stock model faces south.
                yield state.getValue(FenceGateBlock.IN_WALL) ? shape.move(0, -3.0 / 16, 0) : shape;
            }
            default -> entity;
        };
    }

    private static List<AABB> computeBoxes(VoxelShape shape) {
        // Slivers thinner than the physics engine's minimum would only be rejected there.
        return shape.toAabbs().stream()
                .filter(b -> b.getXsize() >= 0.002 && b.getYsize() >= 0.002 && b.getZsize() >= 0.002).toList();
    }

    static Surface surface(BlockState state) {
        if (state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE)
                || state.is(Blocks.FROSTED_ICE)) return Surface.ICE;
        if (state.is(Blocks.SLIME_BLOCK)) return Surface.SLIME;
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id.getNamespace().equals(Aerolith.ID)) {
            return switch (id.getPath()) {
                default -> Surface.DEFAULT;
            };
        }
        if (!id.getNamespace().equals("minecraft")) return Surface.DEFAULT;
        String path = id.getPath();
        if (path.endsWith("_wool") || path.endsWith("_carpet")) return Surface.WOOL;
        if (path.equals("glass") || path.endsWith("_glass") || path.endsWith("glass_pane")) return Surface.GLASS;
        if (log(path) || path.endsWith("_planks") || path.equals("bamboo_mosaic")) return Surface.WOOD;
        if (copper(path) || path.equals("iron_block") || path.equals("gold_block") || path.equals("netherite_block")) {
            return Surface.METAL;
        }
        if (state.getBlock() instanceof SlabBlock || state.getBlock() instanceof StairBlock) {
            String base = path.substring(0, path.lastIndexOf('_'));
            return WOODS.contains(base) ? Surface.WOOD : Surface.STONE;
        }
        return ROCK.contains(path) || path.contains("stone") || path.contains("deepslate") || path.contains("brick")
                || path.contains("quartz") || path.endsWith("_concrete") || path.contains("terracotta")
                ? Surface.STONE : Surface.DEFAULT;
    }

    private static boolean log(String path) {
        return path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_stem")
                || path.endsWith("_hyphae") || path.endsWith("bamboo_block");
    }

    private static boolean copper(String path) {
        String unwaxed = path.startsWith("waxed_") ? path.substring(6) : path;
        return unwaxed.equals("copper_block") || unwaxed.endsWith("_copper") || unwaxed.endsWith("_copper_slab")
                || unwaxed.endsWith("_copper_stairs");
    }
}
