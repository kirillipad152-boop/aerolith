package dev.aerolith;

import java.util.ArrayList;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.world.phys.Vec3;
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
            var type = client.getConnection().registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE)
                    .get(ResourceKey.create(Registries.DIMENSION_TYPE, Plots.DIMENSION_ID));
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
        chunk.replaceWithPacketData(packet.chunkX(), packet.chunkZ(), packet.data());
        craft.chunkKeys().add(ChunkPos.pack(packet.chunkX(), packet.chunkZ()));
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
                        entity.handleUpdateTag(net.minecraft.world.level.storage.TagValueInput.create(
                                net.minecraft.util.ProblemReporter.DISCARDING, registries, entry.tag()));
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

    static java.util.List<BlockEntityRenderState> extractBlockEntities(CraftEntity craft, PlotLevel plot, float partialTick) {
        var client = Minecraft.getInstance();
        var dispatcher = client.getBlockEntityRenderDispatcher();
        var states = new ArrayList<BlockEntityRenderState>();
        plot.view(craft);
        Vec3 camera = PlotGrid.toPlot(craft.plotOrigin(), Hull.rotate(craft.orientation(partialTick).conjugate(),
                client.gameRenderer.mainCamera().position().subtract(craft.getPosition(partialTick))));
        for (long key : craft.chunkKeys()) {
            LevelChunk chunk = plot.chunkAt(key);
            if (chunk == null) continue;
            for (BlockEntity entity : chunk.getBlockEntities().values()) {
                var renderer = dispatcher.getRenderer(entity);
                if (renderer != null && renderer.shouldRender(entity, camera)) states.add(extract(renderer, entity, partialTick, camera));
            }
        }
        return states;
    }

    private static <S extends BlockEntityRenderState> S extract(BlockEntityRenderer<BlockEntity, S> renderer,
            BlockEntity entity, float partialTick, Vec3 camera) {
        S state = renderer.createRenderState();
        renderer.extractRenderState(entity, state, partialTick, camera, null);
        return state;
    }
}
