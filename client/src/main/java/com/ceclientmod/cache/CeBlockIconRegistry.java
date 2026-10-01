package com.ceclientmod.cache;

import com.ceclientbridge.protocol.JadeIconProtocol;
import com.ceclientmod.net.BlockProbePayload;
import com.ceclientmod.net.ClientNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Exact inventory-item icons for CraftEngine blocks.
 * <p>
 * The state map built from the server's {@code block_icons} sync is only a fast path. CraftEngine lets
 * several custom blocks share one client-visible visual state (an explicit {@code state:} is never
 * deduplicated, and its auto-state pools are bounded), and from chunk data alone a client cannot tell such
 * blocks apart - which is what used to make Jade draw block A with block B's icon and name. A position Jade
 * actually targets is therefore resolved by asking the server (see {@link BlockProbePayload}), and the state
 * map only covers the frame or two until that answer lands. Blocks nobody targets, and servers without the
 * probe channel, keep behaving exactly as before.
 */
public final class CeBlockIconRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("ceclientmod");

    /** How long a probed answer is trusted. Long enough that walking through a farm re-uses the answers
     *  already collected instead of asking again for every block the crosshair crosses, short enough that a
     *  block swapped for one sharing its visual state cannot keep showing the old block's icon forever. */
    private static final long PROBE_TTL_NANOS = 30_000_000_000L;
    private static final long PROBE_TIMEOUT_NANOS = 2_000_000_000L;
    /** Position answers held at once; far past anything a player can sweep a crosshair over. */
    private static final int MAX_PROBED_POSITIONS = 4096;
    /** Radius of the neighbourhood sweep, how many probes one sweep may start, and how often a sweep may
     *  run at all. The sweep exists to have answers ready before the crosshair arrives, so it must never be
     *  what exhausts the server's per-player probe budget: a probe dropped there is answered by nothing, and
     *  the position it was for keeps showing the vanilla name until a later retry happens to get through. */
    private static final int SWEEP_RADIUS = 4;
    private static final int SWEEP_PROBE_BUDGET = 4;
    private static final long SWEEP_INTERVAL_NANOS = 200_000_000L;
    /** Positions reported as never answered before reporting stops, so a probe channel that is not being
     *  answered shows up in the log instead of only as crops quietly keeping their vanilla name. */
    private static final int MAX_STARVED_REPORTS = 20;
    /** Formatted state names kept before the cache is dropped; a world has far fewer distinct states. */
    private static final int MAX_CACHED_STATE_NAMES = 8192;

    private final Map<String, ItemStack> byVisualState = new HashMap<>();
    /** BlockState to the string CraftEngine uses for it, so neither Jade's per-frame lookup nor the
     *  neighbourhood sweep formats the same state over and over. */
    private final Map<BlockState, String> visualStateNames = new HashMap<>();
    /** States claimed by more than one CraftEngine block - exactly the ones the probe exists to resolve. */
    private final Set<String> ambiguousVisualStates = new HashSet<>();
    private final Map<ProbeKey, ProbeEntry> probes = new HashMap<>();
    private final Map<ProbeKey, PendingProbe> pendingProbes = new HashMap<>();
    private final Map<Integer, ProbeKey> keyByRequest = new HashMap<>();
    private int nextRequestId;
    /** Block position the last neighbourhood sweep covered, and when it ran. */
    private BlockPos lastSweepCenter;
    private long lastSweepNanos;
    /** Positions whose probe the server never answered; each is reported once. */
    private final Set<ProbeKey> starvedProbes = new HashSet<>();

    private record ProbeKey(ResourceKey<Level> dimension, int x, int y, int z) {
    }

    /** {@code icon} is null for a confirmed "no CraftEngine block at this position" answer. */
    private record ProbeEntry(String visualState, ItemStack icon, long answeredAtNanos) {
    }

    private record PendingProbe(String visualState, long requestedAtNanos) {
    }

    public void readFrom(byte[] payload) throws IOException {
        Map<String, ItemStack> fresh = new HashMap<>();
        Set<String> freshAmbiguous = new HashSet<>();
        String firstDuplicate = null;
        int duplicates = 0;
        for (JadeIconProtocol.BlockIcon icon : JadeIconProtocol.decodeBlockIcons(payload)) {
            if (fresh.containsKey(icon.visualState())) {
                // The server sends one entry per (block, state) pair, the winning entry first, so a second
                // entry for one state means CraftEngine really does have more than one block behind it -
                // exactly the states this probe exists for. The first entry already won, and neither the
                // order nor the winner is left to chance: it is the same lowest-id owner the
                // ceclientbridge:blocks channel reports. The duplicate's appearance is not worth decoding.
                duplicates++;
                freshAmbiguous.add(icon.visualState());
                if (firstDuplicate == null) firstDuplicate = icon.visualState();
                continue;
            }
            ItemStack stack = CeItemRegistry.readAppearance(
                    new DataInputStream(new ByteArrayInputStream(icon.appearance())));
            fresh.put(icon.visualState(), stack);
        }
        if (duplicates > 0) {
            LOGGER.warn("ceclientmod: {} CraftEngine block icon(s) shared a visual block state (first: '{}'); those positions are now resolved by asking the server instead of guessing", duplicates, firstDuplicate);
        }
        byVisualState.clear();
        byVisualState.putAll(fresh);
        ambiguousVisualStates.clear();
        ambiguousVisualStates.addAll(freshAmbiguous);
        // Ids and answers can both change with a new sync, so nothing probed under the old one is trusted.
        probes.clear();
        pendingProbes.clear();
        keyByRequest.clear();
        starvedProbes.clear();
    }

    public Optional<ItemStack> iconFor(ResourceKey<Level> dimension, BlockPos pos, BlockState state) {
        String visualState = visualStateOf(state);
        ItemStack mapped = byVisualState.get(visualState);
        if (!ClientNetwork.canSend(BlockProbePayload.TYPE)) {
            // A server without the probe channel: keep the plain sync-map behaviour.
            return Optional.ofNullable(mapped);
        }
        ProbeKey key = new ProbeKey(dimension, pos.getX(), pos.getY(), pos.getZ());
        long now = System.nanoTime();
        ProbeEntry probed = probes.get(key);
        if (probed != null && probed.visualState().equals(visualState)) {
            if (now - probed.answeredAtNanos() >= PROBE_TTL_NANOS) {
                // Stale but still about this state: re-ask in the background and keep showing what we were
                // told. Dropping straight back to the un-probed path here flipped the tooltip to the wrong
                // claimant for a frame every TTL window while the player just stood still looking at it.
                requestProbe(key, visualState, now);
            }
            return Optional.ofNullable(probed.icon());
        }
        requestProbe(key, visualState, now);
        reportIfStarved(key, visualState, now);
        // Every position is asked about, including states the map does have an icon for. That map is built
        // from each block definition's own list of states, which says which blocks *may* use a state, not
        // which one is really at this position - CraftEngine hands a state to a block when it is placed, and
        // its pools hand the same state to several blocks. A position whose real owner differs from the
        // map's idea of the owner used to be shown wrong forever, because nothing ever asked.
        // Until the answer lands, a state with a single claimant keeps its map appearance: it is the only
        // one that can be there. An ambiguous or unmapped state must not be drawn as whichever claimant
        // happens to be first - that is precisely the "block A drawn as block B" this probe removes - and
        // vanilla's own name is a better answer for one frame than a confidently wrong custom one.
        if (mapped == null || ambiguousVisualStates.contains(visualState)) return Optional.empty();
        return Optional.of(mapped);
    }

    /**
     * Reports a probe the server never answered. Silence here is what makes a position fall back to the
     * vanilla name for good, so it must not be invisible: a probe is only lost when the request never
     * reached the server (no probe channel), was rejected, or was dropped by the server's rate limit.
     */
    private void reportIfStarved(ProbeKey key, String visualState, long now) {
        if (starvedProbes.size() >= MAX_STARVED_REPORTS && !starvedProbes.contains(key)) return;
        PendingProbe pending = pendingProbes.get(key);
        if (pending == null || now - pending.requestedAtNanos() < PROBE_TIMEOUT_NANOS) return;
        if (!starvedProbes.add(key)) return;
        LOGGER.warn("ceclientmod: no answer for the CraftEngine block probe at {} (state '{}') after {}s; showing the vanilla name there instead", key, visualState, PROBE_TIMEOUT_NANOS / 1_000_000_000L);
    }

    /**
     * Resolves ambiguous blocks around the player before Jade asks about them.
     * <p>
     * An answer is per position and needs a server round trip, so a first look at an ambiguous block would
     * otherwise draw the owner's item - or vanilla's own name - for one round trip and then switch. One
     * sweep per block position the player moves through fills those answers in ahead of the crosshair,
     * which is what makes the tooltip right on its first frame. It costs nothing while the sync reports no
     * ambiguous state, and only asks about positions that have no answer yet.
     */
    public void sweepNearby(Level level, BlockPos center) {
        if (ambiguousVisualStates.isEmpty() || !ClientNetwork.canSend(BlockProbePayload.TYPE)) return;
        long now = System.nanoTime();
        if (center.equals(lastSweepCenter) || now - lastSweepNanos < SWEEP_INTERVAL_NANOS) return;
        lastSweepCenter = center;
        lastSweepNanos = now;
        int budget = SWEEP_PROBE_BUDGET;
        for (int dy = -SWEEP_RADIUS; dy <= SWEEP_RADIUS && budget > 0; dy++) {
            for (int dx = -SWEEP_RADIUS; dx <= SWEEP_RADIUS && budget > 0; dx++) {
                for (int dz = -SWEEP_RADIUS; dz <= SWEEP_RADIUS && budget > 0; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!level.isLoaded(pos)) continue;
                    String visualState = visualStateOf(level.getBlockState(pos));
                    if (!ambiguousVisualStates.contains(visualState)) continue;
                    ProbeKey key = new ProbeKey(level.dimension(), pos.getX(), pos.getY(), pos.getZ());
                    ProbeEntry answered = probes.get(key);
                    if (answered != null && answered.visualState().equals(visualState)
                            && now - answered.answeredAtNanos() < PROBE_TTL_NANOS) {
                        continue;
                    }
                    if (pendingProbes.containsKey(key)) continue;
                    requestProbe(key, visualState, now);
                    budget--;
                }
            }
        }
    }

    private String visualStateOf(BlockState state) {
        String cached = visualStateNames.get(state);
        if (cached != null) return cached;
        String formatted = BlockStateStringifier.stringify(state);
        if (visualStateNames.size() >= MAX_CACHED_STATE_NAMES) visualStateNames.clear();
        visualStateNames.put(state, formatted);
        return formatted;
    }

    private void requestProbe(ProbeKey key, String visualState, long now) {
        PendingProbe pending = pendingProbes.get(key);
        if (pending != null) {
            if (now - pending.requestedAtNanos() < PROBE_TIMEOUT_NANOS) return;
            pendingProbes.remove(key);
            keyByRequest.values().removeIf(key::equals);
        }
        if (!ClientNetwork.canSend(BlockProbePayload.TYPE)) return;

        int requestId = nextRequestId++ & Integer.MAX_VALUE;
        pendingProbes.put(key, new PendingProbe(visualState, now));
        keyByRequest.put(requestId, key);
        ClientNetwork.send(new BlockProbePayload(requestId, key.x(), key.y(), key.z()));
    }

    /** One authoritative position answer: the real CraftEngine block's icon, or a negative result. */
    public void accept(byte[] payload) throws IOException {
        JadeIconProtocol.BlockProbeIcon response = JadeIconProtocol.decodeBlockProbeIcon(payload);
        ProbeKey key = keyByRequest.remove(response.requestId());
        if (key == null) return;
        PendingProbe pending = pendingProbes.remove(key);
        if (pending == null) return;
        starvedProbes.remove(key);
        ItemStack icon = response.appearance().length == 0 ? null
                : CeItemRegistry.readAppearance(new DataInputStream(new ByteArrayInputStream(response.appearance())));
        // A plain cache, so dropping all of it at the cap is fine - the next look simply asks again.
        if (probes.size() >= MAX_PROBED_POSITIONS) {
            probes.clear();
        }
        probes.put(key, new ProbeEntry(pending.visualState(), icon, System.nanoTime()));
    }

    public void clear() {
        byVisualState.clear();
        ambiguousVisualStates.clear();
        probes.clear();
        pendingProbes.clear();
        keyByRequest.clear();
        visualStateNames.clear();
        starvedProbes.clear();
        lastSweepCenter = null;
        lastSweepNanos = 0L;
    }
}
