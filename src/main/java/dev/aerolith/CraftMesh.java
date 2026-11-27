package dev.aerolith;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import org.joml.Vector3f;

/** Caches each plot section's tessellated geometry and rebuilds only edited or relit sections. */
public final class CraftMesh {
    private static final int EDGE = 16;
    private static final long LIGHT_BUDGET = 1_500_000;
    private static final long BUILD_BUDGET = 8_000_000;
    private static final int RELIGHT_TICKS = 5;
    private static final int LIGHT_PROBES = 32;
    private static final int LIGHT_PROBES_IN_ALL = 768;
    private static final Map<CraftEntity, CraftMesh> MESHES = new IdentityHashMap<>();
    private static int epoch;
    private static int ticks;
    private static boolean built;

    private final CraftEntity craft;
    private final Long2ObjectOpenHashMap<Section> sections = new Long2ObjectOpenHashMap<>();
    private final RandomSource probes = RandomSource.create();
    private int madeIn = -1;

    private CraftMesh(CraftEntity craft) { this.craft = craft; }

    private static final class Section {
        final BlockPos corner;
        boolean stale = true;
        boolean relight;
        int madeAt;
        List<Geometry> geometry = List.of();
        long[] litCells = {};
        int[] litAs = {};

        Section(BlockPos corner) { this.corner = corner; }
    }

