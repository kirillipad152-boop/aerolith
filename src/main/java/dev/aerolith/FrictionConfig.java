package dev.aerolith;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.ModConfigSpec;

final class FrictionConfig {
    record Contact(double mu, double restitution) {}
    private record BlockFriction(Identifier id, double mu) {}
    private static final double MAX_COEFFICIENT = 3.0;
    private static final Map<Materials.Surface, ModConfigSpec.DoubleValue> COEFFICIENTS = new EnumMap<>(Materials.Surface.class);
    private static final ModConfigSpec.ConfigValue<List<? extends String>> OVERRIDES;
    static final ModConfigSpec SPEC;

    static {
        var builder = new ModConfigSpec.Builder();
        builder.comment("Coulomb contact friction for physical hulls and the world. Reload the world after changing values.",
                "The smaller of the two coefficients is used at a contact. Zero gives no contact friction.").push("friction");
        for (Materials.Surface surface : Materials.Surface.values()) {
            COEFFICIENTS.put(surface, builder.defineInRange(surface.name().toLowerCase(Locale.ROOT),
                    surface.coefficient, 0.0, MAX_COEFFICIENT));
        }
        OVERRIDES = builder.comment("Optional block-specific coefficients, written as \"minecraft:oak_planks=0.55\".",
                "Entries override the preset and apply to this block in all its states. Last entry wins.")
                .defineListAllowEmpty("overrides", List.<String>of(), () -> "minecraft:oak_planks=0.55",
                        entry -> entry instanceof String line && parse(line) != null);
        SPEC = builder.pop().build();
    }

    // One table per ServerLevel; a mid-world file edit must not split old hull and new terrain coefficients.
    private final Map<Materials.Surface, Double> coefficients = new EnumMap<>(Materials.Surface.class);
    private final Map<Identifier, Double> overrides;
    private final Map<Block, Contact> cache = new IdentityHashMap<>();

    FrictionConfig() {
        this(SPEC.isLoaded() ? OVERRIDES.get() : List.of());
    }

    FrictionConfig(List<? extends String> custom) {
        for (Materials.Surface surface : Materials.Surface.values()) {
            coefficients.put(surface, SPEC.isLoaded() ? COEFFICIENTS.get(surface).get() : surface.coefficient);
        }
        Map<Identifier, Double> values = new HashMap<>();
        for (String entry : custom) {
            BlockFriction override = parse(entry);
            if (override != null) values.put(override.id, override.mu);
        }
        overrides = Map.copyOf(values);
    }

    Contact contact(BlockState state) {
        return cache.computeIfAbsent(state.getBlock(), block -> {
            Materials.Surface surface = Materials.surface(state);
            double mu = overrides.getOrDefault(BuiltInRegistries.BLOCK.getKey(block), coefficients.get(surface));
            // Only slime springs back. Blocks do not bounce in Minecraft, and any rebound at falling speed (50 m/s is 4 m/s at
            // a restitution of just 0.08) reads as crafts bouncing off each other.
            return new Contact(mu, surface == Materials.Surface.SLIME ? 0.78 : 0.0);
        });
    }

    private static BlockFriction parse(String line) {
        int equals = line.indexOf('=');
        if (equals < 1) return null;
        Identifier id = Identifier.tryParse(line.substring(0, equals).trim());
        if (id == null || id.getPath().isEmpty()) return null;
        try {
            double value = Double.parseDouble(line.substring(equals + 1).trim());
            return value >= 0.0 && value <= MAX_COEFFICIENT ? new BlockFriction(id, value) : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }
}
