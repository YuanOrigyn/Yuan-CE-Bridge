package com.ceclientmod.neoforge;

import com.ceclientmod.BridgeClientCore;
import com.ceclientmod.net.BlockProbePayload;
import com.ceclientmod.net.BridgeChannels;
import com.ceclientmod.net.ChunkPayload;
import com.ceclientmod.net.ClientNetwork;
import com.ceclientmod.net.FurnitureProbePayload;
import com.ceclientmod.net.HelloPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * NeoForge client entrypoint. It runs the same bridge as the Fabric build: declare the channels, install
 * the NeoForge transport and forward every packet to {@link BridgeClientCore}. All bridge logic lives in
 * the shared com.ceclientmod sources, so both loaders behave identically.
 */
@Mod(value = CraftEngineNeoForgeMod.MOD_ID, dist = Dist.CLIENT)
public final class CraftEngineNeoForgeMod {

    public static final String MOD_ID = "ceclientmod";

    /** Not a NeoForge network version: the bridge carries its own protocol version inside the HELLO payload. */
    private static final String CHANNEL_VERSION = "1";

    public CraftEngineNeoForgeMod(IEventBus modEventBus) {
        ClientNetwork.install(new NeoForgeTransport());
        modEventBus.addListener(CraftEngineNeoForgeMod::registerPayloads);
        NeoForge.EVENT_BUS.addListener(CraftEngineNeoForgeMod::onClientTick);
        NeoForge.EVENT_BUS.addListener(CraftEngineNeoForgeMod::onLoggingIn);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // Every channel is optional. The other end is a Paper plugin that only speaks vanilla custom
        // payloads, and a server without the bridge plugin must still let the game launch and play.
        PayloadRegistrar registrar = event.registrar(CHANNEL_VERSION).optional();

        registrar.playToClient(BridgeChannels.ITEMS, ChunkPayload.codecFor(BridgeChannels.ITEMS),
                (payload, context) -> BridgeClientCore.acceptItems(payload));
        registrar.playToClient(BridgeChannels.BLOCKS, ChunkPayload.codecFor(BridgeChannels.BLOCKS),
                (payload, context) -> BridgeClientCore.acceptBlocks(payload));
        registrar.playToClient(BridgeChannels.BREWING, ChunkPayload.codecFor(BridgeChannels.BREWING),
                (payload, context) -> BridgeClientCore.acceptBrewing(payload));
        registrar.playToClient(BridgeChannels.CRAFTING_DISPLAY, ChunkPayload.codecFor(BridgeChannels.CRAFTING_DISPLAY),
                (payload, context) -> BridgeClientCore.acceptCraftingDisplay(payload));
        registrar.playToClient(BridgeChannels.SMITHING_DISPLAY, ChunkPayload.codecFor(BridgeChannels.SMITHING_DISPLAY),
                (payload, context) -> BridgeClientCore.acceptSmithingDisplay(payload));
        registrar.playToClient(BridgeChannels.BLOCK_ICONS, ChunkPayload.codecFor(BridgeChannels.BLOCK_ICONS),
                (payload, context) -> BridgeClientCore.acceptBlockIcons(payload));
        registrar.playToClient(BridgeChannels.FURNITURE_ICON, ChunkPayload.codecFor(BridgeChannels.FURNITURE_ICON),
                (payload, context) -> BridgeClientCore.acceptFurnitureIcon(payload));
        registrar.playToClient(BridgeChannels.BLOCK_ICON, ChunkPayload.codecFor(BridgeChannels.BLOCK_ICON),
                (payload, context) -> BridgeClientCore.acceptBlockIcon(payload));

        // C2S: the bridge server is the Paper plugin, so nothing here ever handles them. They are declared
        // so the client is allowed to send them while connected to a non-NeoForge server.
        registrar.playToServer(HelloPayload.TYPE, HelloPayload.CODEC, (payload, context) -> {
        });
        registrar.playToServer(FurnitureProbePayload.TYPE, FurnitureProbePayload.CODEC, (payload, context) -> {
        });
        registrar.playToServer(BlockProbePayload.TYPE, BlockProbePayload.CODEC, (payload, context) -> {
        });
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        BridgeClientCore.onEndTick(Minecraft.getInstance());
    }

    private static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        BridgeClientCore.onJoin();
    }

    /** Adapts the shared bridge to NeoForge's networking. */
    private static final class NeoForgeTransport implements ClientNetwork.Transport {

        @Override
        public boolean canSendToServer(CustomPacketPayload.Type<?> type) {
            ClientPacketListener listener = Minecraft.getInstance().getConnection();
            if (listener == null) return false;
            Connection connection = listener.getConnection();
            if (connection == null) return false;
            // NeoForge tracks the remote side's channel advertisements on the connection itself, including
            // the plain "minecraft:register" list a Bukkit/Paper server sends for its plugin channels. This
            // therefore answers the question that matters: does the server we are on accept this probe.
            return NetworkRegistry.hasChannel(connection, ConnectionProtocol.PLAY, type.id());
        }

        @Override
        public void sendToServer(CustomPacketPayload payload) {
            ClientPacketDistributor.sendToServer(payload);
        }

        @Override
        public boolean isModLoaded(String modId) {
            return ModList.get().isLoaded(modId);
        }
    }
}