package dev.aerolith;

import java.util.ArrayList;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Client side of plot storage: receives the chunks of crafts, keeps them current, and draws them. */
@EventBusSubscriber(modid = Aerolith.ID, value = Dist.CLIENT)
final class PlotClient {
    private static PlotLevel shadow;

    private PlotClient() {}

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        PlotSync.onChunk = packet -> chunk(packet);
        PlotSync.onDelta = packet -> delta(packet);
        PlotSync.onEvent = packet -> event(packet);
        CraftEntity.clientRelease = craft -> release(craft);
        CraftEntity.clientBlocks = craft -> {
            PlotLevel plot = level(false);
            return plot == null ? net.minecraft.world.level.EmptyBlockGetter.INSTANCE : plot;
        };
    }

    private static PlotLevel level(boolean create) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.getConnection() == null) return null;
        if (shadow != null && shadow.owner != client.level) shadow = null;
        if (shadow == null && create) {
            var type = client.getConnection().registryAccess().registryOrThrow(Registries.DIMENSION_TYPE)
                    .getHolder(ResourceKey.create(Registries.DIMENSION_TYPE, Plots.DIMENSION_ID));
            if (type.isEmpty()) return null;
            shadow = new PlotLevel(client, client.getConnection(), type.get());
        }
        return shadow;
    }

    private static CraftEntity craft(int id) {
        var level = Minecraft.getInstance().level;
        return level != null && level.getEntity(id) instanceof CraftEntity craft ? craft : null;
    }

    static void chunk(PlotSync.Chunk packet) {
        CraftEntity craft = craft(packet.craft());
        PlotLevel plot = level(true);
        if (craft == null || plot == null) return;
        LevelChunk chunk = plot.put(packet.chunkX(), packet.chunkZ());
        chunk.replaceWithPacketData(packet.data().getReadBuffer(), packet.data().getHeightmaps(),
                packet.data().getBlockEntitiesTagsConsumer(packet.chunkX(), packet.chunkZ()));
        craft.chunkKeys().add(ChunkPos.asLong(packet.chunkX(), packet.chunkZ()));
        if (packet.remaining() == 0) {
            craft.clientRescan(packet.origin(), plot::chunkAt);
            CraftMesh.rescan(craft);
        }
    }

    static void delta(PlotSync.Delta packet) {
        CraftEntity craft = craft(packet.craft());
        PlotLevel plot = level(false);
        if (craft == null || plot == null) return;
        var registries = Minecraft.getInstance().level.registryAccess();
        for (PlotSync.Entry entry : packet.entries()) {
            BlockPos pos = BlockPos.of(entry.pos());
            LevelChunk chunk = plot.chunkAt(pos);
            if (chunk == null) {
                // The craft grew into a chunk the client has not been sent yet: start it empty rather than lose the block.
                chunk = plot.put(pos.getX() >> 4, pos.getZ() >> 4);
                craft.chunkKeys().add(PlotGrid.chunkKey(pos));
            }
            BlockState state = Block.stateById(entry.state());
            // Written into the section directly: the usual setter also feeds a light engine this level does not run.
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(pos.getY()));
            BlockState old = section.setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state, false);
            if (old.hasBlockEntity() && !old.is(state.getBlock())) chunk.removeBlockEntity(pos);
            if (state.hasBlockEntity()) {
                BlockEntity entity = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
                if (entity != null) {
                    entity.setBlockState(state);
                    if (entry.tag() != null && entity.getType() == BuiltInRegistries.BLOCK_ENTITY_TYPE.byId(entry.entityType())) {
                        entity.handleUpdateTag(entry.tag(), registries);
                    }
                }
            }
            craft.clientChanged(pos, state);
            CraftMesh.changed(craft, pos);
        }
    }

    static void event(PlotSync.Event packet) {
        PlotLevel plot = level(false);
        if (plot == null) return;
        BlockPos pos = BlockPos.of(packet.pos());
        BlockState state = plot.getBlockState(pos);
        state.triggerEvent(plot, pos, packet.kind(), packet.data());
    }

    static PlotLevel shadow() {
        return shadow;
    }

    static void release(CraftEntity craft) {
        CraftMesh.release(craft);
        PlotLevel plot = shadow;
        if (plot == null) return;
        for (long key : new ArrayList<>(craft.chunkKeys())) plot.drop(key);
        craft.chunkKeys().clear();
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        PlotLevel plot = level(false);
        if (plot != null && !plot.isEmpty()) plot.tickEntities();
    }

    // ---- drawing

    /** Draws every block, fluid and block entity of {@code craft}; false if its chunks have not arrived. */
    static boolean render(CraftEntity craft, float partialTick, PoseStack pose, MultiBufferSource buffers) {
        PlotLevel plot = level(false);
        if (plot == null || craft.hull() == null) return false;
        Minecraft client = Minecraft.getInstance();
        CraftMesh.draw(craft, plot, pose);
        plot.view(craft);
        BlockPos origin = craft.plotOrigin();
        for (long key : craft.chunkKeys()) {
            LevelChunk chunk = plot.chunkAt(key);
            if (chunk == null) continue;
            if (chunk.getBlockEntities().isEmpty()) continue;
            for (BlockEntity entity : new ArrayList<>(chunk.getBlockEntities().values())) {
                var renderer = client.getBlockEntityRenderDispatcher().getRenderer(entity);
                if (renderer == null) continue;
                BlockPos offset = entity.getBlockPos().subtract(origin);
                pose.pushPose();
                // Block entity renderers draw from the block's corner, like block models, so the same half-block shift applies.
                pose.translate(offset.getX() - 0.5, offset.getY() - 0.5, offset.getZ() - 0.5);
                renderer.render(entity, partialTick, pose, buffers, plot.light(entity.getBlockPos()), OverlayTexture.NO_OVERLAY);
                pose.popPose();
            }
        }
        return true;
    }
}
