package dev.aerolith;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record PilotInput(int bits) implements CustomPacketPayload {
    public static final int FORWARD = 1;
    public static final int BACK = 2;
    public static final int LEFT = 4;
    public static final int RIGHT = 8;
    public static final int THROTTLE_UP = 16;
    public static final int THROTTLE_DOWN = 32;
    public static final int ROLL_LEFT = 64;
    public static final int ROLL_RIGHT = 128;
    public static final int FLAPS_DOWN = 256;
    public static final int FLAPS_UP = 512;
    static final int VALID_BITS = FORWARD | BACK | LEFT | RIGHT | THROTTLE_UP | THROTTLE_DOWN | ROLL_LEFT | ROLL_RIGHT | FLAPS_DOWN
            | FLAPS_UP;
    private static final Type<PilotInput> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Aerolith.ID, "pilot_input"));
    private static final StreamCodec<ByteBuf, PilotInput> CODEC = StreamCodec.ofMember(
            (input, buffer) -> buffer.writeShort(input.bits()), buffer -> new PilotInput(buffer.readUnsignedShort()));

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("7");
        registrar.playToServer(TYPE, CODEC, PilotInput::receive);
        CraftClick.register(registrar);
        CraftPush.register(registrar);
        CraftCrack.register(registrar);
        PlotSync.register(registrar);
    }

    private static void receive(PilotInput input, IPayloadContext context) {
        if ((input.bits() & ~VALID_BITS) == 0 && context.player() instanceof ServerPlayer player
                && player.getVehicle() instanceof CraftEntity craft) {
            craft.acceptPilotInput(player, input.bits());
        }
    }

    @Override
    public Type<PilotInput> type() {
        return TYPE;
    }
}
