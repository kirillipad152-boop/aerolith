package dev.aerolith;

import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

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
            Map.entry(Blocks.SLIME_BLOCK, 1150.0)
    );
    private static final Set<String> ROCK = Set.of("andesite", "polished_andesite", "diorite", "polished_diorite",
            "granite", "polished_granite", "tuff", "polished_tuff", "blackstone", "polished_blackstone",
            "basalt", "smooth_basalt", "polished_basalt", "sandstone", "cut_sandstone", "smooth_sandstone",
            "red_sandstone", "cut_red_sandstone", "smooth_red_sandstone", "obsidian", "crying_obsidian", "purpur_block");
    private static final Set<String> WOODS = Set.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove",
            "cherry", "crimson", "warped", "bamboo", "bamboo_mosaic");

    private Materials() {}

    static double density(BlockState state) {
        Block block = state.getBlock();
        Double known = DENSITY.get(block);
        if (known != null) return known;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        String path = id.getPath();
        if (id.getNamespace().equals(Aerolith.ID)) {
            return switch (path) {
                default -> 0.0;
            };
        }
        if (!id.getNamespace().equals("minecraft")) return 0.0;
        if (path.endsWith("_wool")) return 55.0;
        if (path.endsWith("_planks") || path.equals("bamboo_mosaic")) return 480.0;
        if (log(path)) return 650.0;
        if (path.endsWith("_stained_glass")) return 2500.0;
        if (path.equals("terracotta") || path.endsWith("_terracotta")) return 1900.0;
        if (path.endsWith("_concrete")) return 2350.0;
        if (copper(path)) return 8960.0;
        if (path.equals("bricks") || path.endsWith("_bricks")) return 1950.0;
        if (ROCK.contains(path) || path.contains("quartz") && path.endsWith("_block")) return 2450.0;
        if (block instanceof SlabBlock || block instanceof StairBlock) {
            return switch (surface(state)) {
                case WOOD -> 480.0;
                case METAL -> 8960.0;
                default -> path.contains("brick") ? 1950.0 : 2350.0;
            };
        }
        return 0.0;
    }

    /** Density for the mass of a solid block: its own if known, otherwise a middling value. */
    static double massDensity(BlockState state) {
        double known = density(state);
        return known > 0.0 ? known : Hull.DEFAULT_DENSITY;
    }

    /** What can be aimed at on a block that has no collision shape. */
    static List<AABB> outline(BlockState state) {
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

    // Detachable blocks use shapes that depend only on their stored block state.
    static List<AABB> boxes(BlockState state) {
        // Slivers thinner than the physics engine's minimum would only be rejected there.
        return state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs().stream()
                .filter(b -> b.getXsize() >= 0.002 && b.getYsize() >= 0.002 && b.getZsize() >= 0.002).toList();
    }

    static Surface surface(BlockState state) {
        if (state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE)
                || state.is(Blocks.FROSTED_ICE)) return Surface.ICE;
        if (state.is(Blocks.SLIME_BLOCK)) return Surface.SLIME;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
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
