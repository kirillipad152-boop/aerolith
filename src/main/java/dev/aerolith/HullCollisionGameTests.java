package dev.aerolith;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaternionf;

@GameTestHolder(Aerolith.ID)
@PrefixGameTestTemplate(false)
public final class HullCollisionGameTests {
    @GameTest(template = "test_pad")
    public static void rotatedContactAndSlideRespectTerrain(GameTestHelper test) {
        Vec3 origin = origin(test);
        CraftEntity craft = craft(test, origin, planks(BlockPos.ZERO));
        pose(craft, origin, new Quaternionf().rotationY((float) (Math.PI / 4)));
        ItemEntity item = item(test, origin.add(2, -0.125, 2));
        item.move(MoverType.SELF, new Vec3(-4, 0, -4));
        near(test, item, origin.add(Math.sqrt(0.125) + 0.125, -0.125, Math.sqrt(0.125) + 0.125),
                "Diagonal sweep must stop at the rotated face, not the craft bounds");

        test.getLevel().setBlockAndUpdate(BlockPos.containing(origin.add(0, 0, 1.5)), Blocks.STONE.defaultBlockState());
        item.setPos(origin.add(2, -0.125, 0.35));
        item.move(MoverType.SELF, new Vec3(-5, 0, 0));
        double z = item.getZ() - origin.z;
        test.assertTrue(z > 1 && z < 1.3751, "Projected slide must stop at the terrain wall, got z=" + z);
        item.discard();
        craft.discard();
        test.succeed();
    }

    @GameTest(template = "test_pad")
    public static void crossAxesAndHullOpeningsStayEmpty(GameTestHelper test) {
        Vec3 origin = origin(test);
        CraftEntity craft = craft(test, origin, planks(BlockPos.ZERO));
        pose(craft, origin, new Quaternionf().rotateXYZ(0.55f, 0.7f, 0.4f));
        // This pass is separated only by a cross axis, not either box's face axes.
        ItemEntity item = item(test, origin.add(2, -1.025, -0.3));
        item.move(MoverType.SELF, new Vec3(-4, 0, 0));
        near(test, item, origin.add(-2, -1.025, -0.3), "A tilted corner must not create a phantom collision");
        craft.discard();

        craft = craft(test, origin, planks(new BlockPos(-1, 0, 0)), planks(new BlockPos(1, 0, 0)),
                planks(new BlockPos(-1, 1, 0)), planks(new BlockPos(0, 1, 0)), planks(new BlockPos(1, 1, 0)));
        item.setPos(origin.add(0, -0.125, -2));
        item.move(MoverType.SELF, new Vec3(0, 0, 4));
        near(test, item, origin.add(0, -0.125, 2), "The open arch must not be filled by the craft bounds");
        item.discard();
        craft.discard();
        test.succeed();
    }

    @GameTest(template = "test_pad")
    public static void placementSeesBothSidesOfAColumnAndTracksHullChanges(GameTestHelper test) {
        Vec3 at = origin(test);
        Vec3 edge = new Vec3(Math.floor(at.x / 16) * 16 + 0.1, at.y + 30, at.z);
        CraftEntity craft = craft(test, edge, planks(BlockPos.ZERO));
        var level = test.getLevel();
        AABB otherSide = AABB.ofSize(edge.add(-0.3, 0, 0), 0.1, 0.1, 0.1);
        test.assertTrue(HullCollisions.overlapsCraft(level, otherSide, null), "Hull straddling a chunk edge was missed");
        test.assertTrue(!HullCollisions.overlapsCraft(level, otherSide, craft), "Ignored hull reported an overlap");

        Vec3 moved = edge.add(34, 0, 0);
        pose(craft, moved, new Quaternionf());
        AABB center = AABB.ofSize(moved, 0.1, 0.1, 0.1);
        test.assertTrue(!HullCollisions.overlapsCraft(level, otherSide, null)
                && HullCollisions.overlapsCraft(level, center, null), "Moved hull did not change columns");

        craft.edit(List.of(planks(BlockPos.ZERO), planks(new BlockPos(35, 0, 0))), List.of());
        AABB tip = center.move(35, 0, 0);
        test.assertTrue(HullCollisions.overlapsCraft(level, tip, null), "Distant part of a wide hull was missed");
        test.assertTrue(!HullCollisions.overlapsCraft(level, center.move(17, 0, 0), null), "Index filled a gap in the wide hull");
        craft.discard();
        test.assertTrue(!HullCollisions.overlapsCraft(level, center, null)
                && !HullCollisions.overlapsCraft(level, tip, null), "Removed hull stayed in the index");
        test.succeed();
    }

