package dev.aerolith.api;

/**
 * Aerodynamic or propulsion behavior associated with a craft block.
 */
public enum SurfaceBehavior {
    /** Standard bluff exterior panel experiencing pressure and skin-friction drag. */
    BLUFF_FACE(0),
    /** Fixed lifting airfoil generating lift perpendicular to local airflow and +Z chord. */
    FOIL(1),
    /** Directional thruster pushing along its local direction weighted by craft throttle. */
    THRUST(2),
    /** Pitch control airfoil responding to elevator stick input. */
    ELEVATOR(3),
    /** Yaw control airfoil responding to rudder input. */
    RUDDER(4),
    /** Roll control airfoil responding differentially to aileron stick input. */
    AILERON(5),
    /** Deployable camber flap controlled by craft flap setting. */
    FLAP(6);

    private final int nativeKind;

    SurfaceBehavior(int nativeKind) {
        this.nativeKind = nativeKind;
    }

    public int nativeKind() {
        return nativeKind;
    }

    public boolean isFoil() {
        return this != BLUFF_FACE && this != THRUST;
    }
}
