package dev.aerolith;

import java.util.Map;
import java.util.WeakHashMap;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Staff buttons and bounded input deltas, never a client-selected entity, position or rotation. */
record TowStaffInput(int action, float distance, float yaw, float pitch) implements CustomPacketPayload {
    static final TowStaffInput TOGGLE = new TowStaffInput(0, 0, 0, 0);
    static final TowStaffInput PIN = new TowStaffInput(1, 0, 0, 0);
    static final int ADJUST = 2;
    private static final Type<TowStaffInput> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Aerolith.ID, "staff_input"));
    private static final StreamCodec<ByteBuf, TowStaffInput> CODEC = StreamCodec.of(
            (buf, input) -> buf.writeByte(input.action).writeFloat(input.distance).writeFloat(input.yaw).writeFloat(input.pitch),
            buf -> new TowStaffInput(buf.readUnsignedByte(), buf.readFloat(), buf.readFloat(), buf.readFloat()));
    private static final Map<ServerPlayer, Long> LAST_CLICK = new WeakHashMap<>();
    private static final Map<ServerPlayer, Long> LAST_ADJUST = new WeakHashMap<>();

    static TowStaffInput adjust(float distance, float yaw, float pitch) {
        return new TowStaffInput(ADJUST, distance, yaw, pitch);
    }

    boolean valid() {
        return action >= TOGGLE.action && action <= ADJUST
                && Float.isFinite(distance) && Float.isFinite(yaw) && Float.isFinite(pitch);
    }

    static void register(PayloadRegistrar registrar) {
        registrar.playToServer(TYPE, CODEC, (input, context) -> {
            if (!(context.player() instanceof ServerPlayer player) || TowStaffItem.held(player).isEmpty() || !input.valid()) return;
            long now = player.level().getGameTime();
            Long previous = (input.action == ADJUST ? LAST_ADJUST : LAST_CLICK).put(player, now);
            if (previous == null || previous != now) WorldPhysics.of(player.level()).staffInput(player, input);
        });
    }

    @Override
    public Type<TowStaffInput> type() {
        return TYPE;
    }
}
