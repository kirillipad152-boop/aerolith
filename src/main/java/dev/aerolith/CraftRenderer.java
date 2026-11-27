package dev.aerolith;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.joml.Quaternionf;

final class CraftRenderer extends EntityRenderer<CraftEntity, CraftRenderer.State> {
    static final class State extends EntityRenderState {
        Quaternionf rotation;
        List<CraftMesh.Geometry> geometry = List.of();
        List<BlockEntityRenderState> blockEntities = List.of();
        List<Crack> cracks = new ArrayList<>();
        List<Outline> outlines = new ArrayList<>();
        BlockPos origin;
    }
    private record Crack(BlockPos offset, List<BlockStateModelPart> parts, int phase, boolean translucent) {}
    private record Outline(AABB box, int color) {}

    CraftRenderer(EntityRendererProvider.Context context) { super(context); }

    @Override
    public State createRenderState() { return new State(); }

    @Override
    public void extractRenderState(CraftEntity craft, State render, float partialTick) {
        super.extractRenderState(craft, render, partialTick);
        render.rotation = new Quaternionf(craft.orientation(partialTick));
        render.origin = craft.plotOrigin();
        var client = Minecraft.getInstance();
        PlotLevel plot = PlotClient.shadow();
        if (plot != null && craft.hull() != null) {
            long start = Perf.begin();
            render.geometry = CraftMesh.extract(craft, plot);
            render.blockEntities = PlotClient.extractBlockEntities(craft, plot, partialTick);
            Perf.end("client.craft-blocks", start);
            for (var entry : CraftClient.cracks(craft).entrySet()) {
                var offset = entry.getKey();
                var state = craft.cells().get(offset);
                if (state == null) continue;
                var pos = craft.plotPos(offset);
                var model = client.getModelManager().getBlockStateModelSet().get(state);
                var parts = new ArrayList<BlockStateModelPart>();
                model.collectParts(plot, pos, state, RandomSource.create(state.getSeed(pos)), parts);
                render.cracks.add(new Crack(offset, parts, entry.getValue(), model.hasMaterialFlag(plot, pos, state, BakedQuad.FLAG_TRANSLUCENT)));
            }
        }
        if (client.debugEntries.isCurrentlyEnabled(DebugScreenEntries.ENTITY_HITBOXES)) debug(craft, render.outlines);
        if (client.player != null && !TowStaffClient.holdsStaff(client.player) && !client.gui.hud.isHidden()
                && craft.getBoundingBox().inflate(client.player.blockInteractionRange())
                .contains(client.player.getEyePosition(partialTick))) {
            var aimed = CraftClient.aimed(partialTick);
            if (aimed != null && aimed.craft() == craft) render.outlines.add(new Outline(aimed.target().pick().box().inflate(0.002), 0x99000000));
        }
    }

    @Override
    public void submit(State render, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        super.submit(render, pose, collector, camera);
        pose.pushPose();
        pose.rotate(render.rotation);
        for (var geometry : render.geometry) geometry.submit(pose, collector);
        for (var entity : render.blockEntities) {
            pose.pushPose();
            translate(pose, entity.blockPos.subtract(render.origin));
            Minecraft.getInstance().getBlockEntityRenderDispatcher().submit(entity, pose, collector, camera);
            pose.popPose();
        }
        for (var crack : render.cracks) {
            pose.pushPose();
            translate(pose, crack.offset);
            collector.submitBreakingBlockModel(pose, crack.parts, crack.phase, crack.translucent);
            pose.popPose();
        }
        for (var outline : render.outlines) {
            collector.submitShapeOutline(pose, Shapes.create(outline.box), RenderTypes.linesTranslucent(), outline.color, 2f, false);
        }
        pose.popPose();
    }

    private static void translate(PoseStack pose, BlockPos offset) {
        pose.translate(offset.getX() - 0.5, offset.getY() - 0.5, offset.getZ() - 0.5);
    }

    /** Shown with the vanilla hitbox toggle: exact shapes, merged boxes and center of mass. */
    private static void debug(CraftEntity craft, List<Outline> boxes) {
        Hull hull = craft.hull();
        if (hull == null) return;
        for (AABB box : craft.collisionBoxes()) {
            boxes.add(new Outline(box, ARGB.colorFromFloat(1f, 1f, 1f, 1f)));
        }
        double[] merged = hull.displayBoxes();
        for (int i = 0; i < merged.length; i += Hull.COLLIDER) {
            AABB box = new AABB(merged[i] - merged[i + 3], merged[i + 1] - merged[i + 4], merged[i + 2] - merged[i + 5],
                    merged[i] + merged[i + 3], merged[i + 1] + merged[i + 4], merged[i + 2] + merged[i + 5]).inflate(0.01);
            boxes.add(new Outline(box, ARGB.colorFromFloat(1f, 0.2f, 0.9f, 1f)));
        }
        Vec3 com = hull.localCom;
        boxes.add(new Outline(AABB.ofSize(com, 0.16, 0.16, 0.16), ARGB.colorFromFloat(1f, 1f, 1f, 0f)));
        // The axes reach out of the hull so the point can be read even when blocks cover it.
        AABB bounds = hull.localBounds;
        double ax = Math.max(Math.abs(bounds.minX - com.x), Math.abs(bounds.maxX - com.x)) + 0.7;
        double ay = Math.max(Math.abs(bounds.minY - com.y), Math.abs(bounds.maxY - com.y)) + 0.7;
        double az = Math.max(Math.abs(bounds.minZ - com.z), Math.abs(bounds.maxZ - com.z)) + 0.7;
        double thin = 0.015;
        boxes.add(new Outline(new AABB(com.x - ax, com.y - thin, com.z - thin, com.x + ax, com.y + thin, com.z + thin), ARGB.colorFromFloat(1f, 1f, 0.1f, 0.1f)));
        boxes.add(new Outline(new AABB(com.x - thin, com.y - ay, com.z - thin, com.x + thin, com.y + ay, com.z + thin), ARGB.colorFromFloat(1f, 0.1f, 1f, 0.1f)));
        boxes.add(new Outline(new AABB(com.x - thin, com.y - thin, com.z - az, com.x + thin, com.y + thin, com.z + az), ARGB.colorFromFloat(1f, 0.3f, 0.4f, 1f)));
    }

}
