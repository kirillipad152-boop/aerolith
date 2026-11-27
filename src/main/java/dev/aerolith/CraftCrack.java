package dev.aerolith;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Destruction progress in craft-local coordinates, keyed by the player doing the mining. */
record CraftCrack(int breaker, int craft, BlockPos offset, int stage) implements CustomPacketPayload {
    private static final Type<CraftCrack> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Aerolith.ID, "craft_crack"));
    private static final StreamCodec<ByteBuf, CraftCrack> CODEC = StreamCodec.ofMember(
            (crack, buffer) -> buffer.writeInt(crack.breaker).writeInt(crack.craft).writeLong(crack.offset.asLong()).writeByte(crack.stage),
            buffer -> new CraftCrack(buffer.readInt(), buffer.readInt(), BlockPos.of(buffer.readLong()), buffer.readByte()));

    static void register(PayloadRegistrar registrar) {
        registrar.playToClient(TYPE, CODEC, (crack, context) -> CraftClient.showCrack(crack));
    }

    @Override
    public Type<CraftCrack> type() {
        return TYPE;
    }
}
