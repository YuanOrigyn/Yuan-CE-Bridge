package com.ceclientbridge.protocol;

import java.util.Set;

/** Pure compatibility checks shared by the server and client integration layers. */
public final class BridgeHandshake {

    /** Build targets this bridge ships for - adding one here is what makes a new Minecraft version reachable. */
    private static final Set<String> KNOWN_TARGETS = Set.of("26.x", "26.1", "1.21.11");

    private BridgeHandshake() {
    }

    public static BridgeNegotiation negotiate(BridgeHello server, BridgeHello client) {
        if (server.protocolVersion() != client.protocolVersion()
                || server.protocolVersion() != BridgeProtocol.CURRENT_VERSION) {
            return new BridgeNegotiation(false, "protocol version mismatch: server="
                    + server.protocolVersion() + ", client=" + client.protocolVersion());
        }
        if (!targetsCompatible(server.minecraftTarget(), client.minecraftTarget())) {
            return new BridgeNegotiation(false, "Minecraft target mismatch: server="
                    + server.minecraftTarget() + ", client=" + client.minecraftTarget());
        }
        int missing = server.capabilities() & ~client.capabilities();
        if (missing != 0) {
            return new BridgeNegotiation(false, "missing bridge capability bits: " + missing);
        }
        return new BridgeNegotiation(true, "compatible");
    }

    /**
     * A client and a server do not have to run the same Minecraft version: the bridge only carries
     * appearance payloads, and this already accepted a 1.21.11 client against a 26.x server, so every
     * target this build ships for is accepted against every other one. Unknown targets are still
     * rejected, so a stale or third-party build fails loudly instead of connecting and silently
     * syncing nothing.
     */
    private static boolean targetsCompatible(String serverTarget, String clientTarget) {
        if (serverTarget.equals(clientTarget)) {
            return true;
        }
        return KNOWN_TARGETS.contains(serverTarget) && KNOWN_TARGETS.contains(clientTarget);
    }
}
