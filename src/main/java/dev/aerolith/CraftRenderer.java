package dev.aerolith;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Quaternionf;

final class CraftRenderer extends EntityRenderer<CraftEntity> {
    private final BlockRenderDispatcher blocks;

    CraftRenderer(EntityRendererProvider.Context context) {
        super(context);
        blocks = context.getBlockRenderDispatcher();
    }

    @Override
    public void render(CraftEntity craft, float yaw, float partialTick, PoseStack pose,
            MultiBufferSource buffers, int packedLight) {
        Quaternionf rotation = craft.orientation(partialTick);
        Vec3 position = craft.getPosition(partialTick);
        pose.pushPose();
        pose.mulPose(rotation);
        long start = Perf.begin();
        PlotClient.render(craft, partialTick, pose, buffers);
        Perf.end("client.craft-blocks", start);
        var cracks = CraftClient.cracks(craft);
        for (var crack : cracks.entrySet()) {
            BlockPos offset = crack.getKey();
            var state = craft.cells().get(offset);
            if (state == null) continue;
            pose.pushPose();
            pose.translate(offset.getX() - 0.5, offset.getY() - 0.5, offset.getZ() - 0.5);
            VertexConsumer decal = new SheetedDecalTextureGenerator(
                    buffers.getBuffer(ModelBakery.DESTROY_TYPES.get(crack.getValue())), pose.last(), 1.0f);
            blocks.renderSingleBlock(state, pose, type -> decal, lightAround(craft.level(), position.add(Hull.rotate(rotation, Vec3.atLowerCornerOf(offset)))),
                    OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
            pose.popPose();
        }
        if (Minecraft.getInstance().getEntityRenderDispatcher().shouldRenderHitBoxes()) {
            debug(craft, pose, buffers);
        }
        pose.popPose();
        super.render(craft, yaw, partialTick, pose, buffers, packedLight);
    }

    /**
     * Light for one block. A block's own cell can be solid (a craft resting in sand) and then reads as dark, so the
     * brightest of the cell and its neighbours is used.
     */
    private static int lightAround(Level level, Vec3 center) {
        BlockPos origin = BlockPos.containing(center);
        int block = 0;
        int sky = 0;
        int light = LevelRenderer.getLightColor(level, origin);
        block = LightTexture.block(light);
        sky = LightTexture.sky(light);
        for (Direction face : Direction.values()) {
            light = LevelRenderer.getLightColor(level, origin.relative(face));
            block = Math.max(block, LightTexture.block(light));
            sky = Math.max(sky, LightTexture.sky(light));
        }
        return LightTexture.pack(block, sky);
    }

    /** Shown with the vanilla hitbox toggle (F3+B): exact block shapes, collider boxes, and the center of mass. */
    private static void debug(CraftEntity craft, PoseStack pose, MultiBufferSource buffers) {
        Hull hull = craft.hull();
        if (hull == null) return;
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (AABB box : craft.collisionBoxes()) {
            LevelRenderer.renderLineBox(pose, lines, box, 1f, 1f, 1f, 1f);
        }
        double[] merged = hull.displayBoxes();
        for (int i = 0; i < merged.length; i += Hull.COLLIDER) {
            AABB box = new AABB(merged[i] - merged[i + 3], merged[i + 1] - merged[i + 4], merged[i + 2] - merged[i + 5],
                    merged[i] + merged[i + 3], merged[i + 1] + merged[i + 4], merged[i + 2] + merged[i + 5]).inflate(0.01);
            LevelRenderer.renderLineBox(pose, lines, box, 0.2f, 0.9f, 1f, 1f);
        }
        Vec3 com = hull.localCom;
        LevelRenderer.renderLineBox(pose, lines, AABB.ofSize(com, 0.16, 0.16, 0.16), 1f, 1f, 0f, 1f);
        // The axes reach out of the hull so the point can be read even when blocks cover it.
        AABB bounds = hull.localBounds;
        double ax = Math.max(Math.abs(bounds.minX - com.x), Math.abs(bounds.maxX - com.x)) + 0.7;
        double ay = Math.max(Math.abs(bounds.minY - com.y), Math.abs(bounds.maxY - com.y)) + 0.7;
        double az = Math.max(Math.abs(bounds.minZ - com.z), Math.abs(bounds.maxZ - com.z)) + 0.7;
        double thin = 0.015;
        LevelRenderer.renderLineBox(pose, lines, new AABB(com.x - ax, com.y - thin, com.z - thin, com.x + ax, com.y + thin, com.z + thin), 1f, 0.1f, 0.1f, 1f);
        LevelRenderer.renderLineBox(pose, lines, new AABB(com.x - thin, com.y - ay, com.z - thin, com.x + thin, com.y + ay, com.z + thin), 0.1f, 1f, 0.1f, 1f);
        LevelRenderer.renderLineBox(pose, lines, new AABB(com.x - thin, com.y - thin, com.z - az, com.x + thin, com.y + thin, com.z + az), 0.3f, 0.4f, 1f, 1f);
    }

    @Override
    public ResourceLocation getTextureLocation(CraftEntity craft) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
