package dev.aerolith;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = Aerolith.ID, value = Dist.CLIENT)
public final class AerolithClient {
    private AerolithClient() {}

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Aerolith.CRAFT.get(), CraftRenderer::new);
    }
}
