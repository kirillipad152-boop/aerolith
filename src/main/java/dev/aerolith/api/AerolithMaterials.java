package dev.aerolith.api;

import java.util.Objects;

import dev.aerolith.InternalApi;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Registration and query facade for block densities, surface friction/restitution, watertight cavity sealing,
 * and aerodynamic/thruster surface behavior.
 */
public final class AerolithMaterials {
    private AerolithMaterials() {}

    /**
     * Registers or updates physical properties for {@code block} and invalidates derived caches immediately.
     */
    public static void registerBlock(Block block, BlockPhysicsProperties properties) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(properties, "properties");
        InternalApi.registerMaterial(block, properties);
    }

    /** Returns the resolved density in kg/m³ used for {@code state} when computing hull mass and inertia. */
    public static double getDensity(BlockState state) {
        Objects.requireNonNull(state, "state");
        return InternalApi.density(state);
    }

    /** Returns the effective Coulomb friction coefficient configured for {@code state} in {@code level}. */
    public static double getFriction(ServerLevel level, BlockState state) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(state, "state");
        return InternalApi.friction(level, state);
    }

    /** Returns the restitution coefficient configured for {@code state} in {@code level}. */
    public static double getRestitution(ServerLevel level, BlockState state) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(state, "state");
        return InternalApi.restitution(level, state);
    }

    /** Whether {@code state} seals enclosed interior air pockets against water flooding. */
    public static boolean sealsCavity(BlockState state) {
        Objects.requireNonNull(state, "state");
        return InternalApi.sealsCavity(state);
    }
}
