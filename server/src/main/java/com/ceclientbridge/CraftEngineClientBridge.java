package com.ceclientbridge;

import com.ceclientbridge.net.BridgeChannels;
import com.ceclientbridge.protocol.BridgeCapabilities;
import com.ceclientbridge.protocol.BridgeCompatibilityGate;
import com.ceclientbridge.protocol.BridgeHandshake;
import com.ceclientbridge.protocol.BridgeHello;
import com.ceclientbridge.protocol.BridgeHelloCodec;
import com.ceclientbridge.protocol.JadeIconProtocol;
import com.ceclientbridge.protocol.FixedWindowRateLimiter;
import com.ceclientbridge.recipe.RecipeSyncListener;
import com.ceclientbridge.sync.MooncakeIconResolver;
import com.ceclientbridge.sync.SyncManager;
import com.ceclientbridge.version.BridgeServerTarget;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.inventory.ItemStack;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bridges CraftEngine's custom item/block/brewing-recipe registries (public Bukkit API only, no NMS)
 * to any connected client running the companion CraftEngineClientMod, so that mod's JEI/Jade
 * integrations can tell CraftEngine's custom items/blocks apart from vanilla ones.
 */
public final class CraftEngineClientBridge extends JavaPlugin implements Listener, PluginMessageListener {

    /** Squared block distance a Jade block probe is answered from; well beyond any legitimate tooltip range. */
    private static final double MAX_BLOCK_PROBE_DISTANCE_SQUARED = 32.0D * 32.0D;

    private SyncManager syncManager;
    /** Answers for blocks whose items live in their block entity rather than in the block state. */
    private MooncakeIconResolver mooncakeIcons;
    private final BridgeCompatibilityGate compatibilityGate = new BridgeCompatibilityGate();
    private final java.util.Map<java.util.UUID, FixedWindowRateLimiter> furnitureProbeLimits = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, FixedWindowRateLimiter> blockProbeLimits = new java.util.HashMap<>();

    @Override
    public void onEnable() {
        syncManager = new SyncManager(this);
        mooncakeIcons = new MooncakeIconResolver(this, syncManager);

        getServer().getMessenger().registerOutgoingPluginChannel(this, BridgeChannels.ITEMS);
        getServer().getMessenger().registerOutgoingPluginChannel(this, BridgeChannels.BLOCKS);
        getServer().getMessenger().registerOutgoingPluginChannel(this, BridgeChannels.BREWING);
        getServer().getMessenger().registerOutgoingPluginChannel(this, BridgeChannels.CRAFTING_DISPLAY);
        getServer().getMessenger().registerOutgoingPluginChannel(this, BridgeChannels.SMITHING_DISPLAY);
        getServer().getMessenger().registerOutgoingPluginChannel(this, BridgeChannels.BLOCK_ICONS);
        getServer().getMessenger().registerOutgoingPluginChannel(this, BridgeChannels.FURNITURE_ICON);
        getServer().getMessenger().registerOutgoingPluginChannel(this, BridgeChannels.BLOCK_ICON);
        getServer().getMessenger().registerIncomingPluginChannel(this, BridgeChannels.HELLO, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, BridgeChannels.FURNITURE_PROBE, this);
        getServer().getMessenger().registerIncomingPluginChannel(this, BridgeChannels.BLOCK_PROBE, this);

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new RecipeSyncListener(this, syncManager), this);

        BridgeCommand command = new BridgeCommand(this, syncManager);
        getCommand("cebridge").setExecutor(command);
        getCommand("cebridge").setTabCompleter(command);

