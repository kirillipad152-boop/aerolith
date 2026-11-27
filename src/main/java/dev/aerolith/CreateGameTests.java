package dev.aerolith;

import java.util.Random;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Crafts with Create machinery on board. They run apart from the others (see createGameTestServer in build.gradle): with Create loaded, a mock player cannot log in. */
@GameTestHolder("aerolith_create")
@PrefixGameTestTemplate(false)
public final class CreateGameTests {
    private static ServerPlayer player(ServerLevel level) {
        return new FakePlayer(level, new GameProfile(UUID.randomUUID(), "AerolithTest"));
    }

    /** Two identical crafts fall side by side; the one with its fan running must fall faster, which is the fan pushing it down. */
    @GameTest(template = "test_pad", batch = "aerolith_create", timeoutTicks = 300)
    public static void createFanPushesItsCraftAgainstItsAir(GameTestHelper test) {
        if (!Propulsion.createLoaded()) {
            test.succeed();
            return;
        }
        ServerLevel level = test.getLevel();
        ServerPlayer player = player(level);
        CraftEntity[] crafts = new CraftEntity[2];
        double[] gap = new double[1];
        test.startSequence().thenExecute(() -> {
            crafts[0] = CreateGameRig.fanCraft(test, player, new BlockPos(3, 45, 3), 256);
            crafts[1] = CreateGameRig.fanCraft(test, player, new BlockPos(7, 45, 3), 0);
            test.assertTrue(crafts[0] != null && crafts[1] != null, "A craft with Create blocks was not made");
        }).thenIdle(25).thenExecute(() -> {
            var forces = CreateGameRig.forces(crafts[0]);
            test.assertTrue(forces.size() == 1 && forces.getFirst().y < -2000 && Math.abs(forces.getFirst().x) < 1e-9,
                    "A fan at full speed must push its craft down with about 2500 N, got " + forces);
            test.assertTrue(CreateGameRig.forces(crafts[1]).stream().allMatch(f -> f.lengthSqr() == 0), "A fan that is not turning pushed");
            gap[0] = crafts[0].getY() - crafts[1].getY();
        }).thenIdle(20).thenExecute(() -> {
            double moved = crafts[0].getY() - crafts[1].getY() - gap[0];
            test.assertTrue(moved < -0.3, "The fan craft must have fallen at least 0.3 m further than the other, got " + moved);
            crafts[0].discard();
            crafts[1].discard();
        }).thenSucceed();
    }

    /** The same with a bearing: its planks are taken into a contraption and, turning, push the craft the way a propeller does. */
    @GameTest(template = "test_pad", batch = "aerolith_create", timeoutTicks = 400)
    public static void createBearingTurnsPlanksIntoAPropeller(GameTestHelper test) {
        if (!Propulsion.createLoaded()) {
            test.succeed();
            return;
        }
        ServerLevel level = test.getLevel();
        ServerPlayer player = player(level);
        CraftEntity[] crafts = new CraftEntity[2];
        double[] gap = new double[1];
        test.startSequence().thenExecute(() -> {
            crafts[0] = CreateGameRig.propellerCraft(test, player, new BlockPos(3, 45, 3), 256);
            crafts[1] = CreateGameRig.propellerCraft(test, player, new BlockPos(10, 45, 3), 0);
            test.assertTrue(crafts[0] != null && crafts[1] != null, "A craft with a bearing was not made");
        }).thenWaitUntil(() -> test.assertTrue(CreateGameRig.bearingRuns(crafts[0]), "The bearing never took its planks into a contraption"))
                .thenExecute(() -> {
                    var forces = CreateGameRig.forces(crafts[0]);
                    test.assertTrue(forces.stream().anyMatch(f -> f.y < -500 && f.y > -80_001),
                            "A turning propeller must push down with hundreds of newtons or more, got " + forces + "; " + CreateGameRig.describeBearings(crafts[0]));
                    gap[0] = crafts[0].getY() - crafts[1].getY();
                }).thenIdle(20).thenExecute(() -> {
                    double moved = crafts[0].getY() - crafts[1].getY() - gap[0];
                    test.assertTrue(moved < -0.1, "The propeller craft must fall further than the one with a stopped bearing, got " + moved
                            + "; " + CreateGameRig.describeBearings(crafts[0]) + "; forces " + CreateGameRig.forces(crafts[0]));
                    crafts[0].discard();
                    crafts[1].discard();
                }).thenSucceed();
    }

    /** A windmill bearing makes its own rotation out of its sails, and pushes with it. */
    @GameTest(template = "test_pad", batch = "aerolith_create", timeoutTicks = 400)
    public static void createWindmillBearingPushesWithItsOwnRotation(GameTestHelper test) {
        if (!Propulsion.createLoaded()) {
            test.succeed();
            return;
        }
        ServerLevel level = test.getLevel();
        ServerPlayer player = player(level);
        CraftEntity[] craft = new CraftEntity[1];
        test.startSequence().thenExecute(() -> {
            craft[0] = CreateGameRig.windmillCraft(test, player, new BlockPos(3, 45, 3));
            test.assertTrue(craft[0] != null, "A craft with a windmill bearing was not made");
        }).thenIdle(5).thenExecute(() -> CreateGameRig.startBearing(craft[0]))
                .thenWaitUntil(() -> test.assertTrue(CreateGameRig.bearingRuns(craft[0]), "The windmill never started: " + CreateGameRig.describeBearings(craft[0])))
                .thenExecute(() -> {
                    var forces = CreateGameRig.forces(craft[0]);
                    test.assertTrue(forces.stream().anyMatch(f -> f.y < 0 && Math.abs(f.x) < 1e-9), "A turning windmill must push, got " + forces
                            + "; " + CreateGameRig.describeBearings(craft[0]));
                    craft[0].discard();
                }).thenSucceed();
    }
}
