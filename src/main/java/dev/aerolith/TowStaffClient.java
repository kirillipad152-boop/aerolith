package dev.aerolith;

import java.util.ArrayDeque;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Local input only: the server picks, owns and moves the craft. */
@EventBusSubscriber(modid = Aerolith.ID, value = Dist.CLIENT)
public final class TowStaffClient {
    private static final KeyMapping ROTATE = new KeyMapping("key.aerolith.rotate_staff", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_TAB, "key.categories.aerolith");
    private static final double RADIANS_PER_TURN_UNIT = Math.toRadians(0.15);
    private static final ArrayDeque<TowStaffInput> BUTTONS = new ArrayDeque<>();
    private record Controls(ClientLevel level, LocalPlayer player, ItemStack staff) {}

    private static Controls controls;
    private static CraftEntity adjustCraft;
    private static Vec3 adjustPoint;
    private static float distanceDelta, yawDelta, pitchDelta;

    private TowStaffClient() {}

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(ROTATE);
    }

    @SubscribeEvent
    public static void mouseButton(InputEvent.MouseButton.Post event) {
        if (event.getAction() == GLFW.GLFW_PRESS) press(InputConstants.Type.MOUSE.getOrCreate(event.getButton()));
    }

    @SubscribeEvent
    public static void key(InputEvent.Key event) {
        if (event.getAction() == GLFW.GLFW_PRESS) press(InputConstants.getKey(event.getKey(), event.getScanCode()));
    }

    private static void press(InputConstants.Key key) {
        Minecraft client = Minecraft.getInstance();
        if (!updateControls(client)) return;
        if (client.options.keyAttack.isDown() && client.options.keyAttack.isActiveAndMatches(key)) BUTTONS.add(TowStaffInput.PIN);
        else if (client.options.keyUse.isDown() && client.options.keyUse.isActiveAndMatches(key)) BUTTONS.add(TowStaffInput.TOGGLE);
    }

    static boolean intercept(Minecraft client, InputEvent.InteractionKeyMappingTriggered event) {
        if ((!event.isAttack() && !event.isUseItem()) || client.player == null || TowStaffItem.held(client.player).isEmpty()) return false;
        // Vanilla also calls this for held repeats, so only suppress here; press events send staff actions.
        event.setCanceled(true);
        event.setSwingHand(false);
        if (client.gameMode != null) client.gameMode.stopDestroyBlock();
        return true;
    }

    @SubscribeEvent
    public static void scroll(InputEvent.MouseScrollingEvent event) {
        Minecraft client = Minecraft.getInstance();
        float step = 1.5f * (client.options.keySprint.isDown() ? 3 : 1);
        if (adjust(client, (float) event.getScrollDeltaY() * step, 0, 0)) event.setCanceled(true);
    }

    public static boolean turn(double yaw, double pitch) {
        return ROTATE.isDown() && adjust(Minecraft.getInstance(), 0,
                (float) (-yaw * RADIANS_PER_TURN_UNIT), (float) (pitch * RADIANS_PER_TURN_UNIT));
    }

    @SubscribeEvent
    public static void tabList(RenderGuiLayerEvent.Pre event) {
        if (!VanillaGuiLayers.TAB_LIST.equals(event.getName()) || !ROTATE.isDown()) return;
        Minecraft client = Minecraft.getInstance();
        if (ROTATE.same(client.options.keyPlayerList) && canControl(client)
                && !TowStaffItem.held(client.player).isEmpty() && heldCraft(client) != null) event.setCanceled(true);
    }

    private static boolean adjust(Minecraft client, float distance, float yaw, float pitch) {
        if (!updateControls(client)) return false;
        CraftEntity craft = heldCraft(client);
        if (craft != adjustCraft) clearAdjustment();
        if (craft == null) return false;
        if (distance != 0 || yaw != 0 || pitch != 0) {
            Vec3 point = craft.staffPoint();
            if (!point.equals(adjustPoint)) clearAdjustment();
            adjustCraft = craft;
            adjustPoint = point;
            distanceDelta += distance;
            yawDelta += yaw;
            pitchDelta += pitch;
        }
        return true;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (!updateControls(client)) return;
        // The player has just sent its view to the server; buttons take priority over deltas for the old grip.
        TowStaffInput button = BUTTONS.poll();
        if (button != null) {
            PacketDistributor.sendToServer(button);
        } else if ((distanceDelta != 0 || yawDelta != 0 || pitchDelta != 0) && heldCraft(client) == adjustCraft
                && adjustPoint.equals(adjustCraft.staffPoint())) {
            PacketDistributor.sendToServer(TowStaffInput.adjust(distanceDelta, yawDelta, pitchDelta));
        }
        clearAdjustment();
    }

    private static boolean canControl(Minecraft client) {
        return client.level != null && client.gameMode != null && client.player != null && client.player.level() == client.level
                && client.player.isAlive() && !client.player.isSpectator() && !client.player.isPassenger()
                && client.screen == null && client.getOverlay() == null && client.isWindowActive() && client.mouseHandler.isMouseGrabbed();
    }

    private static boolean updateControls(Minecraft client) {
        ItemStack staff = canControl(client) ? TowStaffItem.held(client.player) : ItemStack.EMPTY;
        if (controls != null && controls.level() == client.level && controls.player() == client.player && controls.staff() == staff) return true;
        controls = staff.isEmpty() ? null : new Controls(client.level, client.player, staff);
        BUTTONS.clear();
        clearAdjustment();
        return controls != null;
    }

    private static CraftEntity heldCraft(Minecraft client) {
        int playerId = client.player.getId();
        for (CraftEntity craft : HullCollisions.crafts(client.level)) {
            if (!craft.isRemoved() && !craft.anchored() && craft.staffHolder() == playerId) return craft;
        }
        return null;
    }

    private static void clearAdjustment() {
        adjustCraft = null;
        adjustPoint = null;
        distanceDelta = yawDelta = pitchDelta = 0;
    }
}