    @GameTest(template = "test_pad")
    public static void slabSupportsAndCarriesItems(GameTestHelper test) {
        Vec3 origin = origin(test);
        CraftEntity craft = craft(test, origin, new CraftBlock(BlockPos.ZERO, Blocks.SMOOTH_STONE_SLAB.defaultBlockState()));
        ItemEntity item = item(test, origin.add(0.25, 1, 0));
        item.move(MoverType.SELF, new Vec3(0, -2, 0));
        near(test, item, origin.add(0.25, 0, 0), "Item must land on the physical slab, not an inflated cube");
        test.assertTrue(item.onGround(), "Landing on a hull must set vanilla onGround");

        Vec3 next = origin.add(0.4, 0.15, 0.15);
        HullCollisions.step(test.getLevel(), true, () -> pose(craft, next, new Quaternionf().rotationY(0.12f)));
        near(test, item, next.add(0.25 * Math.cos(0.12), 0, -0.25 * Math.sin(0.12)),
                "A deck must carry a supported item with translation and yaw");
        test.assertTrue(item.onGround(), "Carried item must stay grounded");

        Vec3 wall = origin.add(1.5, 0, 0);
        test.getLevel().setBlockAndUpdate(BlockPos.containing(wall), Blocks.STONE.defaultBlockState());
        pose(craft, next.add(1.1, 0, 0), craft.orientation(1));
        test.assertTrue(item.getX() > origin.x + 1 && item.getBoundingBox().maxX <= wall.x + 1.0e-5,
                "Carry must stop the item at static terrain rather than tunnel through it");
        item.discard();
        craft.discard();
        test.succeed();
    }

    @GameTest(template = "test_pad")
    public static void translatingSidePushesInsteadOfPassingThrough(GameTestHelper test) {
        Vec3 origin = origin(test);
        CraftEntity craft = craft(test, origin, planks(BlockPos.ZERO));
        ItemEntity item = item(test, origin.add(0.8, -0.125, 0));
        pose(craft, origin.add(1.5, 0, 0), new Quaternionf());
        near(test, item, origin.add(2.125, -0.125, 0), "A moving side must not pass through a stationary item");
        item.discard();
        craft.discard();
        test.succeed();
    }