    private record Quad(float x, float y, float z, BakedQuad model, QuadInstance light) {}
    private record FluidVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {}
    record Geometry(BlockPos offset, RenderType layer, List<Quad> quads, List<FluidVertex> fluid) {
        void submit(PoseStack stack, SubmitNodeCollector collector) {
            stack.pushPose();
            stack.translate(offset.getX() - 0.5, offset.getY() - 0.5, offset.getZ() - 0.5);
            // shortcut: vanilla batches the cached geometry each frame; use a persistent GPU path if large craft rendering needs it.
            collector.submitCustomGeometry(stack, layer, (pose, out) -> {
                var local = pose.copy();
                for (Quad quad : quads) {
                    local.pose().set(pose.pose()).translate(quad.x, quad.y, quad.z);
                    out.putBakedQuad(local, quad.model, quad.light);
                }
                var position = new Vector3f();
                var normal = new Vector3f();
                for (FluidVertex vertex : fluid) {
                    pose.pose().transformPosition(vertex.x, vertex.y, vertex.z, position);
                    pose.transformNormal(vertex.nx, vertex.ny, vertex.nz, normal);
                    out.addVertex(position.x, position.y, position.z, vertex.color, vertex.u, vertex.v,
                            vertex.overlay, vertex.light, normal.x, normal.y, normal.z);
                }
            });
            stack.popPose();
        }
    }

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
        MESHES.remove(craft);
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
            MESHES.keySet().removeIf(craft -> craft.isRemoved() || craft.level() != level);
            PlotLevel plot = PlotClient.shadow();
            if (plot == null || MESHES.isEmpty()) return;
            int each = Math.max(2, Math.min(LIGHT_PROBES, LIGHT_PROBES_IN_ALL / MESHES.size()));
            for (CraftMesh mesh : MESHES.values()) mesh.probeLight(plot, each);
        }

        @SubscribeEvent
        public static void frame(ExtractLevelRenderStateEvent event) {
            built = false;
            spent = 0;
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
        section.geometry = List.of();
        section.litCells = new long[0];
        section.litAs = new int[0];
        LevelChunk chunk = plot.chunkAt(section.corner);
        if (chunk == null) return;
        int index = chunk.getSectionIndex(section.corner.getY());
        if (index < 0 || index >= chunk.getSectionsCount()) return;
        LevelChunkSection blocks = chunk.getSection(index);
        if (blocks.hasOnlyAir()) return;
        plot.view(craft);
        var minecraft = Minecraft.getInstance();
        var models = minecraft.getModelManager();
        var renderer = new ModelBlockRenderer(minecraft.options.ambientOcclusion().get(), true, minecraft.getBlockColors());
        var fluids = new FluidRenderer(models.getFluidStateModelSet());
        var quads = new EnumMap<ChunkSectionLayer, List<Quad>>(ChunkSectionLayer.class);
        var fluid = new EnumMap<ChunkSectionLayer, FluidBuilder>(ChunkSectionLayer.class);
        for (int y = 0; y < EDGE; y++) for (int z = 0; z < EDGE; z++) for (int x = 0; x < EDGE; x++) {
            var state = blocks.getBlockState(x, y, z);
            if (state.isAir()) continue;
            BlockPos pos = section.corner.offset(x, y, z);
            var liquid = state.getFluidState();
            if (!liquid.isEmpty()) fluids.tesselate(plot, pos, layer -> fluid.computeIfAbsent(layer, k -> new FluidBuilder()), state, liquid);
            if (state.getRenderShape() != net.minecraft.world.level.block.RenderShape.MODEL) continue;
            renderer.tesselateBlock((dx, dy, dz, quad, lighting) -> {
                QuadInstance copy = new QuadInstance();
                copy.setOverlayCoords(lighting.overlayCoords());
                for (int i = 0; i < 4; i++) {
                    copy.setColor(i, lighting.getColor(i));
                    copy.setLightCoords(i, lighting.getLightCoords(i));
                }
                quads.computeIfAbsent(quad.materialInfo().layer(), key -> new ArrayList<>()).add(new Quad(dx, dy, dz, quad, copy));
            }, x, y, z, plot, pos, state, models.getBlockStateModelSet().get(state), state.getSeed(pos));
        }
        List<Geometry> geometry = new ArrayList<>();
        BlockPos offset = section.corner.subtract(craft.plotOrigin());
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            var modelsForLayer = quads.getOrDefault(layer, List.of());
            var fluidsForLayer = fluid.containsKey(layer) ? fluid.get(layer).vertices : List.<FluidVertex>of();
            if (modelsForLayer.isEmpty() && fluidsForLayer.isEmpty()) continue;
            RenderType type = switch (layer) {
                case SOLID -> RenderTypes.solidMovingBlock();
                case CUTOUT -> RenderTypes.cutoutMovingBlock();
                case TRANSLUCENT -> RenderTypes.translucentMovingBlock();
            };
            geometry.add(new Geometry(offset, type, modelsForLayer, fluidsForLayer));
        }
        section.geometry = geometry;
        Long2IntMap lit = plot.lightsRead();
        section.litCells = lit.keySet().toLongArray();
        section.litAs = new int[section.litCells.length];
        for (int i = 0; i < section.litCells.length; i++) section.litAs[i] = lit.get(section.litCells[i]);
    }

    static List<Geometry> extract(CraftEntity craft, PlotLevel plot) {
        CraftMesh mesh = MESHES.computeIfAbsent(craft, CraftMesh::new);
        mesh.update(plot);
        return mesh.sections.values().stream().flatMap(section -> section.geometry.stream()).toList();
    }

    /** The vanilla fluid renderer writes complete vertices. */
    private static final class FluidBuilder implements VertexConsumer {
        private final List<FluidVertex> vertices = new ArrayList<>();
        @Override public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            vertices.add(new FluidVertex(x, y, z, color, u, v, overlay, light, nx, ny, nz));
        }
        @Override public VertexConsumer addVertex(float x, float y, float z) { throw new UnsupportedOperationException(); }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { throw new UnsupportedOperationException(); }
        @Override public VertexConsumer setColor(int color) { throw new UnsupportedOperationException(); }
        @Override public VertexConsumer setUv(float u, float v) { throw new UnsupportedOperationException(); }
        @Override public VertexConsumer setUv1(int u, int v) { throw new UnsupportedOperationException(); }
        @Override public VertexConsumer setUv2(int u, int v) { throw new UnsupportedOperationException(); }
        @Override public VertexConsumer setUv3(float u, float v) { throw new UnsupportedOperationException(); }
        @Override public VertexConsumer setNormal(float x, float y, float z) { throw new UnsupportedOperationException(); }
        @Override public VertexConsumer setLineWidth(float width) { throw new UnsupportedOperationException(); }
    }
}
