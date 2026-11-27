package dev.aerolith;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Outlines craft blocks and draws staff tethers at the interpolated craft pose. */
@EventBusSubscriber(modid = Aerolith.ID, value = Dist.CLIENT)
public final class CraftHighlight {
    private CraftHighlight() {}

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || client.options.hideGui) return;
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        PoseStack pose = event.getPoseStack();
        Camera camera = event.getCamera();
        MultiBufferSource.BufferSource buffers = client.renderBuffers().bufferSource();
        VertexConsumer lines = null;
        boolean holding = false;
        for (CraftEntity craft : HullCollisions.crafts(client.level)) {
            int holderId = craft.staffHolder();
            if (holderId < 0 || craft.isRemoved() || craft.anchored()) continue;
            if (!(client.level.getEntity(holderId) instanceof Player holder)) continue;
            ItemStack staff = TowStaffItem.held(holder);
            if (staff.isEmpty()) continue;
            holding |= holder == client.player;
            if (lines == null) lines = buffers.getBuffer(RenderType.lines());
            Vec3 grip = craft.getPosition(partial).add(Hull.rotate(craft.orientation(partial), craft.staffPoint()));
            tether(lines, pose.last(), hand(holder, staff, camera, partial).subtract(camera.getPosition()),
                    grip.subtract(camera.getPosition()));
        }

        boolean staff = !TowStaffItem.held(client.player).isEmpty();
        CraftEditing.Located aimed = null;
        if (!staff) aimed = CraftClient.aimed(partial);
        else if (!holding && !client.player.isSpectator()) aimed = TowStaffItem.aimed(client.player, partial);
        if (aimed != null) {
            if (lines == null) lines = buffers.getBuffer(RenderType.lines());
            CraftEntity craft = aimed.craft();
            pose.pushPose();
            Vec3 offset = craft.getPosition(partial).subtract(camera.getPosition());
            pose.translate(offset.x, offset.y, offset.z);
            pose.mulPose(craft.orientation(partial));
            var box = aimed.target().pick().box().inflate(0.002);
            if (!staff) LevelRenderer.renderLineBox(pose, lines, box, 0f, 0f, 0f, 0.6f);
            else if (craft.anchored()) LevelRenderer.renderLineBox(pose, lines, box, 1f, 0.5f, 0.16f, 1f);
            else LevelRenderer.renderLineBox(pose, lines, box, 0.2f, 0.9f, 1f, 1f);
            pose.popPose();
        }
        if (lines != null) buffers.endBatch(RenderType.lines());
    }

    private static Vec3 hand(Player holder, ItemStack staff, Camera camera, float partial) {
        HumanoidArm arm = holder.getMainHandItem() == staff ? holder.getMainArm() : holder.getMainArm().getOpposite();
        int side = arm == HumanoidArm.RIGHT ? 1 : -1;
        // shortcut: approximate the hand; sample the rendered pose if custom hand animations need exact alignment.
        if (camera.getEntity() == holder && !camera.isDetached()) {
            return camera.getPosition().add(camera.getNearPlane().getPointOnPlane(side * 0.62f, -0.55f).scale(12));
        }
        float yaw = Mth.rotLerp(partial, holder.yBodyRotO, holder.yBodyRot) * Mth.DEG_TO_RAD;
        return holder.getPosition(partial).add(new Vec3(-side * 0.36, holder.isCrouching() ? 0.76 : 0.94, 0.18)
                .scale(holder.getScale()).yRot(-yaw));
    }

    private static void tether(VertexConsumer lines, PoseStack.Pose pose, Vec3 start, Vec3 end) {
        Vec3 direction = end.subtract(start).normalize();
        if (direction.lengthSqr() == 0.0) return;
        lines.addVertex(pose, (float) start.x, (float) start.y, (float) start.z).setColor(0xFF33E6FF)
                .setNormal(pose, (float) direction.x, (float) direction.y, (float) direction.z);
        lines.addVertex(pose, (float) end.x, (float) end.y, (float) end.z).setColor(0xFF33E6FF)
                .setNormal(pose, (float) direction.x, (float) direction.y, (float) direction.z);
    }
}
