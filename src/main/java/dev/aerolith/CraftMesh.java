package dev.aerolith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.RenderTypeHelper;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The blocks of a craft as they lie on the graphics card: one mesh for each 16-block section of its plot, made once and drawn
 * wherever the craft is. A section is made again when a block in or beside it changes, or the light around the craft does.
 */
public final class CraftMesh {
    private static final int EDGE = 16;
    /** Time for sections whose light changed, per frame; at least one is made. New and edited ones are not put off so soon. */
    private static final long LIGHT_BUDGET = 1_500_000;
    private static final long BUILD_BUDGET = 8_000_000;
    private static final int RELIGHT_TICKS = 5;
    private static final int LIGHT_PROBES = 32;
    private static final int LIGHT_PROBES_IN_ALL = 768;

    private static final Map<CraftEntity, CraftMesh> MESHES = new IdentityHashMap<>();
    private static final List<Glass> GLASS = new ArrayList<>();
    private static final ByteBufferBuilder[] SCRATCH = new ByteBufferBuilder[2];
    private static ByteBufferBuilder sorting;
    private static ShaderInstance shader;
    private static int epoch;
    private static int ticks;
    private static long frame;
    private static long frameStart;
    private static boolean built;

    private final CraftEntity craft;
    private final Long2ObjectOpenHashMap<Section> sections = new Long2ObjectOpenHashMap<>();
    private final RandomSource probes = RandomSource.create();
    private int madeIn = -1;

    private CraftMesh(CraftEntity craft) {
        this.craft = craft;
    }

    private static final class Section {
        final BlockPos corner;
        boolean stale = true;
        boolean relight;
        int madeAt;
        VertexBuffer solid;
        VertexBuffer glass;
        MeshData.SortState order;
        Vector3f sortedFrom;
        long[] litCells = {};
        int[] litAs = {};

        Section(BlockPos corner) {
            this.corner = corner;
        }

        void close() {
            if (solid != null) solid.close();
            if (glass != null) glass.close();
            solid = glass = null;
            order = null;
        }
    }

    private record Glass(Section section, Matrix4f model) {}

    // ---- what changes

    /** The renderer was reset: options, resources or shaders changed. */
    public static void invalidateAll() {
        epoch++;
    }

    static void changed(CraftEntity craft, BlockPos pos) {
        CraftMesh mesh = MESHES.get(craft);
        if (mesh == null) return;
        for (int x = pos.getX() - 1 >> 4; x <= pos.getX() + 1 >> 4; x++) {
            for (int y = pos.getY() - 1 >> 4; y <= pos.getY() + 1 >> 4; y++) {
                for (int z = pos.getZ() - 1 >> 4; z <= pos.getZ() + 1 >> 4; z++) mesh.section(x, y, z).stale = true;
            }
        }
    }

    static void rescan(CraftEntity craft) {
        CraftMesh mesh = MESHES.get(craft);
        if (mesh != null) mesh.madeIn = -1;
    }

    static void release(CraftEntity craft) {
        CraftMesh mesh = MESHES.remove(craft);
        if (mesh != null) mesh.close();
    }

    private void close() {
        sections.values().forEach(Section::close);
        sections.clear();
    }

    private Section section(int x, int y, int z) {
        return sections.computeIfAbsent(SectionPos.asLong(x, y, z), key -> new Section(new BlockPos(x << 4, y << 4, z << 4)));
    }

    /** Only what the light of the world does to a craft is looked for here; changes of blocks are reported. */
    @EventBusSubscriber(modid = Aerolith.ID, value = Dist.CLIENT)
    static final class Events {
        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            ticks++;
            var level = Minecraft.getInstance().level;
            MESHES.entrySet().removeIf(entry -> {
                boolean gone = entry.getKey().isRemoved() || entry.getKey().level() != level;
                if (gone) entry.getValue().close();
                return gone;
            });
            PlotLevel plot = PlotClient.shadow();
            if (plot == null || MESHES.isEmpty()) return;
            int each = Math.max(2, Math.min(LIGHT_PROBES, LIGHT_PROBES_IN_ALL / MESHES.size()));
            for (CraftMesh mesh : MESHES.values()) mesh.probeLight(plot, each);
        }

