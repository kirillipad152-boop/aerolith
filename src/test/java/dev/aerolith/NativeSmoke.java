package dev.aerolith;

final class NativeSmoke {
    public static void main(String[] args) {
        long world = NativePhysics.createWorld();
        if (world == 0) {
            throw new AssertionError("JNI returned a null world handle");
        }
        try {
            NativePhysics.setTerrain(world, 0, new double[] {0, -0.5, 0, 4, 0.5, 4, 0.65, 0});
            NativePhysics.setWater(world, 1, new double[0]);
            long body = NativePhysics.addBody(world, new double[] {0, 5, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0},
                    new double[] {0, 0, 0, 0.5, 0.5, 0.5, -1, 0}, new double[] {0.65},
                    new double[] {0, 0, 0, 0.5, 0.5, 0.5, 480}, new double[0]);
            NativePhysics.setThrottle(world, body, 0);
            NativePhysics.setControls(world, body, 0, 0, 0);
            NativePhysics.setFlaps(world, body, 0.5);
            NativePhysics.impulse(world, body, 480, 0, 0);
            NativePhysics.loadAt(world, body, 0, -1, 0, 0, 5.5, 0, true);
            double[] state = NativePhysics.step(world, 0, 0, 0);
            if (state.length != NativePhysics.RECORD || state[0] != body || Math.abs(state[1] - 0.05) > 1e-8 || !(state[2] < 5)
                    || state[NativePhysics.RECORD - 1] != 0) {
                throw new AssertionError("The packaged native library did not deliver the expected body state");
            }
            NativePhysics.setActive(world, body, false);
            if (NativePhysics.step(world, 0, 0, 0).length != 0) {
                throw new AssertionError("An inactive body moved");
            }
            NativePhysics.setActive(world, body, true);
            NativePhysics.wake(world, body);
            double[] later = NativePhysics.step(world, 0, 0, 0);
            if (later.length != NativePhysics.RECORD || !(later[9] < state[9]) || Math.abs(later[8] - state[8]) > 1e-8) {
                throw new AssertionError("An inactive body lost momentum");
            }
            NativePhysics.removeBody(world, body);
            try {
                NativePhysics.setThrottle(world, body, 0);
                throw new AssertionError("A stale handle was accepted");
            } catch (IllegalArgumentException expected) {
                // Unknown handles must be rejected by JNI without dereferencing them.
            }
            checkCapacity(world);
        } finally {
            NativePhysics.destroyWorld(world);
        }
    }

    private static void checkCapacity(long world) {
        double[] pose = {0, 20, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0};
        double[] box = {0, 0, 0, 0.5, 0.5, 0.5, 0.65, 0}, mass = {0, 0, 0, 0.5, 0.5, 0.5, 500}, empty = {};
        long last = 0;
        for (int i = 0; i < WorldPhysics.MAX_BODIES; i++) {
            pose[0] = 2 * i;
            last = NativePhysics.addBody(world, pose, box, empty, mass, empty);
        }
        double[] moved = NativePhysics.step(world, 0, 0, 0);
        if (moved.length != WorldPhysics.MAX_BODIES * NativePhysics.RECORD) throw new AssertionError("Not all bodies stepped");
        try {
            NativePhysics.addBody(world, pose, box, empty, mass, empty);
            throw new AssertionError("Java and native body limits differ");
        } catch (IllegalStateException expected) {
            if (!"world body limit reached".equals(expected.getMessage())) throw expected;
        }
        NativePhysics.removeBody(world, last);
        NativePhysics.addBody(world, pose, box, empty, mass, empty);
    }
}
