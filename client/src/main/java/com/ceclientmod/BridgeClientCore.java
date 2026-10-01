package com.ceclientmod;

import com.ceclientmod.net.ChunkAssembler;
import com.ceclientmod.net.ChunkPayload;
import com.ceclientmod.net.ClientNetwork;
import com.ceclientmod.net.HelloPayload;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;

/**
 * Loader-neutral half of the client entrypoint. Owns the chunk assemblers and turns each incoming bridge
 * payload into a cache update. The Fabric and the NeoForge entrypoint only register their channels and
 * forward to the accept/onJoin/onEndTick methods here, so both loaders run identical bridge logic.
 */
public final class BridgeClientCore {

    private static final Logger LOGGER = LoggerFactory.getLogger("ceclientmod");

    private static final int HELLO_MAX_ATTEMPTS = 200; // ~10s at 20 ticks/sec

    private static final ChunkAssembler ITEMS_ASSEMBLER = new ChunkAssembler("items");
    private static final ChunkAssembler BLOCKS_ASSEMBLER = new ChunkAssembler("blocks");
    private static final ChunkAssembler BREWING_ASSEMBLER = new ChunkAssembler("brewing");
    private static final ChunkAssembler CRAFTING_DISPLAY_ASSEMBLER = new ChunkAssembler("crafting_display");
    private static final ChunkAssembler SMITHING_DISPLAY_ASSEMBLER = new ChunkAssembler("smithing_display");
    private static final ChunkAssembler BLOCK_ICONS_ASSEMBLER = new ChunkAssembler("block_icons");
    private static final ChunkAssembler FURNITURE_ICON_ASSEMBLER = new ChunkAssembler("furniture_icon");
    private static final ChunkAssembler BLOCK_ICON_ASSEMBLER = new ChunkAssembler("block_icon");

    private static boolean helloPending = false;
    private static int helloAttempts = 0;

    private BridgeClientCore() {
    }

    public static void acceptItems(ChunkPayload payload) {
        ITEMS_ASSEMBLER.accept(payload).ifPresent(full -> {
            try {
                CeClientRegistries.items().readFrom(new DataInputStream(new ByteArrayInputStream(full)));
                LOGGER.info("ceclientmod: loaded " + CeClientRegistries.items().all().size() + " CraftEngine items");
                CeClientRegistries.notifyJeiItemsUpdated();
            } catch (Exception e) {
                LOGGER.warn("ceclientmod: failed to parse items sync", e);
            }
        });
    }

    public static void acceptBlocks(ChunkPayload payload) {
        BLOCKS_ASSEMBLER.accept(payload).ifPresent(full -> {
            try {
                CeClientRegistries.blocks().readFrom(new DataInputStream(new ByteArrayInputStream(full)));
                LOGGER.info("ceclientmod: loaded CraftEngine block visual mappings");
            } catch (Exception e) {
                LOGGER.warn("ceclientmod: failed to parse blocks sync", e);
            }
        });
    }

    public static void acceptBrewing(ChunkPayload payload) {
        BREWING_ASSEMBLER.accept(payload).ifPresent(full -> {
            try {
                CeClientRegistries.brewing().readFrom(new DataInputStream(new ByteArrayInputStream(full)));
                LOGGER.info("ceclientmod: loaded " + CeClientRegistries.brewing().all().size() + " CraftEngine brewing recipes");
            } catch (Exception e) {
                LOGGER.warn("ceclientmod: failed to parse brewing sync", e);
            }
        });
    }

    public static void acceptCraftingDisplay(ChunkPayload payload) {
        CRAFTING_DISPLAY_ASSEMBLER.accept(payload).ifPresent(full -> {
            try {
                CeClientRegistries.craftingDisplay().readFrom(new DataInputStream(new ByteArrayInputStream(full)));
                LOGGER.info("ceclientmod: loaded " + CeClientRegistries.craftingDisplay().all().size() + " CraftEngine crafting display entries");
                CeClientRegistries.notifyJeiCraftingDisplayUpdated();
            } catch (Exception e) {
                LOGGER.warn("ceclientmod: failed to parse crafting display sync", e);
            }
        });
    }

