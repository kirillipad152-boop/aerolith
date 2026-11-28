package dev.aerolith.api;

import java.util.Objects;

import javax.annotation.Nullable;
import net.minecraft.world.phys.Vec3;

/**
 * Physical and aerodynamic overrides for a custom or modded {@code Block} registered through {@link AerolithMaterials}.
 */
public final class BlockPhysicsProperties {
    @Nullable private final Double density;
    @Nullable private final SurfacePreset preset;
    @Nullable private final Double friction;
    @Nullable private final Double restitution;
    @Nullable private final Boolean sealsCavity;
    @Nullable private final SurfaceBehavior aerodynamicBehavior;
    @Nullable private final Vec3 aerodynamicDirection;
    private final double aerodynamicMagnitude;
    private final boolean scaleAerodynamicByArea;

    private BlockPhysicsProperties(Builder builder) {
        this.density = builder.density;
        this.preset = builder.preset;
        this.friction = builder.friction;
        this.restitution = builder.restitution;
        this.sealsCavity = builder.sealsCavity;
        this.aerodynamicBehavior = builder.aerodynamicBehavior;
        this.aerodynamicDirection = builder.aerodynamicDirection;
        this.aerodynamicMagnitude = builder.aerodynamicMagnitude;
        this.scaleAerodynamicByArea = builder.scaleAerodynamicByArea;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Nullable public Double density() { return density; }
    @Nullable public SurfacePreset preset() { return preset; }
    @Nullable public Double friction() { return friction; }
    @Nullable public Double restitution() { return restitution; }
    @Nullable public Boolean sealsCavity() { return sealsCavity; }
    @Nullable public SurfaceBehavior aerodynamicBehavior() { return aerodynamicBehavior; }
    @Nullable public Vec3 aerodynamicDirection() { return aerodynamicDirection; }
    public double aerodynamicMagnitude() { return aerodynamicMagnitude; }
    public boolean scaleAerodynamicByArea() { return scaleAerodynamicByArea; }

    public static final class Builder {
        @Nullable private Double density;
        @Nullable private SurfacePreset preset;
        @Nullable private Double friction;
        @Nullable private Double restitution;
        @Nullable private Boolean sealsCavity;
        @Nullable private SurfaceBehavior aerodynamicBehavior;
        @Nullable private Vec3 aerodynamicDirection;
        private double aerodynamicMagnitude;
        private boolean scaleAerodynamicByArea = true;

        private Builder() {}

        /** Material density in kg/m³ (e.g. {@code 480.0} for oak planks, {@code 7870.0} for iron). */
        public Builder density(double kgPerCubicMeter) {
            if (!Double.isFinite(kgPerCubicMeter) || kgPerCubicMeter < 0.001 || kgPerCubicMeter > 100_000.0) {
                throw new IllegalArgumentException("density must be in [0.001, 100000]");
            }
            this.density = kgPerCubicMeter;
            return this;
        }

        /** Surface preset governing default friction unless overridden by {@link #friction(double)}. */
        public Builder surfacePreset(SurfacePreset preset) {
            this.preset = Objects.requireNonNull(preset, "preset");
            return this;
        }

        /** Coulomb friction coefficient in {@code [0.0, 3.0]}. */
        public Builder friction(double mu) {
            if (!Double.isFinite(mu) || mu < 0.0 || mu > 3.0) {
                throw new IllegalArgumentException("friction must be in [0.0, 3.0]");
            }
            this.friction = mu;
            return this;
        }

        /** Coefficient of restitution in {@code [0.0, 1.0]}. */
        public Builder restitution(double e) {
            if (!Double.isFinite(e) || e < 0.0 || e > 1.0) {
                throw new IllegalArgumentException("restitution must be in [0.0, 1.0]");
            }
            this.restitution = e;
            return this;
        }

        /** Explicitly controls whether a full-cube instance of this block seals interior air cavities. */
        public Builder sealsCavity(boolean seals) {
            this.sealsCavity = seals;
            return this;
        }

        /**
         * Configures airfoil or thrust behavior on this block in craft-local coordinates.
         * Foils and control surfaces must have normals perpendicular to the local {@code +Z} forward chord.
         */
        public Builder aerodynamicSurface(
                SurfaceBehavior behavior,
                Vec3 localDirection,
                double magnitude,
                boolean scaleByExposedArea
        ) {
            Objects.requireNonNull(behavior, "behavior");
            Objects.requireNonNull(localDirection, "localDirection");
            if (!Double.isFinite(localDirection.x + localDirection.y + localDirection.z)
                    || localDirection.lengthSqr() < 1.0e-12) {
                throw new IllegalArgumentException("localDirection must be finite and non-zero");
            }
            Vec3 norm = localDirection.normalize();
            double maxMag = behavior == SurfaceBehavior.THRUST ? 1.0e9 : 4096.0;
            if (!Double.isFinite(magnitude) || magnitude < 0.0 || magnitude > maxMag) {
                throw new IllegalArgumentException("magnitude out of range: " + magnitude);
            }
            if (behavior.isFoil()) {
                if (Math.abs(norm.z) > 1.0e-6) {
                    throw new IllegalArgumentException("foil normal must be perpendicular to local +Z chord");
                }
                norm = new Vec3(norm.x, norm.y, 0.0).normalize();
            }
            this.aerodynamicBehavior = behavior;
            this.aerodynamicDirection = norm;
            this.aerodynamicMagnitude = magnitude;
            this.scaleAerodynamicByArea = scaleByExposedArea;
            return this;
        }

        public BlockPhysicsProperties build() {
            return new BlockPhysicsProperties(this);
        }
    }
}
