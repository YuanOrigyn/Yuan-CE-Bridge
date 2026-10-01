package com.ceclientmod;

import com.ceclientmod.net.BlockProbePayload;
import com.ceclientmod.net.BridgeChannels;
import com.ceclientmod.net.ChunkPayload;
import com.ceclientmod.net.ClientNetwork;
import com.ceclientmod.net.FurnitureProbePayload;
import com.ceclientmod.net.HelloPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Fabric client entrypoint. Declares the bridge channels, installs the Fabric transport and forwards every
 * packet to {@link BridgeClientCore}, where the actual bridge logic lives. That logic is loader-neutral, so
 * the NeoForge entrypoint (see the client-neoforge project) runs exactly the same code.
 */
public final class CraftEngineClientModInit implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientNetwork.install(new ClientNetwork.Transport() {
            @Override
            public boolean canSendToServer(CustomPacketPayload.Type<?> type) {
                return ClientPlayNetworking.canSend(type);
            }

            @Override
            public void sendToServer(CustomPacketPayload payload) {
                ClientPlayNetworking.send(payload);
            }

            @Override
            public boolean isModLoaded(String modId) {
                return FabricLoader.getInstance().isModLoaded(modId);
            }
        });

        PayloadTypeRegistry.clientboundPlay().register(BridgeChannels.ITEMS, ChunkPayload.codecFor(BridgeChannels.ITEMS));
        PayloadTypeRegistry.clientboundPlay().register(BridgeChannels.BLOCKS, ChunkPayload.codecFor(BridgeChannels.BLOCKS));
        PayloadTypeRegistry.clientboundPlay().register(BridgeChannels.BREWING, ChunkPayload.codecFor(BridgeChannels.BREWING));
        PayloadTypeRegistry.clientboundPlay().register(BridgeChannels.CRAFTING_DISPLAY, ChunkPayload.codecFor(BridgeChannels.CRAFTING_DISPLAY));
        PayloadTypeRegistry.clientboundPlay().register(BridgeChannels.SMITHING_DISPLAY, ChunkPayload.codecFor(BridgeChannels.SMITHING_DISPLAY));
        PayloadTypeRegistry.clientboundPlay().register(BridgeChannels.BLOCK_ICONS, ChunkPayload.codecFor(BridgeChannels.BLOCK_ICONS));
        PayloadTypeRegistry.clientboundPlay().register(BridgeChannels.FURNITURE_ICON, ChunkPayload.codecFor(BridgeChannels.FURNITURE_ICON));
        PayloadTypeRegistry.clientboundPlay().register(BridgeChannels.BLOCK_ICON, ChunkPayload.codecFor(BridgeChannels.BLOCK_ICON));
        PayloadTypeRegistry.serverboundPlay().register(HelloPayload.TYPE, HelloPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(FurnitureProbePayload.TYPE, FurnitureProbePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(BlockProbePayload.TYPE, BlockProbePayload.CODEC);

        ClientPlayNetworking.registerGlobalReceiver(BridgeChannels.ITEMS,
                (payload, context) -> BridgeClientCore.acceptItems(payload));
        ClientPlayNetworking.registerGlobalReceiver(BridgeChannels.BLOCKS,
                (payload, context) -> BridgeClientCore.acceptBlocks(payload));
        ClientPlayNetworking.registerGlobalReceiver(BridgeChannels.BREWING,
                (payload, context) -> BridgeClientCore.acceptBrewing(payload));
        ClientPlayNetworking.registerGlobalReceiver(BridgeChannels.CRAFTING_DISPLAY,
                (payload, context) -> BridgeClientCore.acceptCraftingDisplay(payload));
        ClientPlayNetworking.registerGlobalReceiver(BridgeChannels.SMITHING_DISPLAY,
                (payload, context) -> BridgeClientCore.acceptSmithingDisplay(payload));
        ClientPlayNetworking.registerGlobalReceiver(BridgeChannels.BLOCK_ICONS,
                (payload, context) -> BridgeClientCore.acceptBlockIcons(payload));
        ClientPlayNetworking.registerGlobalReceiver(BridgeChannels.FURNITURE_ICON,
                (payload, context) -> BridgeClientCore.acceptFurnitureIcon(payload));
        ClientPlayNetworking.registerGlobalReceiver(BridgeChannels.BLOCK_ICON,
                (payload, context) -> BridgeClientCore.acceptBlockIcon(payload));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> BridgeClientCore.onJoin());
        ClientTickEvents.END_CLIENT_TICK.register(BridgeClientCore::onEndTick);
    }
}