        if (getServer().getPluginManager().getPlugin("CraftEngine") != null) {
            syncManager.rebuild();
        } else {
            getLogger().warning("CraftEngine not found - sync payloads will stay empty until it loads and reloads.");
        }
    }

    public SyncManager syncManager() {
        return syncManager;
    }

    /** Client mod says "I'm here" on {@link BridgeChannels#HELLO} right after it registers the channel; push a full sync back. */
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (BridgeChannels.FURNITURE_PROBE.equals(channel)) {
            handleFurnitureProbe(player, message);
            return;
        }
        if (BridgeChannels.BLOCK_PROBE.equals(channel)) {
            handleBlockProbe(player, message);
            return;
        }
        if (!BridgeChannels.HELLO.equals(channel)) return;
        try {
            BridgeHello clientHello = BridgeHelloCodec.decode(message);
            BridgeHello serverHello = new BridgeHello(
                    com.ceclientbridge.protocol.BridgeProtocol.CURRENT_VERSION,
                    BridgeServerTarget.minecraftTarget(),
                    BridgeCapabilities.ALL
            );
            var negotiation = BridgeHandshake.negotiate(serverHello, clientHello);
            if (!negotiation.accepted()) {
                compatibilityGate.clear(player.getUniqueId());
                getLogger().warning("Rejected CraftEngine client bridge from " + player.getName() + ": " + negotiation.reason());
                return;
            }
        } catch (IllegalArgumentException invalidHello) {
            getLogger().warning("Rejected malformed CraftEngine client bridge hello from " + player.getName()
                    + ": " + invalidHello.getMessage());
            compatibilityGate.clear(player.getUniqueId());
            return;
        }
        compatibilityGate.markCompatible(player.getUniqueId());
        pushAllTo(player);
    }

    private void handleFurnitureProbe(Player player, byte[] message) {
        if (!compatibilityGate.isCompatible(player.getUniqueId())
                || !player.getListeningPluginChannels().contains(BridgeChannels.FURNITURE_ICON)) {
            return;
        }
        FixedWindowRateLimiter limiter = furnitureProbeLimits.computeIfAbsent(
                player.getUniqueId(), ignored -> new FixedWindowRateLimiter(10, 1_000L));
        if (!limiter.tryAcquire(System.currentTimeMillis())) return;
        JadeIconProtocol.FurnitureProbe probe;
        try {
            probe = JadeIconProtocol.decodeFurnitureProbe(message);
        } catch (IllegalArgumentException malformed) {
            getLogger().warning("Rejected malformed Jade furniture probe from " + player.getName() + ": " + malformed.getMessage());
            return;
        }

        JadeIconProtocol.FurnitureIcon response = resolveFurnitureIcon(player, probe);
        long responseGeneration = (syncManager.generation() << 32) | (probe.requestId() & 0xFFFFFFFFL);
        BridgeChannels.send(this, player, BridgeChannels.FURNITURE_ICON, responseGeneration,
                JadeIconProtocol.encodeFurnitureIcon(response));
    }

    private JadeIconProtocol.FurnitureIcon resolveFurnitureIcon(Player player, JadeIconProtocol.FurnitureProbe probe) {
        try {
            net.minecraft.world.entity.Entity handle = ((CraftPlayer) player).getHandle().level().getEntity(probe.entityId());
            if (handle == null) {
                return new JadeIconProtocol.FurnitureIcon(probe.requestId(), probe.entityId(), "", new byte[0]);
            }
            org.bukkit.entity.Entity target = handle.getBukkitEntity();
            if (target.getWorld() != player.getWorld()
                    || target.getLocation().distanceSquared(player.getLocation()) > 64.0) {
                return new JadeIconProtocol.FurnitureIcon(probe.requestId(), probe.entityId(), "", new byte[0]);
            }
            BukkitFurniture furniture = CraftEngineFurniture.getLoadedFurnitureByMetaEntity(target);
            if (furniture == null) furniture = CraftEngineFurniture.getLoadedFurnitureByCollider(target);
            if (furniture == null) furniture = CraftEngineFurniture.getLoadedFurnitureBySeat(target);
            if (furniture == null || !containsEntityId(furniture, probe.entityId())) {
                return new JadeIconProtocol.FurnitureIcon(probe.requestId(), probe.entityId(), "", new byte[0]);
            }
            Item source = furniture.sourceItem();
            if (source == null || source.isEmpty()) source = furniture.buildNewFurnitureItem();
            if (source == null || source.isEmpty() || !(source.platformItem() instanceof ItemStack stack)) {
                return new JadeIconProtocol.FurnitureIcon(probe.requestId(), probe.entityId(), "", new byte[0]);
            }
            ItemStack clientStack = SyncManager.toClientBoundStack(stack.clone());
            // furniture.id() is the authoritative id - passing it in avoids reverse-looking it up off the
            // already client-bound stack, which CraftEngine's s2c transform may have retargeted.
            return new JadeIconProtocol.FurnitureIcon(probe.requestId(), probe.entityId(), furniture.id().asString(),
                    SyncManager.encodeItemAppearance(clientStack, furniture.id().asString()));
        } catch (Throwable t) {
            getLogger().log(java.util.logging.Level.WARNING, "Failed to resolve CraftEngine furniture icon for " + player.getName(), t);
            return new JadeIconProtocol.FurnitureIcon(probe.requestId(), probe.entityId(), "", new byte[0]);
        }
    }

    private static boolean containsEntityId(BukkitFurniture furniture, int entityId) {
        if (furniture.entityId() == entityId) return true;
        for (int candidate : furniture.interactableEntityIds()) {
            if (candidate == entityId) return true;
        }
        for (int candidate : furniture.colliderEntityIds()) {
            if (candidate == entityId) return true;
        }
        return false;
    }

    /**
     * Answers "which CraftEngine block is at this position" for the client's Jade integration.
     * <p>
     * The client only ever learns a block's visual (disguise) state from chunk data, and CraftEngine allows
     * several custom blocks to share one - an explicit {@code state:} is never deduplicated, and the
     * auto-state pools are bounded, so an exhausted one hands the same state to a second block. That is
     * exactly what makes Jade draw block A with block B's icon and name. This question is about one
     * position instead of one state, so the answer is always the block that is really there.
     * <p>
     * The client asks for whichever block its crosshair is on plus a small pre-warm sweep, so this handler
     * is on the hot path of ordinary movement. Its budget used to be 20/s, which a player walking through a
     * farm already exceeds: the surplus was dropped without an answer, and the positions behind those
     * dropped probes kept showing their vanilla name while their neighbours showed the custom item - the
     * same crop rendering two different ways depending on where the crosshair landed. The budget now sits
     * above what a moving client consumes. Note that a dropped probe is silent by design, so the client
     * only finds out by timing out; keep this above the client's sweep plus hover probes.
     */
    private void handleBlockProbe(Player player, byte[] message) {
        // Only the bridge client can send this: the channel is registered as an incoming one above, and a
        // vanilla client has no code that speaks it. The old getListeningPluginChannels() guard was dropped
        // because it is not a reliable view of what a modern client has registered, and a false negative
        // there silently disabled every probe instead of failing loudly.
        if (!compatibilityGate.isCompatible(player.getUniqueId())) return;
        FixedWindowRateLimiter limiter = blockProbeLimits.computeIfAbsent(
                player.getUniqueId(), ignored -> new FixedWindowRateLimiter(60, 1_000L));
        if (!limiter.tryAcquire(System.currentTimeMillis())) return;
        JadeIconProtocol.BlockProbe probe;
        try {
            probe = JadeIconProtocol.decodeBlockProbe(message);
        } catch (IllegalArgumentException malformed) {
            getLogger().warning("Rejected malformed Jade block probe from " + player.getName() + ": " + malformed.getMessage());
            return;
        }

        JadeIconProtocol.BlockProbeIcon response = resolveBlockIcon(player, probe);
        long responseGeneration = (syncManager.generation() << 32) | (probe.requestId() & 0xFFFFFFFFL);
        BridgeChannels.send(this, player, BridgeChannels.BLOCK_ICON, responseGeneration,
                JadeIconProtocol.encodeBlockProbeIcon(response));
    }

    private JadeIconProtocol.BlockProbeIcon resolveBlockIcon(Player player, JadeIconProtocol.BlockProbe probe) {
        try {
            org.bukkit.World world = player.getWorld();
            // Never let a probe pull a chunk in: a client could otherwise ask about arbitrarily far positions.
            if (!world.isChunkLoaded(probe.x() >> 4, probe.z() >> 4)) {
                return emptyBlockProbeIcon(probe);
            }
            org.bukkit.Location target = new org.bukkit.Location(world,
                    probe.x() + 0.5D, probe.y() + 0.5D, probe.z() + 0.5D);
            if (target.distanceSquared(player.getLocation()) > MAX_BLOCK_PROBE_DISTANCE_SQUARED) {
                return emptyBlockProbeIcon(probe);
            }
            ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(
                    world.getBlockAt(probe.x(), probe.y(), probe.z()));
            if (state == null) {
                return emptyBlockProbeIcon(probe);
            }
            Key blockId = state.owner().value().id();
            // A block whose look is decided by its block entity (one state covering several different
            // items, e.g. a plate holding several mooncakes) cannot be answered from the block state
            // alone, so ask it first and keep the block's own icon as the fallback.
            MooncakeIconResolver.Resolved fromBlockEntity = mooncakeIcons.resolve(
                    player, world, probe.x(), probe.y(), probe.z(), blockId);
            if (fromBlockEntity != null) {
                return new JadeIconProtocol.BlockProbeIcon(probe.requestId(),
                        fromBlockEntity.itemId().asString(), fromBlockEntity.appearance());
            }
            byte[] appearance = syncManager.jadeIconAppearance(blockId);
            if (appearance == null) {
                return emptyBlockProbeIcon(probe);
            }
            return new JadeIconProtocol.BlockProbeIcon(probe.requestId(), blockId.asString(), appearance);
        } catch (Throwable t) {
            getLogger().log(java.util.logging.Level.WARNING, "Failed to resolve a CraftEngine block icon for " + player.getName(), t);
            return emptyBlockProbeIcon(probe);
        }
    }

    /** An empty id plus an empty appearance is the wire form of "not a CraftEngine block here". */
    private static JadeIconProtocol.BlockProbeIcon emptyBlockProbeIcon(JadeIconProtocol.BlockProbe probe) {
        return new JadeIconProtocol.BlockProbeIcon(probe.requestId(), "", new byte[0]);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // sendPluginMessage silently no-ops until the client's channel-registration packet reaches the
        // server (getListeningPluginChannels() is still empty at PlayerJoinEvent) - delay a moment so it
        // has time to arrive. The client-side HELLO handshake (onPluginMessageReceived above) is the
        // primary trigger. The gate below prevents this fallback from sending before compatibility is
        // established.
        Player player = event.getPlayer();
        compatibilityGate.clear(player.getUniqueId());
        getServer().getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) {
                pushAllTo(player);
            }
        }, 40L);
    }

    @EventHandler
    public void onCraftEngineReload(CraftEngineReloadEvent event) {
        syncManager.rebuild();
        for (Player player : getServer().getOnlinePlayers()) {
            pushAllTo(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        compatibilityGate.clear(event.getPlayer().getUniqueId());
        furnitureProbeLimits.remove(event.getPlayer().getUniqueId());
        blockProbeLimits.remove(event.getPlayer().getUniqueId());
    }

    public void pushAllTo(Player player) {
        if (!compatibilityGate.isCompatible(player.getUniqueId())) {
            return;
        }
        long generation = syncManager.generation();
        BridgeChannels.send(this, player, BridgeChannels.ITEMS, generation, syncManager.itemsPayload());
        BridgeChannels.send(this, player, BridgeChannels.BLOCKS, generation, syncManager.blocksPayload());
        BridgeChannels.send(this, player, BridgeChannels.BREWING, generation, syncManager.brewingPayload());
        BridgeChannels.send(this, player, BridgeChannels.CRAFTING_DISPLAY, generation, syncManager.craftingDisplayPayload());
        BridgeChannels.send(this, player, BridgeChannels.SMITHING_DISPLAY, generation, syncManager.smithingDisplayPayload());
        if (player.getListeningPluginChannels().contains(BridgeChannels.BLOCK_ICONS)) {
            BridgeChannels.send(this, player, BridgeChannels.BLOCK_ICONS, generation, syncManager.blockIconsPayload());
        }
    }
}