        /** See-through blocks are drawn when everything solid is, crafts and creatures alike. */
        @SubscribeEvent
        public static void stage(RenderLevelStageEvent event) {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
                frame++;
                GLASS.clear();
            } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES && !GLASS.isEmpty()) {
                drawGlass();
            }
        }

        @SubscribeEvent
        public static void shaders(RegisterShadersEvent event) throws IOException {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(Aerolith.ID, "craft"), DefaultVertexFormat.NEW_ENTITY), loaded -> shader = loaded);
            invalidateAll();
        }
    }

    private void probeLight(PlotLevel plot, int count) {
        if (sections.isEmpty()) return;
        plot.view(craft);
        Section[] all = sections.values().toArray(Section[]::new);
        for (int i = 0; i < count; i++) {
            Section section = all[probes.nextInt(all.length)];
            if (section.relight || section.litCells.length == 0) continue;
            int cell = probes.nextInt(section.litCells.length);
            if (plot.light(section.litCells[cell]) != section.litAs[cell]) {
                // Light that reaches one block seldom stops there.
                for (Section other : all) other.relight = true;
                return;
            }
        }
    }

    // ---- making

    private void update(PlotLevel plot) {
        if (madeIn != epoch) {
            madeIn = epoch;
            var old = new Long2ObjectOpenHashMap<>(sections);
            sections.clear();
            for (long key : craft.chunkKeys()) {
                LevelChunk chunk = plot.chunkAt(key);
                if (chunk == null) continue;
                LevelChunkSection[] parts = chunk.getSections();
                for (int i = 0; i < parts.length; i++) {
                    if (parts[i].hasOnlyAir()) continue;
                    int y = chunk.getSectionYFromSectionIndex(i);
                    Section kept = old.remove(SectionPos.asLong(ChunkPos.getX(key), y, ChunkPos.getZ(key)));
                    if (kept == null) {
                        section(ChunkPos.getX(key), y, ChunkPos.getZ(key));
                    } else {
                        kept.stale = true;
                        sections.put(SectionPos.asLong(kept.corner), kept);
                    }
                }
            }
            old.values().forEach(Section::close);
        }
        if (frameStart != frame) {
            frameStart = frame;
            built = false;
            spent = 0;
        }
        for (Section section : sections.values()) {
            boolean light = section.relight && ticks - section.madeAt >= RELIGHT_TICKS;
            if (!section.stale && !light) continue;
            if (built && spent >= (section.stale ? BUILD_BUDGET : LIGHT_BUDGET)) continue;
            long start = System.nanoTime();
            make(section, plot);
            spent += System.nanoTime() - start;
            built = true;
        }
    }

    private static long spent;

    private void make(Section section, PlotLevel plot) {
        section.stale = section.relight = false;
        section.madeAt = ticks;
        section.litCells = new long[0];
        section.litAs = new int[0];
        BufferBuilder[] layers = new BufferBuilder[2];
        LevelChunk chunk = plot.chunkAt(section.corner);
        LevelChunkSection blocks = chunk == null ? null : chunk.getSection(chunk.getSectionIndex(section.corner.getY()));
        if (blocks != null && !blocks.hasOnlyAir()) {
            plot.view(craft);
            var dispatcher = Minecraft.getInstance().getBlockRenderer();
            RandomSource random = RandomSource.create(42);
            PoseStack pose = new PoseStack();
            for (int y = 0; y < EDGE; y++) {
                for (int z = 0; z < EDGE; z++) {
                    for (int x = 0; x < EDGE; x++) {
                        BlockState state = blocks.getBlockState(x, y, z);
                        if (state.isAir()) continue;
                        BlockPos pos = section.corner.offset(x, y, z);
                        var fluid = state.getFluidState();
                        if (!fluid.isEmpty()) {
                            boolean clear = ItemBlockRenderTypes.getRenderLayer(fluid) == RenderType.translucent();
                            dispatcher.renderLiquid(pos, plot, new Untinted(layer(layers, clear)), state, fluid);
                        }
                        if (state.getRenderShape() != RenderShape.MODEL) continue;
                        pose.pushPose();
                        pose.translate(x, y, z);
                        var model = dispatcher.getBlockModel(state);
                        random.setSeed(state.getSeed(pos));
                        for (var type : model.getRenderTypes(state, random, ModelData.EMPTY)) {
                            dispatcher.renderBatched(state, pos, plot, pose, layer(layers, type == RenderType.translucent()), true, random,
                                    ModelData.EMPTY, type);
                        }
                        pose.popPose();
                    }
                }
            }
            Long2IntMap lit = plot.lightsRead();
            section.litCells = lit.keySet().toLongArray();
            section.litAs = new int[section.litCells.length];
            for (int i = 0; i < section.litCells.length; i++) section.litAs[i] = lit.get(section.litCells[i]);
        }
        section.solid = upload(section.solid, layers[0] == null ? null : layers[0].build());
        MeshData glass = layers[1] == null ? null : layers[1].build();
        section.sortedFrom = null;
        section.order = glass == null ? null : glass.sortQuads(sorting(), VertexSorting.byDistance(0, 0, 0));
        section.glass = upload(section.glass, glass);
        VertexBuffer.unbind();
    }

    private static BufferBuilder layer(BufferBuilder[] layers, boolean clear) {
        int i = clear ? 1 : 0;
        if (layers[i] == null) {
            if (SCRATCH[i] == null) SCRATCH[i] = new ByteBufferBuilder(1 << 18);
            layers[i] = new BufferBuilder(SCRATCH[i], VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
        }
        return layers[i];
    }

    private static ByteBufferBuilder sorting() {
        if (sorting == null) sorting = new ByteBufferBuilder(1 << 16);
        return sorting;
    }

    private static VertexBuffer upload(VertexBuffer buffer, MeshData data) {
        if (data == null) {
            if (buffer != null) buffer.close();
            return null;
        }
        if (buffer == null) buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(data);
        return buffer;
    }

    // ---- drawing

    /** Draws the solid blocks of {@code craft} now and its see-through ones later; {@code pose} is the craft's, turned and all. */
    static void draw(CraftEntity craft, PlotLevel plot, PoseStack pose) {
        CraftMesh mesh = MESHES.computeIfAbsent(craft, CraftMesh::new);
        mesh.update(plot);
        RenderType type = null;
        BlockPos origin = craft.plotOrigin();
        for (Section section : mesh.sections.values()) {
            if (section.solid == null && section.glass == null) continue;
            Matrix4f model = new Matrix4f(pose.last().pose()).translate(section.corner.getX() - origin.getX() - 0.5f,
                    section.corner.getY() - origin.getY() - 0.5f, section.corner.getZ() - origin.getZ() - 0.5f);
            if (section.glass != null) GLASS.add(new Glass(section, model));
            if (section.solid == null) continue;
            if (type == null) {
                type = RenderTypeHelper.getEntityRenderType(RenderType.solid(), false);
                type.setupRenderState();
            }
            section.solid.bind();
            draw(section.solid, model);
        }
        if (type != null) {
            VertexBuffer.unbind();
            type.clearRenderState();
        }
    }

    private static void drawGlass() {
        GLASS.sort((a, b) -> Float.compare(distance(b.model), distance(a.model)));
        RenderType type = RenderTypeHelper.getEntityRenderType(RenderType.translucent(), false);
        type.setupRenderState();
        java.util.Set<Section> drawn = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Glass glass : GLASS) {
            Section section = glass.section;
            // A shadow pass lists the same sections once more.
            if (section.glass == null || !drawn.add(section)) continue;
            section.glass.bind();
            Vector3f eye = new Matrix4f(glass.model).invert().transformPosition(new Vector3f());
            if (section.order != null && (section.sortedFrom == null || section.sortedFrom.distanceSquared(eye) > 0.25f)) {
                section.sortedFrom = eye;
                var indices = section.order.buildSortedIndexBuffer(sorting(), VertexSorting.byDistance(eye));
                if (indices != null) section.glass.uploadIndexBuffer(indices);
            }
            draw(section.glass, glass.model);
        }
        VertexBuffer.unbind();
        type.clearRenderState();
        GLASS.clear();
    }

    private static float distance(Matrix4f model) {
        return model.transformPosition(8, 8, 8, new Vector3f()).lengthSquared();
    }

    private static void draw(VertexBuffer buffer, Matrix4f model) {
        Matrix4f view = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(model);
        ShaderInstance own = shader;
        if (own != null && !shaderPack()) {
            own.safeGetUniform("ModelMat").set(model);
            buffer.drawWithShader(view, RenderSystem.getProjectionMatrix(), own);
            return;
        }
        // A shader pack has programs of its own for entities, with fog of their own; vanilla's would measure it from the craft.
        float start = RenderSystem.getShaderFogStart();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        buffer.drawWithShader(view, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
        RenderSystem.setShaderFogStart(start);
    }

    private static final java.util.function.BooleanSupplier SHADER_PACK = shaderPackCheck();

    private static boolean shaderPack() {
        return SHADER_PACK.getAsBoolean();
    }

    private static java.util.function.BooleanSupplier shaderPackCheck() {
        if (!ModList.get().isLoaded("iris") && !ModList.get().isLoaded("oculus")) return () -> false;
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            var inUse = api.getMethod("isShaderPackInUse");
            return () -> {
                try {
                    return (boolean) inUse.invoke(instance);
                } catch (ReflectiveOperationException failure) {
                    return true;
                }
            };
        } catch (ReflectiveOperationException failure) {
            return () -> true;
        }
    }

    /** Gives vertices what the fluid renderer, made for terrain, leaves out. */
    private record Untinted(VertexConsumer out) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            out.addVertex(x, y, z);
            out.setOverlay(OverlayTexture.NO_OVERLAY);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            out.setColor(r, g, b, a);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            out.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            out.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            out.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            out.setNormal(x, y, z);
            return this;
        }
    }
}
