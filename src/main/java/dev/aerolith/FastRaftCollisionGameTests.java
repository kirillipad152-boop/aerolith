package dev.aerolith;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

public final class FastRaftCollisionGameTests {
    @SuppressWarnings("removal")
    public static void fastRaftPushesPlayersWithoutTunneling(GameTestHelper test) {
        Vec3 origin = origin(test);
        for (GameType mode : new GameType[]{GameType.SURVIVAL, GameType.CREATIVE}) {
            CraftEntity craft = craft(test, origin, planks(BlockPos.ZERO));
            ServerPlayer player = test.makeMockServerPlayerInLevel();
            try {
                player.setGameMode(mode);
                ackTeleport(player, 1);
                player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());

                Vec3 standing = origin.add(2.0, -0.5, 0.0);
                player.setPos(standing);
                player.connection.resetPosition();

                // Move 3.5 m/tick (70 m/s): swept thickness < single-tick step, and lag sends pre-impact pos.
                pose(craft, origin.add(3.5, 0.0, 0.0), new Quaternionf());
                test.assertTrue(player.getX() >= origin.x + 3.5 + 0.5 + 0.3 - 1.0e-4,
                        mode + " player must be pushed in front of fast raft, got x=" + player.getX());

                // Simulate a stale client packet from before the server pushed the player.
                player.connection.resetPosition();
                packet(player, standing, false);
                test.assertTrue(player.getX() >= origin.x + 3.5 + 0.5 + 0.3 - 1.0e-4,
                        mode + " stale client packet must not teleport player through fast raft, got x=" + player.getX());
            } finally {
                player.discard();
                craft.discard();
            }
        }
        test.succeed();
    }

    @SuppressWarnings("removal")
    public static void fastSpinningRaftBeyondOldStepCapPushesPlayer(GameTestHelper test) {
        Vec3 origin = origin(test);
        CraftEntity craft = craft(test, origin,
                planks(new BlockPos(-1, 0, 0)),
                planks(BlockPos.ZERO),
                planks(new BlockPos(1, 0, 0)),
                planks(new BlockPos(2, 0, 0)));
        ServerPlayer player = test.makeMockServerPlayerInLevel();
        try {
            ackTeleport(player, 1);
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            Vec3 standing = origin.add(3.5, -0.5, 0.0);
            player.setPos(standing);
            player.connection.resetPosition();

            // Travel > 4.0 m/tick (which used to exceed MAX_POSE_STEPS = 16 and abort carry/push).
            pose(craft, origin.add(5.0, 0.0, 0.0), new Quaternionf().rotationY(0.35f));
            test.assertTrue(player.getX() > origin.x + 5.0,
                    "Fast multi-block craft (>4 m/tick) must push player ahead instead of passing through, got x=" + player.getX());
        } finally {
            player.discard();
            craft.discard();
        }
        test.succeed();
    }

    @SuppressWarnings("removal")
    public static void tiltedFastRaftPushesGroundedPlayerForward(GameTestHelper test) {
        BlockPos floorRel = new BlockPos(6, 5, 5);
        for (int dx = -2; dx <= 4; dx++) {
            test.setBlock(floorRel.offset(dx, 0, 0), Blocks.STONE);
        }
        Vec3 origin = Vec3.atCenterOf(test.absolutePos(new BlockPos(5, 6, 5)));
        CraftEntity craft = craft(test, origin, planks(BlockPos.ZERO));
        // Nose pitched slightly forward-down (-20 deg about Z -> front face normal has y < 0).
        Quaternionf pitched = new Quaternionf().rotationZ(-0.35f);
        pose(craft, origin, pitched);

        ServerPlayer player = test.makeMockServerPlayerInLevel();
        try {
            ackTeleport(player, 1);
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            double floorTop = test.absolutePos(floorRel).getY() + 1.0;
            Vec3 standing = new Vec3(origin.x + 1.5, floorTop, origin.z);
            player.setPos(standing);
            player.setOnGround(true);
            player.connection.resetPosition();

            pose(craft, origin.add(2.0, 0.0, 0.0), pitched);
            test.assertTrue(player.getX() >= origin.x + 2.5,
                    "Tilted fast raft must push grounded player horizontally rather than passing through, got x=" + player.getX());
        } finally {
            player.discard();
            craft.discard();
        }
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

    private static void ackTeleport(ServerPlayer player, int id) {
        player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(id,
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
    }

    private static void packet(ServerPlayer player, Vec3 pos, boolean onGround) {
        player.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(pos, onGround, false));
        player.connection.handleClientTickEnd(ServerboundClientTickEndPacket.INSTANCE);
    }
}
