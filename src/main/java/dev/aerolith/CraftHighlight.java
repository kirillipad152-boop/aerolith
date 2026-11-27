package dev.aerolith;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Outlines the craft block the player is aiming at, in the craft's rotated frame. */
@EventBusSubscriber(modid = Aerolith.ID, value = Dist.CLIENT)
public final class CraftHighlight {
    private CraftHighlight() {}

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.options.hideGui) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        CraftEditing.Located aimed = CraftClient.aimed(partial);
        if (aimed == null) return;
        CraftEntity craft = aimed.craft();
        CraftEditing.Target target = aimed.target();

        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(craft.getPosition(partial).x - camera.x, craft.getPosition(partial).y - camera.y,
                craft.getPosition(partial).z - camera.z);
        pose.mulPose(craft.orientation(partial));
        MultiBufferSource.BufferSource buffers = client.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(pose, lines, target.pick().box().inflate(0.002), 0f, 0f, 0f, 0.6f);
        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }
}
