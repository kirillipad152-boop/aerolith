package dev.aerolith;

import com.mojang.brigadier.Command;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/** {@code /aerolithperf}: how long drawing the crafts has taken per call since the last time, then starts over. */
@EventBusSubscriber(modid = Aerolith.ID, value = Dist.CLIENT)
final class ClientPerf {
    private ClientPerf() {}

    @SubscribeEvent
    public static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("aerolithperf").executes(context -> {
            String report = Perf.report("client.");
            context.getSource().sendSuccess(() -> Component.literal(report.isEmpty() ? "No crafts were drawn" : report), false);
            Perf.reset("client.");
            return Command.SINGLE_SUCCESS;
        }));
    }
}