    public static void acceptSmithingDisplay(ChunkPayload payload) {
        SMITHING_DISPLAY_ASSEMBLER.accept(payload).ifPresent(full -> {
            try {
                CeClientRegistries.smithingDisplay().readFrom(new DataInputStream(new ByteArrayInputStream(full)));
                LOGGER.info("ceclientmod: loaded " + CeClientRegistries.smithingDisplay().all().size() + " CraftEngine smithing display entries");
                CeClientRegistries.notifyJeiSmithingDisplayUpdated();
            } catch (Exception e) {
                LOGGER.warn("ceclientmod: failed to parse smithing display sync", e);
            }
        });
    }

    public static void acceptBlockIcons(ChunkPayload payload) {
        BLOCK_ICONS_ASSEMBLER.accept(payload).ifPresent(full -> {
            try {
                CeClientRegistries.blockIcons().readFrom(full);
                CeClientRegistries.furnitureIcons().resetForSync();
                LOGGER.info("ceclientmod: loaded CraftEngine Jade block icons");
            } catch (Exception e) {
                LOGGER.warn("ceclientmod: failed to parse Jade block icon sync", e);
            }
        });
    }

    public static void acceptFurnitureIcon(ChunkPayload payload) {
        FURNITURE_ICON_ASSEMBLER.accept(payload).ifPresent(full -> {
            try {
                CeClientRegistries.furnitureIcons().accept(full);
            } catch (Exception e) {
                LOGGER.warn("ceclientmod: failed to parse Jade furniture icon response", e);
            }
        });
    }

    public static void acceptBlockIcon(ChunkPayload payload) {
        BLOCK_ICON_ASSEMBLER.accept(payload).ifPresent(full -> {
            try {
                CeClientRegistries.blockIcons().accept(full);
            } catch (Exception e) {
                LOGGER.warn("ceclientmod: failed to parse Jade block icon probe response", e);
            }
        });
    }

    /** A new connection must not reuse anything the previous server sent. */
    public static void onJoin() {
        ITEMS_ASSEMBLER.clear();
        BLOCKS_ASSEMBLER.clear();
        BREWING_ASSEMBLER.clear();
        CRAFTING_DISPLAY_ASSEMBLER.clear();
        SMITHING_DISPLAY_ASSEMBLER.clear();
        BLOCK_ICONS_ASSEMBLER.clear();
        FURNITURE_ICON_ASSEMBLER.clear();
        BLOCK_ICON_ASSEMBLER.clear();
        CeClientRegistries.blockIcons().clear();
        CeClientRegistries.furnitureIcons().clear();
        helloPending = true;
        helloAttempts = 0;
    }

    public static void onEndTick(Minecraft client) {
        // Warm the ambiguous-block answers around the player before the crosshair lands on one, so Jade
        // never has to draw the wrong claimant (or vanilla) for a probe round trip.
        if (client.level != null && client.player != null) {
            CeClientRegistries.blockIcons().sweepNearby(client.level, client.player.blockPosition());
        }
        if (!helloPending) return;
        // canSend(HELLO) is frequently still false right at JOIN - the server's channel-advertisement packet
        // hasn't necessarily been processed client-side yet, so a one-shot send here silently did nothing
        // most of the time. Retry every client tick until it succeeds (typically within a handful of ticks)
        // instead of gambling on a single attempt.
        if (ClientNetwork.canSend(HelloPayload.TYPE)) {
            ClientNetwork.send(new HelloPayload());
            helloPending = false;
        } else if (++helloAttempts > HELLO_MAX_ATTEMPTS) {
            LOGGER.warn("ceclientmod: giving up waiting to send HELLO after " + HELLO_MAX_ATTEMPTS + " ticks");
            helloPending = false;
        }
    }
}