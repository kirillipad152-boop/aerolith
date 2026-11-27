package dev.aerolith;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = Aerolith.ID, value = Dist.CLIENT)
public final class AerolithClient {
    private static final String KEY_CATEGORY = "key.categories.aerolith";
    private static KeyMapping throttleUp;
    private static KeyMapping throttleDown;
    private static KeyMapping rollLeft;
    private static KeyMapping rollRight;
    private static KeyMapping flapsDown;
    private static KeyMapping flapsUp;
    private static int ticks;

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        throttleUp = new KeyMapping("key.aerolith.throttle_up", KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM, InputConstants.KEY_Z, KEY_CATEGORY);
        throttleDown = new KeyMapping("key.aerolith.throttle_down", KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM, InputConstants.KEY_X, KEY_CATEGORY);
        rollLeft = new KeyMapping("key.aerolith.roll_left", KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM, InputConstants.KEY_LEFT, KEY_CATEGORY);
        rollRight = new KeyMapping("key.aerolith.roll_right", KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM, InputConstants.KEY_RIGHT, KEY_CATEGORY);
        flapsDown = new KeyMapping("key.aerolith.flaps_down", KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM, InputConstants.KEY_G, KEY_CATEGORY);
        flapsUp = new KeyMapping("key.aerolith.flaps_up", KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM, InputConstants.KEY_H, KEY_CATEGORY);
        event.register(throttleUp);
        event.register(throttleDown);
        event.register(rollLeft);
        event.register(rollRight);
        event.register(flapsDown);
        event.register(flapsUp);
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Aerolith.CRAFT.get(), CraftRenderer::new);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if ((++ticks & 1) != 0) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || !(player.getVehicle() instanceof CraftEntity craft) || !craft.isControlledBy(player)) {
            return;
        }

        int bits = 0;
        if (client.screen == null && client.getOverlay() == null && client.isWindowActive() && !client.isPaused()) {
            Options keys = client.options;
            bits = (keys.keyUp.isDown() ? PilotInput.FORWARD : 0)
                    | (keys.keyDown.isDown() ? PilotInput.BACK : 0)
                    | (keys.keyLeft.isDown() ? PilotInput.LEFT : 0)
                    | (keys.keyRight.isDown() ? PilotInput.RIGHT : 0)
                    | (throttleUp.isDown() ? PilotInput.THROTTLE_UP : 0)
                    | (throttleDown.isDown() ? PilotInput.THROTTLE_DOWN : 0)
                    | (rollLeft.isDown() ? PilotInput.ROLL_LEFT : 0)
                    | (rollRight.isDown() ? PilotInput.ROLL_RIGHT : 0)
                    | (flapsDown.isDown() ? PilotInput.FLAPS_DOWN : 0)
                    | (flapsUp.isDown() ? PilotInput.FLAPS_UP : 0);
        }
        PacketDistributor.sendToServer(new PilotInput(bits));
    }
}
