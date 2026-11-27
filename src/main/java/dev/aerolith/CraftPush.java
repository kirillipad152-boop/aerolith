package dev.aerolith;

import java.util.Map;
import java.util.WeakHashMap;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * The local player leaning on a craft. Walking is simulated on the client, so only it knows how hard the player is
 * pressing; the server bounds the push by the player's weight and by how near the player really is.
 */
public record CraftPush(int craft, Vec3 point, Vec3 direction, float strength) implements CustomPacketPayload {
    private static final Type<CraftPush> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Aerolith.ID, "craft_push"));
    private static final StreamCodec<ByteBuf, CraftPush> CODEC = StreamCodec.ofMember(CraftPush::write, CraftPush::read);
    private static final Map<ServerPlayer, Long> LAST = new WeakHashMap<>();

    static void register(PayloadRegistrar registrar) {
        registrar.playToServer(TYPE, CODEC, CraftPush::receive);
    }

    private void write(ByteBuf buffer) {
        buffer.writeInt(craft);
        buffer.writeDouble(point.x).writeDouble(point.y).writeDouble(point.z);
        buffer.writeDouble(direction.x).writeDouble(direction.y).writeDouble(direction.z);
        buffer.writeFloat(strength);
    }

    private static CraftPush read(ByteBuf buffer) {
        return new CraftPush(buffer.readInt(), new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()), buffer.readFloat());
    }

    private static boolean finite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    private static void receive(CraftPush push, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || player.isSpectator()) return;
        if (!finite(push.point) || !finite(push.direction) || !(push.strength > 0f && push.strength <= 1f)) return;
        long now = player.level().getGameTime();
        Long previous = LAST.put(player, now);
        if (previous != null && previous == now) return;
        if (!(player.level().getEntity(push.craft) instanceof CraftEntity craft) || craft.isRemoved()
                || push.direction.lengthSqr() < 1e-6) return;
        if (player.distanceToSqr(push.point) > 16 || !craft.getBoundingBox().inflate(2).contains(push.point)) return;
        Vec3 impulse = push.direction.normalize().scale(push.strength * CraftLoads.pushImpulse(player));
        craft.queuePush(push.point, impulse);
    }

    @Override
    public Type<CraftPush> type() {
        return TYPE;
    }
}
