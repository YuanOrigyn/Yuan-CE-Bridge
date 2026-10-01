package com.ceclientmod.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S request for the CraftEngine block at the position Jade is currently targeting. Blocks are asked for by
 * position rather than by the visual state the client can see, because CraftEngine lets several custom blocks
 * share one visual state and a vanilla-protocol client cannot tell those apart from chunk data alone.
 */
public record BlockProbePayload(int requestId, int x, int y, int z) implements CustomPacketPayload {
    public static final Type<BlockProbePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("ceclientbridge", "block_probe"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BlockProbePayload> CODEC = StreamCodec.of(
            (buf, payload) -> {
                buf.writeVarInt(payload.requestId());
                buf.writeVarInt(payload.x());
                buf.writeVarInt(payload.y());
                buf.writeVarInt(payload.z());
            },
            buf -> new BlockProbePayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt())
    );

    public BlockProbePayload(int requestId, BlockPos pos) {
        this(requestId, pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