    @SuppressWarnings("removal")
    @GameTest(template = "test_pad")
    public static void playerCrouchesAndPacketsRespectDeck(GameTestHelper test) {
        Vec3 origin = origin(test);
        CraftEntity craft = craft(test, origin, planks(BlockPos.ZERO), planks(new BlockPos(1, 0, 0)));
        ServerPlayer player = test.makeMockServerPlayerInLevel();
        try {
            player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(1));
            player.setPos(origin.add(0, 1, 0));
            player.move(MoverType.SELF, new Vec3(0, -1, 0));
            test.assertTrue(player.onGround(), "Player was not grounded on the deck");
            player.setShiftKeyDown(true);
            player.move(MoverType.PLAYER, new Vec3(0.16, -0.08, 0));
            near(test, player, origin.add(0.16, 0.5, 0), "Sneaking must allow walking along the deck");
            player.setShiftKeyDown(false);

            Vec3 current = origin;
            Vec3 carry = new Vec3(0.09, 0.02, 0);
            for (int tick = 0; tick < 3; tick++) {
                Vec3 carried = player.position().add(carry);
                current = current.add(carry);
                pose(craft, current, new Quaternionf());
                near(test, player, carried, "Player was not carried with the slow deck");
                player.connection.resetPosition();
                Vec3 walk = carried.add(0.12, 0, 0);
                packet(player, walk, true);
                near(test, player, walk, "Server corrected a legal predicted move along the deck");
            }

            Vec3 beside = current.add(-0.875, -0.1, 0);
            player.setPos(beside);
            player.connection.resetPosition();
            // The client resolves contact itself and lags a moving hull, so a shallow overlap must not rubber-band.
            packet(player, beside.add(0.14, 0, 0), false);
            near(test, player, beside.add(0.14, 0, 0), "A shallow overlap must be accepted, not teleported back");
            player.setPos(beside);
            player.connection.resetPosition();
            packet(player, beside.add(0.5, 0, 0), false);
            near(test, player, beside, "The packet handler must reject a move that ends deep inside the hull");

            player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(2));
            Vec3 above = current.add(0, 1.2, 0);
            player.setPos(above);
            player.connection.resetPosition();
            packet(player, current.add(0, -2, 0), false);
            near(test, player, above, "The packet handler must reject crossing the hull to a clear endpoint");
            test.succeed();
        } finally {
            craft.discard();
            test.getLevel().getServer().getPlayerList().remove(player);
        }
    }

    @GameTest(template = "test_pad")
    public static void creatureWalksUpATiltedDeckWithoutSlidingBack(GameTestHelper test) {
        Vec3 origin = origin(test);
        CraftBlock[] deck = new CraftBlock[15];
        for (int i = 0; i < 15; i++) deck[i] = planks(new BlockPos(i % 5 - 2, 0, i / 5 - 1));
        CraftEntity craft = craft(test, origin, deck);
        float tilt = 0.35f; // about 20 degrees, rising toward +x
        pose(craft, origin, new Quaternionf().rotationZ(tilt));
        var pig = test.spawn(net.minecraft.world.entity.EntityType.PIG, 0, 0, 0);
        double c = Math.cos(tilt), s = Math.sin(tilt);
        pig.setPos(origin.add(-1.2 * c - 0.5 * s, -1.2 * s + 0.5 * c + 0.01, 0));
        // A box rides a slope on its lower front edge, so settle onto it before measuring.
        for (int i = 0; i < 3; i++) pig.move(MoverType.SELF, new Vec3(0.12, -0.0784, 0));
        double x0 = pig.getX(), y0 = pig.getY();
        for (int i = 0; i < 10; i++) pig.move(MoverType.SELF, new Vec3(0.12, -0.0784, 0));
        double progress = pig.getX() - x0, rise = pig.getY() - y0;
        test.assertTrue(progress > 10 * 0.12 * 0.95, "Walking up a slope must keep the horizontal speed, got " + progress);
        test.assertTrue(Math.abs(rise - progress * Math.tan(tilt)) < 0.08 && pig.onGround(),
                "The creature must follow the surface up: rise " + rise + ", ground " + pig.onGround());
        pig.setPos(origin.add(0.4 * c - 0.5 * s, 0.4 * s + 0.5 * c + 0.01, 0));
        double slideStart = pig.getX();
        for (int i = 0; i < 10; i++) pig.move(MoverType.SELF, new Vec3(0, -0.0784, 0));
        test.assertTrue(Math.abs(pig.getX() - slideStart) < 0.02, "Standing on a slope must not slide down it");
        pig.discard();
        craft.discard();
        test.succeed();
    }

    @GameTest(template = "test_pad")
    public static void creatureWalkingIntoAHullPushesIt(GameTestHelper test) {
        Vec3 origin = origin(test);
        CraftEntity craft = craft(test, origin, planks(BlockPos.ZERO));
        var pig = test.spawn(net.minecraft.world.entity.EntityType.PIG, 0, 0, 0);
        pig.setPos(origin.add(1.2, -0.5, 0));
        for (int i = 0; i < 5; i++) pig.move(MoverType.SELF, new Vec3(-0.15, 0, 0));
        test.assertTrue(craft.pendingPushes() > 0, "A creature blocked by the hull wall must push it");
        test.assertTrue(pig.getX() - origin.x > 0.5 + 0.45 - 0.01, "The creature must still be stopped by the wall");
        pig.discard();
        craft.discard();
        test.succeed();
    }

    private static Vec3 origin(GameTestHelper test) {
        return Vec3.atCenterOf(test.absolutePos(new BlockPos(5, 6, 5)));
    }

    private static CraftBlock planks(BlockPos offset) {
        return new CraftBlock(offset, Blocks.OAK_PLANKS.defaultBlockState());
    }

    private static CraftEntity craft(GameTestHelper test, Vec3 origin, CraftBlock... blocks) {
        CraftEntity craft = StructureTransfer.create(test.getLevel(), List.of(blocks), origin, UUID.randomUUID());
        test.assertTrue(craft != null, "Could not spawn test hull");
        return craft;
    }

    private static ItemEntity item(GameTestHelper test, Vec3 pos) {
        ItemEntity item = new ItemEntity(test.getLevel(), pos.x, pos.y, pos.z, new ItemStack(Items.FLINT), 0, 0, 0);
        test.assertTrue(test.getLevel().addFreshEntity(item), "Could not spawn test item");
        return item;
    }

    private static void pose(CraftEntity craft, Vec3 position, Quaternionf rotation) {
        double[] state = craft.nativeState();
        state[0] = position.x;
        state[1] = position.y;
        state[2] = position.z;
        state[3] = rotation.x;
        state[4] = rotation.y;
        state[5] = rotation.z;
        state[6] = rotation.w;
        craft.applyNative(state, 0);
    }

    private static void packet(ServerPlayer player, Vec3 pos, boolean onGround) {
        player.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(pos.x, pos.y, pos.z, onGround));
    }

    private static void near(GameTestHelper test, Entity entity, Vec3 expected, String message) {
        test.assertTrue(entity.position().distanceToSqr(expected) < 1.0e-8,
                message + ": expected " + expected + ", got " + entity.position());
    }
}
