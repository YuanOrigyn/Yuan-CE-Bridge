package com.ceclientmod.net;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Loader-neutral facade over the only two client networking primitives the bridge needs: "may we send on
 * this channel" and "send this C2S payload". Fabric and NeoForge each implement {@link Transport} and
 * install it from their own entrypoint, which keeps every shared registry and probe class free of
 * loader imports.
 */
public final class ClientNetwork {

    /** One implementation per mod loader, installed before any payload can be sent. */
    public interface Transport {
        boolean canSendToServer(CustomPacketPayload.Type<?> type);

        void sendToServer(CustomPacketPayload payload);

        boolean isModLoaded(String modId);
    }

    private static Transport transport;

    private ClientNetwork() {
    }

    public static void install(Transport installed) {
        transport = installed;
    }

    public static boolean canSend(CustomPacketPayload.Type<?> type) {
        Transport current = transport;
        return current != null && current.canSendToServer(type);
    }

    public static void send(CustomPacketPayload payload) {
        Transport current = transport;
        if (current == null) throw new IllegalStateException("no client network transport installed");
        current.sendToServer(payload);
    }

    public static boolean isModLoaded(String modId) {
        Transport current = transport;
        return current != null && current.isModLoaded(modId);
    }
}