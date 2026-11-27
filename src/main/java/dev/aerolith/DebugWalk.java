package dev.aerolith;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Quaternionf;

/** Developer aid: -Daerolith.walkLog=/path logs the player's position in the nearest craft's frame every tick. */
@EventBusSubscriber(modid = Aerolith.ID, value = Dist.CLIENT)
public final class DebugWalk {
    private static final String FILE = System.getProperty("aerolith.walkLog");

    private DebugWalk() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (FILE == null || player == null || client.level == null) return;
        CraftEntity craft = client.level.getEntitiesOfClass(CraftEntity.class, player.getBoundingBox().inflate(8))
                .stream().findFirst().orElse(null);
        if (craft == null) return;
        Quaternionf q = craft.orientation(1f).conjugate();
        Vec3 d = player.position().subtract(craft.position());
        Vec3 local = Hull.rotate(q, d);
        String line = String.format("%d,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%d,%.5f,%.5f,%.5f%n", client.level.getGameTime(),
                local.x, local.y, local.z, player.getX(), player.getY(), player.getZ(), player.onGround() ? 1 : 0,
                craft.getX(), craft.getY(), craft.getZ());
        try {
            Files.writeString(Path.of(FILE), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // debug output only
        }
    }
}
