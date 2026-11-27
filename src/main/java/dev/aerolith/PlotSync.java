package dev.aerolith;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Keeps clients' copies of a craft's plot chunks current: a full snapshot when a player starts to see a craft, then only
 * the blocks that changed. The plot dimension has no players, so vanilla's own chunk and block-update packets reach nobody.
 */
@EventBusSubscriber(modid = Aerolith.ID)
final class PlotSync {
    static final int MAX_DELTA = 2048;

    static volatile Consumer<Chunk> onChunk = chunk -> {};
    static volatile Consumer<Delta> onDelta = delta -> {};
    static volatile Consumer<Event> onEvent = event -> {};

    private PlotSync() {}

    static void register(PayloadRegistrar registrar) {
        registrar.playToClient(Chunk.TYPE, Chunk.CODEC, (packet, context) -> context.enqueueWork(() -> onChunk.accept(packet)));
        registrar.playToClient(Delta.TYPE, Delta.CODEC, (packet, context) -> context.enqueueWork(() -> onDelta.accept(packet)));
        registrar.playToClient(Event.TYPE, Event.CODEC, (packet, context) -> context.enqueueWork(() -> onEvent.accept(packet)));
    }

    @SubscribeEvent
    public static void startTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof CraftEntity craft && event.getEntity() instanceof ServerPlayer player) {
            sendFull(craft, player);
        }
    }

    static void sendFull(CraftEntity craft, ServerPlayer player) {
        if (player.connection == null || !player.connection.hasChannel(Chunk.TYPE)) return;
        ServerLevel plot = craft.plotLevel();
        if (plot == null || craft.plotId() < 0) return;
        List<Long> keys = new ArrayList<>(craft.chunkKeys());
        for (int i = 0; i < keys.size(); i++) {
            long key = keys.get(i);
            LevelChunk chunk = plot.getChunk(ChunkPos.getX(key), ChunkPos.getZ(key));
            player.connection.send(new Chunk(craft.getId(), craft.plotOrigin(), keys.size() - 1 - i,
                    ChunkPos.getX(key), ChunkPos.getZ(key), new ClientboundLevelChunkPacketData(chunk)));
        }
    }

    static void sendFullToTrackers(CraftEntity craft) {
        ServerLevel plot = craft.plotLevel();
        if (plot == null || craft.plotId() < 0) return;
        List<Long> keys = new ArrayList<>(craft.chunkKeys());
        for (int i = 0; i < keys.size(); i++) {
            long key = keys.get(i);
            LevelChunk chunk = plot.getChunk(ChunkPos.getX(key), ChunkPos.getZ(key));
            sendToTrackers(craft, new Chunk(craft.getId(), craft.plotOrigin(), keys.size() - 1 - i,
                    ChunkPos.getX(key), ChunkPos.getZ(key), new ClientboundLevelChunkPacketData(chunk)));
        }
    }

    /** Sends the blocks of {@code craft} that changed since the last call. */
    static void sendChanges(CraftEntity craft, List<Long> changed) {
        ServerLevel plot = craft.plotLevel();
        if (plot == null || changed.isEmpty()) return;
        if (changed.size() > MAX_DELTA) {
            sendFullToTrackers(craft);
            return;
        }
        List<Entry> entries = new ArrayList<>(changed.size());
        for (long packed : changed) {
            BlockPos pos = BlockPos.of(packed);
            BlockEntity entity = plot.getBlockEntity(pos);
            CompoundTag tag = entity == null ? null : entity.getUpdateTag(plot.registryAccess());
            entries.add(new Entry(packed, Block.getId(plot.getBlockState(pos)),
                    entity == null ? -1 : BuiltInRegistries.BLOCK_ENTITY_TYPE.getId(entity.getType()), tag));
        }
        sendToTrackers(craft, new Delta(craft.getId(), entries));
    }

    static void sendEvent(CraftEntity craft, BlockPos pos, int type, int data) {
        sendToTrackers(craft, new Event(craft.getId(), pos.asLong(), type, data));
    }

    static void sendToTrackers(CraftEntity craft, CustomPacketPayload payload) {
        if (craft.level() instanceof ServerLevel level) {
            level.getChunkSource().sendToTrackingPlayersFiltered(craft, new ClientboundCustomPayloadPacket(payload),
                    player -> player.connection.hasChannel(payload));
        }
    }

    /** One plot chunk in vanilla's format; {@code remaining} counts the chunks still to come for this craft. */
    record Chunk(int craft, BlockPos origin, int remaining, int chunkX, int chunkZ, ClientboundLevelChunkPacketData data)
            implements CustomPacketPayload {
        static final Type<Chunk> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Aerolith.ID, "plot_chunk"));
        static final StreamCodec<RegistryFriendlyByteBuf, Chunk> CODEC = StreamCodec.of((buffer, chunk) -> {
            buffer.writeVarInt(chunk.craft);
            buffer.writeLong(chunk.origin.asLong());
            buffer.writeVarInt(chunk.remaining);
            buffer.writeInt(chunk.chunkX);
            buffer.writeInt(chunk.chunkZ);
            ClientboundLevelChunkPacketData.STREAM_CODEC.encode(buffer, chunk.data);
        }, buffer -> {
            int craft = buffer.readVarInt();
            BlockPos origin = BlockPos.of(buffer.readLong());
            int remaining = buffer.readVarInt();
            int x = buffer.readInt();
            int z = buffer.readInt();
            return new Chunk(craft, origin, remaining, x, z, ClientboundLevelChunkPacketData.STREAM_CODEC.decode(buffer));
        });

        @Override
        public Type<Chunk> type() {
            return TYPE;
        }
    }

    record Entry(long pos, int state, int entityType, @Nullable CompoundTag tag) {}

    record Delta(int craft, List<Entry> entries) implements CustomPacketPayload {
        static final Type<Delta> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Aerolith.ID, "plot_delta"));
        static final StreamCodec<RegistryFriendlyByteBuf, Delta> CODEC = StreamCodec.of((buffer, delta) -> {
            buffer.writeVarInt(delta.craft);
            buffer.writeVarInt(delta.entries.size());
            for (Entry entry : delta.entries) {
                buffer.writeLong(entry.pos);
                buffer.writeVarInt(entry.state);
                buffer.writeVarInt(entry.entityType + 1);
                if (entry.entityType >= 0) buffer.writeNbt(entry.tag);
            }
        }, buffer -> {
            int craft = buffer.readVarInt();
            int count = buffer.readVarInt();
            List<Entry> entries = new ArrayList<>(Math.min(count, MAX_DELTA));
            for (int i = 0; i < count; i++) {
                long pos = buffer.readLong();
                int state = buffer.readVarInt();
                int type = buffer.readVarInt() - 1;
                entries.add(new Entry(pos, state, type, type >= 0 ? buffer.readNbt() : null));
            }
            return new Delta(craft, entries);
        });

        @Override
        public Type<Delta> type() {
            return TYPE;
        }
    }

    /** A block event (a chest lid, a piston) of a plot block, which clients replay on their copy. */
    record Event(int craft, long pos, int kind, int data) implements CustomPacketPayload {
        static final Type<Event> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Aerolith.ID, "plot_event"));
        static final StreamCodec<RegistryFriendlyByteBuf, Event> CODEC = StreamCodec.of((buffer, event) -> {
            buffer.writeVarInt(event.craft).writeLong(event.pos).writeVarInt(event.kind).writeVarInt(event.data);
        }, buffer -> new Event(buffer.readVarInt(), buffer.readLong(), buffer.readVarInt(), buffer.readVarInt()));

        @Override
        public Type<Event> type() {
            return TYPE;
        }
    }
}
