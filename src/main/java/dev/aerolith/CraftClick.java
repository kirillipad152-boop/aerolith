package dev.aerolith;

import java.util.Map;
import java.util.WeakHashMap;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.joml.Quaternionf;

/**
 * A click on a craft block. The client says which block and face it saw. A moving craft is shown a few ticks behind the
 * server's, so repeating the ray on the server often misses what the player really aimed at; the server therefore
 * accepts the client's choice when that block exists and is within reach, and only falls back to its own ray otherwise.
 */
public record CraftClick(boolean attack, boolean offHand, int craft, BlockPos offset, Direction face, Vec3 localHit)
        implements CustomPacketPayload {
    private static final Type<CraftClick> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Aerolith.ID, "craft_click"));
    private static final StreamCodec<ByteBuf, CraftClick> CODEC = StreamCodec.ofMember(CraftClick::write, CraftClick::read);
    private static final Map<ServerPlayer, Long> LAST = new WeakHashMap<>();
    // The craft may have moved on since the client drew it.
    private static final double SLACK = 2.5;

    static void register(PayloadRegistrar registrar) {
        registrar.playToServer(TYPE, CODEC, CraftClick::receive);
    }

    private void write(ByteBuf buffer) {
        buffer.writeByte((attack ? 1 : 0) | (offHand ? 2 : 0));
        buffer.writeInt(craft);
        buffer.writeLong(offset.asLong());
        buffer.writeByte(face.get3DDataValue());
        buffer.writeFloat((float) localHit.x).writeFloat((float) localHit.y).writeFloat((float) localHit.z);
    }

    private static CraftClick read(ByteBuf buffer) {
        int bits = buffer.readUnsignedByte();
        int craft = buffer.readInt();
        BlockPos offset = BlockPos.of(buffer.readLong());
        Direction face = Direction.from3DDataValue(buffer.readUnsignedByte());
        Vec3 hit = new Vec3(buffer.readFloat(), buffer.readFloat(), buffer.readFloat());
        return new CraftClick((bits & 1) != 0, (bits & 2) != 0, craft, offset, face, hit);
    }

    private static void receive(CraftClick click, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || player.isSpectator()) return;
        long now = player.level().getGameTime();
        Long previous = LAST.put(player, now);
        if (previous != null && previous == now) return;
        if (!TowStaffItem.held(player).isEmpty()) return;
        if (!Double.isFinite(click.localHit.x + click.localHit.y + click.localHit.z)) return;

        var eye = player.getEyePosition();
        double reach = player.blockInteractionRange();
        CraftEditing.Located aimed = claimed(player, click, reach);
        if (aimed == null) aimed = CraftEditing.nearest(player.level(), eye, player.getViewVector(1f), reach, 1f, CraftEditing.aimsAtFluid(player));
        if (aimed == null) return;
        HitResult world = player.pick(reach, 1f, false);
        // Something solid in front of the craft keeps the click for the world.
        if (world.getType() == HitResult.Type.BLOCK && world.getLocation().distanceTo(eye) < aimed.target().pick().distance() - 0.3) return;

        if (click.attack) {
            CraftEditing.hit(player, aimed.craft(), aimed.target());
        } else {
            aimed.craft().interact(player, click.offHand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, aimed.target());
        }
    }

    /** The client's choice, if the block is really there and the player could plausibly reach it. */
    static CraftEditing.Located claimed(ServerPlayer player, CraftClick click, double reach) {
        if (!(player.level().getEntity(click.craft) instanceof CraftEntity craft) || craft.isRemoved()) return null;
        CraftBlock block = craft.blocks().stream().filter(b -> b.offset().equals(click.offset)).findFirst().orElse(null);
        if (block == null) {
            // The client drew a block the server does not have: send the hull again so they agree.
            PlotSync.sendFull(craft, player);
            return null;
        }
        Quaternionf rotation = craft.orientation(1f);
        Vec3 hit = craft.position().add(Hull.rotate(rotation, click.localHit));
        double distance = player.getEyePosition().distanceTo(hit);
        if (distance > reach + SLACK) return null;
        Vec3 localView = Hull.rotate(rotation.conjugate(), player.getViewVector(1f));
        return new CraftEditing.Located(craft,
                new CraftEditing.Target(new Hull.Pick(block, click.face, distance, null), click.localHit, localView));
    }

    @Override
    public Type<CraftClick> type() {
        return TYPE;
    }
}
