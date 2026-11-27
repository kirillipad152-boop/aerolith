package dev.aerolith;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** Aims at the real block shapes of crafts instead of their bounding boxes. */
@EventBusSubscriber(modid = Aerolith.ID, value = Dist.CLIENT)
public final class CraftClient {
    private record Crack(CraftCrack update, long expires) {}

    private static final Map<Integer, Crack> CRACKS = new HashMap<>();
    private static ClientLevel crackLevel;

    private CraftClient() {}

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        HullCollisions.pushSink = (craft, point, direction, strength) ->
                ClientPacketDistributor.sendToServer(new CraftPush(craft.getId(), point, direction, (float) strength));
    }

    /** The craft block under the crosshair, unless something nearer in the world or a mob is in the way. */
    static CraftEditing.Located aimed(float partialTick) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || client.player.isSpectator()) return null;
        Vec3 eye = client.player.getEyePosition(partialTick);
        CraftEditing.Located located = CraftEditing.nearest(client.level, eye, client.player.getViewVector(partialTick),
                client.player.blockInteractionRange(), partialTick, CraftEditing.aimsAtFluid(client.player));
        if (located == null) return null;
        HitResult vanilla = client.hitResult;
        boolean boundingBoxOnly = vanilla instanceof EntityHitResult hit && hit.getEntity() instanceof CraftEntity;
        if (vanilla != null && vanilla.getType() != HitResult.Type.MISS && !boundingBoxOnly
                && vanilla.getLocation().distanceTo(eye) < located.target().pick().distance() - 0.001) {
            return null;
        }
        return located;
    }

    /** Vanilla targets a craft by its box; when no real block is there the terrain behind should be targeted. */
    private static void correctTarget(float partialTick) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && client.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof CraftEntity
                && aimed(partialTick) == null) {
            client.hitResult = client.player.pick(client.player.blockInteractionRange(), partialTick, false);
        }
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Pre event) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level != crackLevel) {
            CRACKS.clear();
            crackLevel = level;
        }
        if (level != null && !CRACKS.isEmpty()) {
            CRACKS.values().removeIf(crack -> crack.expires() <= level.getGameTime()
                    || !(level.getEntity(crack.update().craft()) instanceof CraftEntity));
        }
        if (client.gui.screen() == null && client.player != null && client.player.getUseItem().is(Aerolith.TOW_STAFF.get())) {
            // Vanilla discards attack presses during item use; the staff pins the held craft on that press.
            while (client.options.keyAttack.consumeClick()) attackTapped = true;
        }
        correctTarget(1f);
    }

    static void showCrack(CraftCrack update) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || level != crackLevel) return;
        if (update.stage() < 0 || update.stage() > 9) {
            CRACKS.remove(update.breaker());
        } else {
            // The server renews unchanged stages every four ticks; releases don't need another packet.
            CRACKS.put(update.breaker(), new Crack(update, level.getGameTime() + 8));
        }
    }

    static Map<BlockPos, Integer> cracks(CraftEntity craft) {
        Map<BlockPos, Integer> stages = null;
        for (Crack crack : CRACKS.values()) {
            CraftCrack update = crack.update();
            if (update.craft() != craft.getId()) continue;
            if (stages == null) stages = new HashMap<>();
            stages.merge(update.offset(), update.stage(), Math::max);
        }
        return stages == null ? Map.of() : stages;
    }

    @SubscribeEvent
    public static void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack() && !event.isUseItem() && !event.isPickBlock()) return;
        Minecraft client = Minecraft.getInstance();
        float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        correctTarget(partial);
        CraftEditing.Located aimed = aimed(partial);
        if (aimed == null) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        if (event.isPickBlock()) {
            send(aimed, false, false, true);
            return;
        }
        if (event.isAttack()) {
            // Attacking is sent from the tick below so that a held button keeps mining and a tap is not counted twice.
            attackTapped = true;
            return;
        }
        client.player.swing(event.getHand(), net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
        send(aimed, false, event.getHand() == InteractionHand.OFF_HAND, false);
    }

    private static boolean attackTapped;
    private static boolean attackDown;

    private static void send(CraftEditing.Located aimed, boolean attack, boolean offHand, boolean pick) {
        CraftEditing.Target target = aimed.target();
        ClientPacketDistributor.sendToServer(new CraftClick(attack, offHand, pick, aimed.craft().getId(), target.pick().block().offset(),
                target.pick().face(), target.localHit()));
    }

    /** Holding the attack button on a craft block keeps hitting it, one hit per tick, like mining a block. */
    @SubscribeEvent
    public static void hold(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        boolean tapped = attackTapped;
        attackTapped = false;
        boolean down = client.options.keyAttack.isDown();
        boolean pressed = down ? !attackDown : tapped;
        attackDown = down;
        if (client.player == null || client.gui.screen() != null || (!tapped && !down)) return;
        if (client.player.getMainHandItem().is(Aerolith.TOW_STAFF.get())) {
            if (pressed && (client.player.isUsingItem() || aimed(1f) != null)) ClientPacketDistributor.sendToServer(CraftClick.STAFF_ANCHOR);
            return;
        }
        CraftEditing.Located aimed = aimed(1f);
        if (aimed == null) return;
        if (!client.player.isSwinging()) client.player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
        send(aimed, true, false, false);
    }
}
