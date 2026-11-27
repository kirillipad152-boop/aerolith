package dev.aerolith;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

final class NativePhysics {
    static {
        load();
    }

    private NativePhysics() {}

    static native long createWorld();
    static native void destroyWorld(long world);
    // Pose: origin xyz, quaternion xyzw, COM velocity xyz and world angular velocity xyz.
    // A box adds half extents, friction and restitution to its local center; -1 friction consumes its grid (X fastest, then Y, Z).
    // A mass adds half extents and density to its own; masses have no shape to collide with.
    static native long addBody(long world, double[] state, double[] boxes, double[] frictionCells, double[] masses, double[] surfaces);
    static native void removeBody(long world, long body);
    static native void setActive(long world, long body, boolean active);
    static native void setThrottle(long world, long body, double throttle);
    static native void setControls(long world, long body, double pitch, double yaw, double roll);
    // How far the flaps are down, 0 to 1.
    static native void setFlaps(long world, long body, double flaps);
    static native void impulse(long world, long body, double ix, double iy, double iz);
    static native void staffPull(long world, long body, double ix, double iy, double iz, double qx, double qy, double qz, double qw);
    // World-space impulse applied at a world-space point: it adds torque by the lever arm to the COM change.
    static native void impulseAt(long world, long body, double ix, double iy, double iz, double px, double py, double pz);
    // The same spread over the coming tick, for what stands on the body; a still load leaves a body at rest alone.
    static native void loadAt(long world, long body, double ix, double iy, double iz, double px, double py, double pz, boolean still);
    static native void wake(long world, long body);
    static native void setTerrain(long world, long key, double[] boxes);
    static native void setWater(long world, long key, double[] cells);
    /** Length of one record of {@link #step}. */
    static final int RECORD = 15;
    // Output: for every body that moved, its handle, its pose in the same format, and 1 if it is asleep now.
    static native double[] step(long world, double windX, double windY, double windZ);

    private static void load() {
        String osName = System.getProperty("os.name");
        String archName = System.getProperty("os.arch");
        String osKey = osName.toLowerCase(Locale.ROOT);
        String os = osKey.startsWith("linux") ? "linux"
                : osKey.startsWith("mac") ? "macos"
                : osKey.startsWith("windows") ? "windows" : null;
        String arch = switch (archName.toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64" -> "x86_64";
            case "arm64", "aarch64" -> "aarch64";
            default -> null;
        };
        if (os == null || arch == null) {
            throw new UnsatisfiedLinkError("Aerolith native physics does not support " + osName + " / " + archName
                    + ". Use a 64-bit x86 or ARM JVM on Linux (glibc), macOS, or Windows.");
        }

        String filename = System.mapLibraryName("aerolith_physics");
        String resource = "/natives/" + os + "-" + arch + "/" + filename;
        Path library;
        try (InputStream stream = NativePhysics.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new UnsatisfiedLinkError("Aerolith is missing " + resource
                        + ". Install a jar built for this platform, or build Aerolith on it.");
            }
            Path directory = Files.createTempDirectory("aerolith-");
            directory.toFile().deleteOnExit();
            library = directory.resolve(filename);
            library.toFile().deleteOnExit();
            Files.copy(stream, library);
        } catch (IOException failure) {
            throw loadError("Aerolith could not extract " + resource + "; check that java.io.tmpdir is writable.", failure);
        }

        try {
            System.load(library.toAbsolutePath().toString());
        } catch (UnsatisfiedLinkError failure) {
            throw loadError("Aerolith could not load " + resource
                    + "; check native dependencies and that java.io.tmpdir allows loading libraries.", failure);
        }
    }

    private static UnsatisfiedLinkError loadError(String message, Throwable cause) {
        UnsatisfiedLinkError error = new UnsatisfiedLinkError(message);
        error.initCause(cause);
        return error;
    }
}
