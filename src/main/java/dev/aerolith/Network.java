package dev.aerolith;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Registers the packets between client and server. */
public final class Network {
    private Network() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("8");
        CraftClick.register(registrar);
        TowStaffInput.register(registrar);
        CraftPush.register(registrar);
        CraftCrack.register(registrar);
        PlotSync.register(registrar);
    }
}
