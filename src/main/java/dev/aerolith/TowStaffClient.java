package dev.aerolith;

import java.util.ArrayDeque;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterDebugRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** Local staff controls and feedback; the server alone picks a craft and moves it. */
@EventBusSubscriber(modid = Aerolith.ID, value = Dist.CLIENT)
public final class TowStaffClient {
    private static final int CYAN = 0xff53e4ff;
    private static final int ORANGE = 0xffff923e;
    private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Identifier.fromNamespaceAndPath(Aerolith.ID, "staff"));
    private static final KeyMapping ROTATE = new KeyMapping("key.aerolith.rotate_staff", InputConstants.KEY_TAB, CATEGORY);
    private static final ArrayDeque<TowStaffInput> PRESSES = new ArrayDeque<>();
    private static ClientLevel inputLevel;
    private static LocalPlayer inputPlayer;
    private static ItemStack inputStaff;
    private static CraftEntity adjusting;
    private static Vec3 grip;
    private static float distance, yaw, pitch;

    private TowStaffClient() {}

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(ROTATE);
    }

    static boolean holdsStaff(Player player) {
        return player != null && !TowStaffItem.held(player).isEmpty();
    }

    static boolean intercept(InputEvent.InteractionKeyMappingTriggered event) {
        if ((!event.isAttack() && !event.isUseItem()) || !holdsStaff(Minecraft.getInstance().player)) return false;
        event.setCanceled(true);
        event.setSwingHand(false);
        return true;
    }

    @SubscribeEvent
    public static void click(InputEvent.MouseButton.Post event) {
        if (event.getAction() == InputConstants.PRESS) press(InputConstants.Type.MOUSE.getOrCreate(event.getButton()));
    }

    @SubscribeEvent
    public static void key(InputEvent.Key event) {
        if (event.getAction() == InputConstants.PRESS) press(InputConstants.getKey(event.getKeyEvent()));
    }

    private static void press(InputConstants.Key key) {
        Minecraft client = Minecraft.getInstance();
        if (!checkControl(client)) return;
        var attack = client.options.keyAttack;
        var use = client.options.keyUse;
        TowStaffInput action;
        if (attack.isActiveAndMatches(key) && attack.consumeClick()) action = TowStaffInput.PIN;
        else if (use.isActiveAndMatches(key) && use.consumeClick()) action = TowStaffInput.TOGGLE;
        else return;
        clearAdjustment();
        PRESSES.addLast(action);
    }

    @SubscribeEvent
    public static void scroll(InputEvent.MouseScrollingEvent event) {
        int steps = event.getAccumulatedScrollY();
        if (steps == 0) return;
        Minecraft client = Minecraft.getInstance();
        float delta = 1.5f * steps * (client.options.keySprint.isDown() ? 3 : 1);
        if (queue(client, delta, 0, 0)) event.setCanceled(true);
    }

    public static boolean turn(double mouseX, double mouseY) {
        if (!ROTATE.isDown() || (mouseX == 0 && mouseY == 0)) return false;
        // The wrapped turn receives sensitivity- and inversion-adjusted mouse input in vanilla's turn units.
        float radians = 0.15f * Mth.DEG_TO_RAD;
        return queue(Minecraft.getInstance(), 0, (float) (-mouseX * radians), (float) (mouseY * radians));
    }

    private static boolean queue(Minecraft client, float scroll, float turnYaw, float turnPitch) {
        if (!checkControl(client) || !inputPlayer.isCreative()) return false;
        for (CraftEntity craft : HullCollisions.crafts(inputLevel)) {
            if (craft.isRemoved() || craft.staffHolder() != inputPlayer.getId()) continue;
            if (PRESSES.isEmpty()) {
                Vec3 point = craft.staffPoint();
                if (craft != adjusting || !point.equals(grip)) {
                    clearAdjustment();
                    adjusting = craft;
                    grip = point;
                }
                distance += scroll;
                yaw += turnYaw;
                pitch += turnPitch;
            }
            return true;
        }
        clearAdjustment();
        return false;
    }

    private static boolean checkControl(Minecraft client) {
        LocalPlayer player = client.player;
        ItemStack staff = player != null ? TowStaffItem.held(player) : ItemStack.EMPTY;
        boolean inControl = player != null && client.level != null && client.level == player.level() && !staff.isEmpty()
                && player.isAlive() && !player.isSpectator() && !player.isPassenger()
                && client.gui.screen() == null && client.gui.overlay() == null && client.isWindowActive() && client.mouseHandler.isMouseGrabbed();
        if (!inControl || player != inputPlayer || client.level != inputLevel || staff != inputStaff) {
            PRESSES.clear();
            clearAdjustment();
            inputPlayer = inControl ? player : null;
            inputLevel = inControl ? client.level : null;
            inputStaff = inControl ? staff : null;
        }
        if (inControl && !player.isCreative()) clearAdjustment();
        return inControl;
    }

    @SubscribeEvent
    public static void flush(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (!checkControl(client)) return;
        // Let vanilla send the selected slot and view before the server resolves a staff press.
        TowStaffInput action = PRESSES.pollFirst();
        if (action != null) {
            ClientPacketDistributor.sendToServer(action);
        } else if (adjusting != null && !adjusting.isRemoved() && adjusting.level() == inputLevel
                && adjusting.staffHolder() == inputPlayer.getId() && grip.equals(adjusting.staffPoint())
                && (distance != 0 || yaw != 0 || pitch != 0)) {
            ClientPacketDistributor.sendToServer(TowStaffInput.adjust(distance, yaw, pitch));
        }
        clearAdjustment();
    }

    private static void clearAdjustment() {
        adjusting = null;
        grip = null;
        distance = yaw = pitch = 0;
    }

    @SubscribeEvent
    public static void renderer(RegisterDebugRenderersEvent event) {
        event.register(client -> (x, y, z, values, frustum, partialTick) -> draw(client, partialTick));
    }

    private static void draw(Minecraft client, float partialTick) {
        ClientLevel level = client.level;
        LocalPlayer player = client.player;
        if (level == null || player == null) return;
        boolean holding = false;
        for (CraftEntity craft : HullCollisions.crafts(level)) {
            int id = craft.staffHolder();
            if (id < 0 || craft.isRemoved() || !(level.getEntity(id) instanceof Player holder) || !holdsStaff(holder)) continue;
            holding |= holder == player;
            Vec3 tip = staffTip(client, holder, partialTick);
            Vec3 end = craft.getPosition(partialTick).add(Hull.rotate(craft.orientation(partialTick), craft.staffPoint()));
            if (tip.distanceToSqr(end) > 1.0e-6) Gizmos.line(tip, end, CYAN, 2f);
        }
        if (holding || !holdsStaff(player) || player.isSpectator() || client.gui.hud.isHidden()) return;
        var aimed = TowStaffItem.aimed(player, partialTick);
        if (aimed == null) return;
        CraftEntity craft = aimed.craft();
        int color = craft.anchored() ? ORANGE : CYAN;
        var rotation = craft.orientation(partialTick);
        Vec3 position = craft.getPosition(partialTick);
        Shapes.create(aimed.target().pick().box().inflate(0.005)).forAllEdges((ax, ay, az, bx, by, bz) ->
                Gizmos.line(position.add(Hull.rotate(rotation, new Vec3(ax, ay, az))),
                        position.add(Hull.rotate(rotation, new Vec3(bx, by, bz))), color, 2f));
    }

    private static Vec3 staffTip(Minecraft client, Player holder, float partialTick) {
        HumanoidArm arm = TowStaffItem.held(holder) == holder.getMainHandItem() ? holder.getMainArm() : holder.getMainArm().getOpposite();
        int side = arm == HumanoidArm.RIGHT ? 1 : -1;
        var camera = client.gameRenderer.mainCamera();
        if (camera.entity() == holder && !camera.isDetached()) {
            return camera.position().add(camera.getNearPlane(camera.getFov()).getPointOnPlane(side * 0.62f, -0.48f).scale(14));
        }
        float bodyYaw = -Mth.lerp(partialTick, holder.yBodyRotO, holder.yBodyRot) * Mth.DEG_TO_RAD;
        return holder.getEyePosition(partialTick).add(new Vec3(-0.34 * side, -0.46, 0.42).scale(holder.getScale()).yRot(bodyYaw));
    }
}
