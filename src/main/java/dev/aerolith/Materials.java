package dev.aerolith;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class Materials {
    enum Surface {
        DEFAULT(0.65), WOOD(0.55), STONE(0.70), METAL(0.45), GLASS(0.32), ICE(0.035), WOOL(0.80), SLIME(0.35);

        final double coefficient;
        Surface(double coefficient) { this.coefficient = coefficient; }
    }

    /** Aerodynamic or thruster surface behavior attached to a block via the API. */
    record AeroProperty(int surfaceKind, Vec3 direction, double magnitude, boolean perArea) {}

    /** Developer-registered per-block physical overrides. */
    record CustomProperties(
            @Nullable Double density,
            @Nullable Surface surface,
            @Nullable Double friction,
            @Nullable Double restitution,
            @Nullable Boolean sealsCavity,
            @Nullable AeroProperty aero
    ) {}

    private static final String[] WOOD_ORDER = {
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak",
            "mangrove", "cherry", "crimson", "warped", "bamboo", "pale_oak"
    };
    private static final Map<String, Double> WOOD_PLANK_DENSITY = Map.ofEntries(
            // Oak keeps 480 kg/m³ as the seasoned structural timber baseline used by existing flotation tests.
            Map.entry("oak", 480.0),
            Map.entry("spruce", 450.0),
            Map.entry("bamboo", 380.0),
            Map.entry("cherry", 560.0),
            Map.entry("crimson", 600.0),
            Map.entry("warped", 600.0),
            Map.entry("pale_oak", 640.0),
            Map.entry("birch", 650.0),
            Map.entry("acacia", 690.0),
            Map.entry("jungle", 710.0),
            Map.entry("dark_oak", 760.0),
            Map.entry("mangrove", 840.0)
    );
    private static final Map<String, Double> WOOD_LOG_DENSITY = Map.ofEntries(
            Map.entry("oak", 650.0),
            Map.entry("spruce", 540.0),
            Map.entry("bamboo", 420.0),
            Map.entry("cherry", 630.0),
            Map.entry("crimson", 680.0),
            Map.entry("warped", 680.0),
            Map.entry("pale_oak", 720.0),
            Map.entry("birch", 710.0),
            Map.entry("acacia", 760.0),
            Map.entry("jungle", 780.0),
            Map.entry("dark_oak", 830.0),
            Map.entry("mangrove", 910.0)
    );

    /** Explicit vanilla block densities (kg/m³), independent of state properties. */
    private static final Map<Block, Double> DENSITY = Map.ofEntries(
            // Sedimentary, metamorphic & igneous rock
            Map.entry(Blocks.STONE, 2450.0),
            Map.entry(Blocks.SMOOTH_STONE, 2450.0),
            Map.entry(Blocks.COBBLESTONE, 2350.0),
            Map.entry(Blocks.MOSSY_COBBLESTONE, 2350.0),
            Map.entry(Blocks.STONE_BRICKS, 2400.0),
            Map.entry(Blocks.MOSSY_STONE_BRICKS, 2400.0),
            Map.entry(Blocks.CRACKED_STONE_BRICKS, 2350.0),
            Map.entry(Blocks.CHISELED_STONE_BRICKS, 2400.0),
            Map.entry(Blocks.DEEPSLATE, 2750.0),
            Map.entry(Blocks.COBBLED_DEEPSLATE, 2670.0),
            Map.entry(Blocks.POLISHED_DEEPSLATE, 2750.0),
            Map.entry(Blocks.DEEPSLATE_BRICKS, 2670.0),
            Map.entry(Blocks.CRACKED_DEEPSLATE_BRICKS, 2630.0),
            Map.entry(Blocks.DEEPSLATE_TILES, 2700.0),
            Map.entry(Blocks.CRACKED_DEEPSLATE_TILES, 2650.0),
            Map.entry(Blocks.CHISELED_DEEPSLATE, 2750.0),
            Map.entry(Blocks.BASALT, 2900.0),
            Map.entry(Blocks.SMOOTH_BASALT, 2900.0),
            Map.entry(Blocks.POLISHED_BASALT, 2900.0),
            Map.entry(Blocks.BLACKSTONE, 2850.0),
            Map.entry(Blocks.POLISHED_BLACKSTONE, 2850.0),
            Map.entry(Blocks.GILDED_BLACKSTONE, 3300.0),
            Map.entry(Blocks.POLISHED_BLACKSTONE_BRICKS, 2820.0),
            Map.entry(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS, 2780.0),
            Map.entry(Blocks.CHISELED_POLISHED_BLACKSTONE, 2850.0),
            Map.entry(Blocks.GRANITE, 2700.0),
            Map.entry(Blocks.POLISHED_GRANITE, 2700.0),
            Map.entry(Blocks.DIORITE, 2850.0),
            Map.entry(Blocks.POLISHED_DIORITE, 2850.0),
            Map.entry(Blocks.ANDESITE, 2650.0),
            Map.entry(Blocks.POLISHED_ANDESITE, 2650.0),
            Map.entry(Blocks.CALCITE, 2710.0),
            Map.entry(Blocks.DRIPSTONE_BLOCK, 2550.0),
            Map.entry(Blocks.TUFF, 1850.0),
            Map.entry(Blocks.POLISHED_TUFF, 1900.0),
            Map.entry(Blocks.TUFF_BRICKS, 1900.0),
            Map.entry(Blocks.CHISELED_TUFF, 1850.0),
            Map.entry(Blocks.CHISELED_TUFF_BRICKS, 1900.0),
            Map.entry(Blocks.OBSIDIAN, 2550.0),
            Map.entry(Blocks.CRYING_OBSIDIAN, 2550.0),
            Map.entry(Blocks.BEDROCK, 3200.0),
            Map.entry(Blocks.END_STONE, 2550.0),
            Map.entry(Blocks.END_STONE_BRICKS, 2550.0),
            Map.entry(Blocks.PRISMARINE, 2550.0),
            Map.entry(Blocks.PRISMARINE_BRICKS, 2650.0),
            Map.entry(Blocks.DARK_PRISMARINE, 2700.0),
            Map.entry(Blocks.NETHERRACK, 2100.0),
            Map.entry(Blocks.MAGMA_BLOCK, 2650.0),
            Map.entry(Blocks.NETHER_BRICKS, 2150.0),
            Map.entry(Blocks.CRACKED_NETHER_BRICKS, 2100.0),
            Map.entry(Blocks.CHISELED_NETHER_BRICKS, 2150.0),
            Map.entry(Blocks.RED_NETHER_BRICKS, 2200.0),
            Map.entry(Blocks.PURPUR_BLOCK, 2200.0),
            Map.entry(Blocks.PURPUR_PILLAR, 2200.0),
            Map.entry(Blocks.QUARTZ_BLOCK, 2650.0),
            Map.entry(Blocks.CHISELED_QUARTZ_BLOCK, 2650.0),
            Map.entry(Blocks.QUARTZ_PILLAR, 2650.0),
            Map.entry(Blocks.QUARTZ_BRICKS, 2600.0),
            Map.entry(Blocks.SMOOTH_QUARTZ, 2650.0),
            Map.entry(Blocks.SANDSTONE, 2250.0),
            Map.entry(Blocks.CUT_SANDSTONE, 2250.0),
            Map.entry(Blocks.SMOOTH_SANDSTONE, 2250.0),
            Map.entry(Blocks.CHISELED_SANDSTONE, 2250.0),
            Map.entry(Blocks.RED_SANDSTONE, 2250.0),
            Map.entry(Blocks.CUT_RED_SANDSTONE, 2250.0),
            Map.entry(Blocks.SMOOTH_RED_SANDSTONE, 2250.0),
            Map.entry(Blocks.CHISELED_RED_SANDSTONE, 2250.0),
            Map.entry(Blocks.RESIN_BLOCK, 1150.0),
            Map.entry(Blocks.RESIN_BRICKS, 1600.0),
            Map.entry(Blocks.CHISELED_RESIN_BRICKS, 1600.0),

            // Ceramics, concrete, soils & loose particulates
            Map.entry(Blocks.CLAY, 1700.0),
            Map.entry(Blocks.BRICKS, 1900.0),
            Map.entry(Blocks.MUD, 1600.0),
            Map.entry(Blocks.PACKED_MUD, 1550.0),
            Map.entry(Blocks.MUD_BRICKS, 1650.0),
            Map.entry(Blocks.DIRT, 1350.0),
            Map.entry(Blocks.COARSE_DIRT, 1400.0),
            Map.entry(Blocks.ROOTED_DIRT, 1320.0),
            Map.entry(Blocks.GRASS_BLOCK, 1350.0),
            Map.entry(Blocks.PODZOL, 1300.0),
            Map.entry(Blocks.MYCELIUM, 1300.0),
            Map.entry(Blocks.FARMLAND, 1300.0),
            Map.entry(Blocks.DIRT_PATH, 1450.0),
            Map.entry(Blocks.SAND, 1600.0),
            Map.entry(Blocks.RED_SAND, 1620.0),
            Map.entry(Blocks.GRAVEL, 1750.0),
            Map.entry(Blocks.SOUL_SAND, 1450.0),
            Map.entry(Blocks.SOUL_SOIL, 1450.0),

            // Metals, ores, gems & heavy machinery
            Map.entry(Blocks.IRON_BLOCK, 7870.0),
            Map.entry(Blocks.RAW_IRON_BLOCK, 5400.0),
            Map.entry(Blocks.IRON_BARS, 7870.0),
            Map.entry(Blocks.IRON_DOOR, 7870.0),
            Map.entry(Blocks.IRON_TRAPDOOR, 7870.0),
            Map.entry(Blocks.ANVIL, 7200.0),
            Map.entry(Blocks.CHIPPED_ANVIL, 7200.0),
            Map.entry(Blocks.DAMAGED_ANVIL, 7200.0),
            Map.entry(Blocks.CAULDRON, 7200.0),
            Map.entry(Blocks.HOPPER, 7200.0),
            Map.entry(Blocks.GOLD_BLOCK, 19300.0),
            Map.entry(Blocks.RAW_GOLD_BLOCK, 12800.0),
            Map.entry(Blocks.NETHERITE_BLOCK, 22400.0),
            Map.entry(Blocks.ANCIENT_DEBRIS, 16500.0),
            Map.entry(Blocks.HEAVY_CORE, 18000.0),
            Map.entry(Blocks.LODESTONE, 5200.0),
            Map.entry(Blocks.RAW_COPPER_BLOCK, 6200.0),
            Map.entry(Blocks.DIAMOND_BLOCK, 3510.0),
            Map.entry(Blocks.EMERALD_BLOCK, 2720.0),
            Map.entry(Blocks.LAPIS_BLOCK, 2800.0),
            Map.entry(Blocks.REDSTONE_BLOCK, 2700.0),
            Map.entry(Blocks.AMETHYST_BLOCK, 2650.0),
            Map.entry(Blocks.BUDDING_AMETHYST, 2650.0),
            Map.entry(Blocks.COAL_BLOCK, 1350.0),

            // Glass, ice, organics, technical & utility blocks
            Map.entry(Blocks.GLASS, 2500.0),
            Map.entry(Blocks.GLASS_PANE, 2500.0),
            Map.entry(Blocks.TINTED_GLASS, 2500.0),
            Map.entry(Blocks.ICE, 917.0),
            Map.entry(Blocks.FROSTED_ICE, 917.0),
            Map.entry(Blocks.PACKED_ICE, 920.0),
            Map.entry(Blocks.BLUE_ICE, 925.0),
            Map.entry(Blocks.SNOW_BLOCK, 450.0),
            Map.entry(Blocks.SNOW, 450.0),
            Map.entry(Blocks.SLIME_BLOCK, 1150.0),
            Map.entry(Blocks.HONEY_BLOCK, 1420.0),
            Map.entry(Blocks.HONEYCOMB_BLOCK, 450.0),
            Map.entry(Blocks.SPONGE, 120.0),
            Map.entry(Blocks.WET_SPONGE, 1050.0),
            Map.entry(Blocks.HAY_BLOCK, 160.0),
            Map.entry(Blocks.DRIED_KELP_BLOCK, 280.0),
            Map.entry(Blocks.TARGET, 220.0),
            Map.entry(Blocks.MOSS_BLOCK, 260.0),
            Map.entry(Blocks.BONE_BLOCK, 1900.0),
            Map.entry(Blocks.MELON, 680.0),
            Map.entry(Blocks.PUMPKIN, 680.0),
            Map.entry(Blocks.CARVED_PUMPKIN, 620.0),
            Map.entry(Blocks.JACK_O_LANTERN, 640.0),
            Map.entry(Blocks.TNT, 1600.0),
            Map.entry(Blocks.BOOKSHELF, 560.0),
            Map.entry(Blocks.CHISELED_BOOKSHELF, 560.0),
            Map.entry(Blocks.CRAFTING_TABLE, 520.0),
            Map.entry(Blocks.LOOM, 520.0),
            Map.entry(Blocks.CARTOGRAPHY_TABLE, 520.0),
            Map.entry(Blocks.FLETCHING_TABLE, 520.0),
            Map.entry(Blocks.BARREL, 540.0),
            Map.entry(Blocks.CHEST, 540.0),
            Map.entry(Blocks.TRAPPED_CHEST, 540.0),
            Map.entry(Blocks.LECTERN, 540.0),
            Map.entry(Blocks.COMPOSTER, 500.0),
            Map.entry(Blocks.ENDER_CHEST, 2550.0),
            Map.entry(Blocks.FURNACE, 2350.0),
            Map.entry(Blocks.SMOKER, 2100.0),
            Map.entry(Blocks.BLAST_FURNACE, 3400.0),
            Map.entry(Blocks.DISPENSER, 2350.0),
            Map.entry(Blocks.DROPPER, 2350.0),
            Map.entry(Blocks.OBSERVER, 2350.0),
            Map.entry(Blocks.STONECUTTER, 2450.0),
            Map.entry(Blocks.GRINDSTONE, 2450.0),
            Map.entry(Blocks.SMITHING_TABLE, 1600.0),
            Map.entry(Blocks.PISTON, 2100.0),
            Map.entry(Blocks.STICKY_PISTON, 2100.0),
            Map.entry(Blocks.GLOWSTONE, 1800.0),
            Map.entry(Blocks.SEA_LANTERN, 2200.0),
            Map.entry(Blocks.REDSTONE_LAMP, 2100.0)
    );

    private static final String[] VARIANT_SUFFIXES = {
            "_slab", "_stairs", "_wall", "_fence_gate", "_fence", "_door", "_trapdoor",
            "_button", "_pressure_plate", "_hanging_sign", "_wall_hanging_sign", "_wall_sign", "_sign"
    };

    private static final Map<String, Double> BASE_PATH_DENSITY = Map.ofEntries(
            Map.entry("stone", 2450.0),
            Map.entry("smooth_stone", 2450.0),
            Map.entry("cobblestone", 2350.0),
            Map.entry("mossy_cobblestone", 2350.0),
            Map.entry("stone_brick", 2400.0),
            Map.entry("stone_bricks", 2400.0),
            Map.entry("mossy_stone_brick", 2400.0),
            Map.entry("mossy_stone_bricks", 2400.0),
            Map.entry("deepslate", 2750.0),
            Map.entry("cobbled_deepslate", 2670.0),
            Map.entry("polished_deepslate", 2750.0),
            Map.entry("deepslate_brick", 2670.0),
            Map.entry("deepslate_bricks", 2670.0),
            Map.entry("deepslate_tile", 2700.0),
            Map.entry("deepslate_tiles", 2700.0),
            Map.entry("basalt", 2900.0),
            Map.entry("blackstone", 2850.0),
            Map.entry("polished_blackstone", 2850.0),
            Map.entry("polished_blackstone_brick", 2820.0),
            Map.entry("polished_blackstone_bricks", 2820.0),
            Map.entry("granite", 2700.0),
            Map.entry("polished_granite", 2700.0),
            Map.entry("diorite", 2850.0),
            Map.entry("polished_diorite", 2850.0),
            Map.entry("andesite", 2650.0),
            Map.entry("polished_andesite", 2650.0),
            Map.entry("tuff", 1850.0),
            Map.entry("polished_tuff", 1900.0),
            Map.entry("tuff_brick", 1900.0),
            Map.entry("tuff_bricks", 1900.0),
            Map.entry("sandstone", 2250.0),
            Map.entry("cut_sandstone", 2250.0),
            Map.entry("smooth_sandstone", 2250.0),
            Map.entry("red_sandstone", 2250.0),
            Map.entry("cut_red_sandstone", 2250.0),
            Map.entry("smooth_red_sandstone", 2250.0),
            Map.entry("brick", 1900.0),
            Map.entry("bricks", 1900.0),
            Map.entry("mud_brick", 1650.0),
            Map.entry("mud_bricks", 1650.0),
            Map.entry("nether_brick", 2150.0),
            Map.entry("nether_bricks", 2150.0),
            Map.entry("red_nether_brick", 2200.0),
            Map.entry("red_nether_bricks", 2200.0),
            Map.entry("end_stone_brick", 2550.0),
            Map.entry("end_stone_bricks", 2550.0),
            Map.entry("prismarine", 2550.0),
            Map.entry("prismarine_brick", 2650.0),
            Map.entry("prismarine_bricks", 2650.0),
            Map.entry("dark_prismarine", 2700.0),
            Map.entry("quartz", 2650.0),
            Map.entry("smooth_quartz", 2650.0),
            Map.entry("purpur", 2200.0),
            Map.entry("resin_brick", 1600.0),
            Map.entry("resin_bricks", 1600.0)
    );

    private static final ConcurrentHashMap<Block, CustomProperties> CUSTOM = new ConcurrentHashMap<>();

    private Materials() {}

    static void registerCustom(Block block, CustomProperties properties) {
        CUSTOM.put(block, properties);
        SIGNATURES.keySet().removeIf(state -> state.getBlock() == block);
        FrictionConfig.invalidate(block);
    }

    @Nullable
    static CustomProperties custom(Block block) {
        return CUSTOM.get(block);
    }

    static double density(BlockState state) {
        Block block = state.getBlock();
        CustomProperties custom = CUSTOM.get(block);
        if (custom != null && custom.density() != null) return custom.density();
        Double known = DENSITY.get(block);
        if (known != null) return known;
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        String path = id.getPath();
        if (id.getNamespace().equals(Aerolith.ID)) {
            return switch (path) {
                case "physics_assembler" -> 4200.0;
                default -> 0.0;
            };
        }
        if (!id.getNamespace().equals("minecraft")) return 0.0;
        return resolveMinecraftDensity(block, path);
    }

    private static double resolveMinecraftDensity(Block block, String path) {
        if (path.endsWith("_wool") || path.endsWith("_carpet")) return 55.0;
        if (path.endsWith("_bed")) return 180.0;
        if (block instanceof LeavesBlock || path.endsWith("_leaves")) return 220.0;
        if (path.endsWith("_concrete_powder")) return 1550.0;
        if (path.endsWith("_concrete")) return 2350.0;
        if (path.equals("terracotta") || path.endsWith("_terracotta")) return 1900.0;
        if (path.endsWith("_stained_glass") || path.endsWith("_stained_glass_pane")) return 2500.0;
        if (path.contains("copper")) {
            if (path.equals("copper_grate") || path.endsWith("_copper_grate")) return 3200.0;
            if (copper(path) || path.endsWith("_copper_bulb") || path.endsWith("_copper_door")
                    || path.endsWith("_copper_trapdoor") || path.endsWith("_copper_chain") || path.endsWith("_copper_bars")) {
                return 8960.0;
            }
        }
        if (path.endsWith("_chain") || path.equals("chain")) return 7870.0;
        if (path.startsWith("deepslate_") && path.endsWith("_ore")) return 3400.0;
        if (path.endsWith("_ore")) return 3100.0;
        if (log(path)) {
            String wood = findWoodPrefix(path);
            return wood != null ? WOOD_LOG_DENSITY.get(wood) : 650.0;
        }
        if (path.equals("bamboo_mosaic")) return WOOD_PLANK_DENSITY.get("bamboo");
        String base = stripVariantSuffix(path);
        String wood = findWoodPrefix(base);
        if (wood != null) {
            if (base.equals(wood) || base.equals(wood + "_planks") || base.equals(wood + "_mosaic")) {
                return WOOD_PLANK_DENSITY.get(wood);
            }
            if (base.equals("stripped_" + wood) || base.equals("stripped_" + wood + "_log")
                    || base.equals("stripped_" + wood + "_wood") || base.equals("stripped_" + wood + "_stem")) {
                return WOOD_LOG_DENSITY.get(wood);
            }
        }
        if (path.endsWith("_planks")) return 480.0;
        Double derived = BASE_PATH_DENSITY.get(base);
        if (derived != null) return derived;
        if (path.contains("quartz") && path.endsWith("_block")) return 2650.0;
        if (path.equals("bricks") || path.endsWith("_bricks")) return 1950.0;
        if (block instanceof SlabBlock || block instanceof StairBlock) {
            return switch (surface(block.defaultBlockState())) {
                case WOOD -> 480.0;
                case METAL -> 8960.0;
                default -> path.contains("brick") ? 1950.0 : 2350.0;
            };
        }
        return 0.0;
    }

    @Nullable
    private static String findWoodPrefix(String path) {
        String clean = path.startsWith("stripped_") ? path.substring(9) : path;
        for (String wood : WOOD_ORDER) {
            if (clean.equals(wood) || clean.startsWith(wood + "_")) return wood;
        }
        return null;
    }

    private static String stripVariantSuffix(String path) {
        for (String suffix : VARIANT_SUFFIXES) {
            if (path.endsWith(suffix)) return path.substring(0, path.length() - suffix.length());
        }
        return path;
    }

    /** Density for the mass of a solid block: its own if known, otherwise a middling value. */
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
    private record Signature(
            Block block,
            double density,
            List<AABB> boxes,
            List<AABB> outline,
            boolean fullBlock,
            boolean sealsCavity,
            @Nullable AeroProperty aero
    ) {}

    private static final ConcurrentHashMap<BlockState, Signature> SIGNATURES = new ConcurrentHashMap<>();

    private static Signature signature(BlockState state) {
        return SIGNATURES.computeIfAbsent(state, s -> {
            List<AABB> boxes = List.copyOf(computeBoxes(s));
            boolean full = boxes.size() == 1 && boxes.getFirst().getXsize() >= 1.0 - 1.0e-6
                    && boxes.getFirst().getYsize() >= 1.0 - 1.0e-6 && boxes.getFirst().getZsize() >= 1.0 - 1.0e-6;
            CustomProperties custom = CUSTOM.get(s.getBlock());
            boolean seals = custom != null && custom.sealsCavity() != null
                    ? custom.sealsCavity()
                    : full && !isPorousBlock(s.getBlock());
            AeroProperty aero = custom != null ? custom.aero() : null;
            return new Signature(s.getBlock(), computeMassDensity(s), boxes,
                    boxes.isEmpty() ? List.copyOf(computeOutline(s)) : List.of(), full, seals, aero);
        });
    }

    private static boolean isPorousBlock(Block block) {
        if (block instanceof LeavesBlock) return true;
        if (block == Blocks.SPONGE || block == Blocks.WET_SPONGE || block == Blocks.HAY_BLOCK
                || block == Blocks.MANGROVE_ROOTS || block == Blocks.MUDDY_MANGROVE_ROOTS) return true;
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        return id.getPath().endsWith("copper_grate");
    }

    static boolean isFullBlock(BlockState state) {
        return signature(state).fullBlock();
    }

    static boolean sealsCavity(BlockState state) {
        return signature(state).sealsCavity();
    }

    @Nullable
    static AeroProperty aero(BlockState state) {
        return signature(state).aero();
    }

    /**
     * Whether a change from {@code a} to {@code b} leaves the hull as it was: a lever thrown, a lamp lit, redstone dust
     * that gains or loses power. Its mass, colliders, friction and the shape one aims at are the same.
     */
    static boolean samePhysics(BlockState a, BlockState b) {
        return a == b || signature(a).equals(signature(b));
    }

    /** Whether the block has a collision shape: a block without one has no mass and takes part in no contact. */
    static boolean hasCollision(BlockState state) {
        return !signature(state).boxes().isEmpty();
    }

    static List<AABB> boxes(BlockState state) {
        return signature(state).boxes();
    }

    private static List<AABB> computeBoxes(BlockState state) {
        // Slivers thinner than the physics engine's minimum would only be rejected there.
        return state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs().stream()
                .filter(b -> b.getXsize() >= 0.002 && b.getYsize() >= 0.002 && b.getZsize() >= 0.002).toList();
    }

    static Surface surface(BlockState state) {
        CustomProperties custom = CUSTOM.get(state.getBlock());
        if (custom != null && custom.surface() != null) return custom.surface();
        if (state.is(Blocks.ICE) || state.is(Blocks.PACKED_ICE) || state.is(Blocks.BLUE_ICE)
                || state.is(Blocks.FROSTED_ICE)) return Surface.ICE;
        if (state.is(Blocks.SLIME_BLOCK)) return Surface.SLIME;
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id.getNamespace().equals(Aerolith.ID)) {
            return switch (id.getPath()) {
                case "physics_assembler" -> Surface.METAL;
                default -> Surface.DEFAULT;
            };
        }
        if (!id.getNamespace().equals("minecraft")) return Surface.DEFAULT;
        String path = id.getPath();
        if (path.endsWith("_wool") || path.endsWith("_carpet") || path.endsWith("_bed")) return Surface.WOOL;
        if (path.equals("glass") || path.endsWith("_glass") || path.endsWith("glass_pane") || path.equals("sea_lantern")) {
            return Surface.GLASS;
        }
        if (copper(path) || path.equals("iron_block") || path.equals("gold_block") || path.equals("netherite_block")
                || path.equals("heavy_core") || path.equals("iron_bars") || path.equals("iron_door")
                || path.equals("iron_trapdoor") || path.endsWith("anvil") || path.equals("cauldron")
                || path.equals("hopper") || path.endsWith("chain") || path.equals("raw_iron_block")
                || path.equals("raw_gold_block") || path.equals("raw_copper_block")) {
            return Surface.METAL;
        }
        String base = stripVariantSuffix(path);
        if (findWoodPrefix(base) != null || log(path) || path.endsWith("_planks") || path.equals("bamboo_mosaic")
                || path.endsWith("bookshelf") || path.equals("crafting_table") || path.equals("barrel")
                || path.endsWith("chest") && !path.equals("ender_chest") || path.equals("loom") || path.equals("lectern")) {
            return Surface.WOOD;
        }
        if (state.getBlock() instanceof SlabBlock || state.getBlock() instanceof StairBlock) {
            return Surface.STONE;
        }
        return BASE_PATH_DENSITY.containsKey(base) || path.contains("stone") || path.contains("deepslate")
                || path.contains("brick") || path.contains("quartz") || path.endsWith("_concrete")
                || path.contains("terracotta") || path.equals("obsidian") || path.equals("crying_obsidian")
                || path.contains("basalt") || path.contains("tuff") || path.equals("netherrack")
                || path.equals("calcite") || path.equals("dripstone_block") || path.equals("prismarine")
                || path.contains("purpur") || path.equals("lodestone") || path.equals("furnace")
                || path.equals("blast_furnace") || path.equals("dispenser") || path.equals("dropper")
                || path.equals("observer")
                ? Surface.STONE : Surface.DEFAULT;
    }

    private static boolean log(String path) {
        return path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_stem")
                || path.endsWith("_hyphae") || path.endsWith("bamboo_block");
    }

    private static boolean copper(String path) {
        String unwaxed = path.startsWith("waxed_") ? path.substring(6) : path;
        return unwaxed.equals("copper_block") || unwaxed.endsWith("_copper") || unwaxed.endsWith("_copper_slab")
                || unwaxed.endsWith("_copper_stairs") || unwaxed.endsWith("_copper_bulb")
                || unwaxed.endsWith("_copper_door") || unwaxed.endsWith("_copper_trapdoor");
    }
}
