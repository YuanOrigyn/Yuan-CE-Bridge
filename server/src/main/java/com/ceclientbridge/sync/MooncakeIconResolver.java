package com.ceclientbridge.sync;

import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.CEWorld;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Jade icon resolution for CraftEngine blocks whose visible items live in their block entity instead of in
 * the block state.
 * <p>
 * The mooncake block is one state for every mooncake there is: flavor and bite stage are not block
 * properties at all, they are per-slot entries in the block entity's own saved data
 * ({@code slot_<kind>_<index>} -> {@code <flavor>:<stage>}), and one placed block can hold several
 * mooncakes at once. The block state a client can see therefore only ever says "some mooncake here", which
 * is why the generic block-item mapping has nothing to go on and every position ends up drawn as the same
 * icon with the same name.
 * <p>
 * So this resolver goes to the source: read the block entity's data back out through CraftEngine's public
 * block-entity API and work out which slot the player is actually aiming at the same way the mooncake
 * plugin does - the crosshair's entry point, then the nearest occupied slot in the block's own slot layout
 * - and return the display item that slot is rendering, together with the handheld item of the same flavor
 * for the name (display items carry a model but no name of their own).
 * <p>
 * Everything degrades instead of guessing: an unreadable or customized layout falls back to the layout the
 * plugin ships, and any step that does not line up (no block entity, no data, unknown flavor, missing item)
 * returns null so the caller keeps its normal behaviour.
 */
public final class MooncakeIconResolver {

    /** Plugin whose config describes the slot layout; without it the shipped layout below is used. */
    private static final String LAYOUT_PLUGIN = "NyanaMooncake";
    private static final String LAYOUT_FILE = "config.yml";
    /** Prefix of the per-slot keys inside the block entity data, e.g. {@code slot_small_0}. */
    private static final String SLOT_PREFIX = "slot_";
    private static final String DEFAULT_NAMESPACE = "nyana_mooncake";
    private static final double DEFAULT_BASE = 0.5D;

    private final JavaPlugin plugin;
    private final SyncManager syncManager;
    private final Set<String> loggedOnce = ConcurrentHashMap.newKeySet();
    private volatile Layout layout = DEFAULT_LAYOUT;
    private volatile long layoutStamp = Long.MIN_VALUE;

    public MooncakeIconResolver(JavaPlugin plugin, SyncManager syncManager) {
        this.plugin = plugin;
        this.syncManager = syncManager;
    }

    /**
     * The appearance of the item a player is looking at, for a block whose items are held by its block
     * entity.
     *
     * @param blockId the CraftEngine block at that position, as reported by the block state
     * @return the display item id plus its encoded client-bound appearance, or null when this block is not
     *         one of these (or its data cannot be read), in which case the caller falls back to its own
     *         block icon
     */
    public Resolved resolve(Player player, World world, int x, int y, int z, Key blockId) {
        if (blockId == null) return null;
        Layout layout = this.layout();
        if (!layout.namespace().equals(blockId.namespace())) return null;
        CompoundTag data = blockData(world, x, y, z);
        if (data == null) {
            logOnce("no-data", "Jade probe for " + blockId + " found no block entity data; falling back to the block's own icon");
            return null;
        }
        List<Occupied> occupied = occupiedSlots(layout, data);
        if (occupied.isEmpty()) return null;
        Occupied target = nearest(player, occupied, layout, x, y, z);
        Slot slot = target.slot();
        if (target.stage() < 0 || target.stage() >= slot.stages()) return null;
        Key displayId = Key.of(layout.namespace(), slot.displayFormat().formatted(target.flavor(), target.stage()));
        ItemStack icon = syncManager.clientBoundItem(displayId);
        if (icon == null) {
            logOnce("missing-display:" + displayId, "Jade probe resolved " + displayId + " for slot '" + slot.kind() + "' but CraftEngine has no such item");
            return null;
        }
        Component name = displayName(syncManager.clientBoundItem(Key.of(layout.namespace(), target.flavor() + slot.itemSuffix())));
        if (name == null) {
            // Display items carry a model but no name of their own, so without the handheld item of the same
            // flavor this would show up as the display item's bare material ("Paper") - worse than the
            // block's own icon, which the caller falls back to.
            logOnce("no-name:" + target.flavor(), "Jade probe resolved " + displayId + " but no handheld item of flavor '" + target.flavor() + "' exists to name it; falling back");
            return null;
        }
        // Copy first: that stack came out of SyncManager's cache and is shared with every other probe.
        ItemStack named = icon.clone();
        ItemMeta meta = named.getItemMeta();
        if (meta != null) {
            meta.itemName(name);
            named.setItemMeta(meta);
            icon = named;
        }
        logOnce("active", "Jade block-entity icons are live (first answer: " + displayId + " at " + blockId + ")");
        return new Resolved(displayId, SyncManager.encodeItemAppearance(icon, displayId.asString()));
    }

    /** The block entity's custom data for one position, or null when there is none to read. */
    private CompoundTag blockData(World world, int x, int y, int z) {
        CraftEngine craftEngine = CraftEngine.instance();
        if (craftEngine == null) return null;
        CEWorld ceWorld = craftEngine.worldManager().getWorld(world.getUID());
        if (ceWorld == null) return null;
        // false: report only what is already there. A probe must never be what creates a block entity, so a
        // position without one simply has nothing to show.
        BlockEntity blockEntity = ceWorld.getBlockEntityAtIfLoaded(new BlockPos(x, y, z), false);
        if (blockEntity == null || blockEntity.controller == null) return null;
        CompoundTag tag = new CompoundTag();
        blockEntity.saveCustomData(tag);
        // The key is configurable per block (behavior.data_key), so find the compound by its shape rather
        // than hardcoding it: the one whose keys are the per-slot entries.
        for (String key : tag.keySet()) {
            CompoundTag candidate = tag.getCompound(key);
            if (candidate == null || candidate.isEmpty()) continue;
            for (String inner : candidate.keySet()) {
                if (inner.startsWith(SLOT_PREFIX)) return candidate;
            }
        }
        return null;
    }

    /** Every occupied slot, in the layout's own order, with its flavor and bite stage read off the data. */
    private static List<Occupied> occupiedSlots(Layout layout, CompoundTag data) {
        List<Occupied> occupied = new ArrayList<>();
        for (Slot slot : layout.slots()) {
            String value = data.getString(SLOT_PREFIX + slot.kind() + "_" + slot.kindSlot(), "");
            if (value.isEmpty()) {
                // Older saves keyed by the flat slot index alone.
                value = data.getString(SLOT_PREFIX + slot.index(), "");
            }
            if (value.isEmpty()) continue;
            int split = value.lastIndexOf(':');
            if (split <= 0 || split == value.length() - 1) continue;
            int stage;
            try {
                stage = Integer.parseInt(value.substring(split + 1).trim());
            } catch (NumberFormatException notAStage) {
                continue;
            }
            occupied.add(new Occupied(slot, value.substring(0, split), stage));
        }
        return occupied;
    }

    /**
     * The occupied slot closest to the crosshair, by the same horizontal distance the mooncake plugin
     * itself uses to decide which mooncake a click bites - so the tooltip names what the player would eat.
     */
    private static Occupied nearest(Player player, List<Occupied> occupied, Layout layout, int x, int y, int z) {
        double[] entry = crosshairEntry(player, x, y, z);
        double pointX = entry == null ? x + 0.5D : entry[0];
        double pointZ = entry == null ? z + 0.5D : entry[2];
        Occupied best = occupied.getFirst();
        double bestDistance = Double.MAX_VALUE;
        for (Occupied candidate : occupied) {
            double dx = pointX - x - (layout.baseX() + candidate.slot().x());
            double dz = pointZ - z - (layout.baseZ() + candidate.slot().z());
            double distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Where the player's crosshair enters that block - the point the server would report as the click
     * location - or null when the ray misses the block entirely.
     */
    private static double[] crosshairEntry(Player player, int x, int y, int z) {
        Location eye = player.getEyeLocation();
        Vector origin = eye.toVector();
        Vector direction = eye.getDirection();
        double[] start = {origin.getX(), origin.getY(), origin.getZ()};
        double[] step = {direction.getX(), direction.getY(), direction.getZ()};
        double[] lower = {x, y, z};
        double[] upper = {x + 1.0D, y + 1.0D, z + 1.0D};
        double near = 0.0D;
        double far = Double.MAX_VALUE;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(step[axis]) < 1.0e-7D) {
                if (start[axis] < lower[axis] || start[axis] > upper[axis]) return null;
                continue;
            }
            double first = (lower[axis] - start[axis]) / step[axis];
            double second = (upper[axis] - start[axis]) / step[axis];
            if (first > second) {
                double swap = first;
                first = second;
                second = swap;
            }
            if (first > near) near = first;
            if (second < far) far = second;
            if (near > far) return null;
        }
        return new double[] {start[0] + step[0] * near, start[1] + step[1] * near, start[2] + step[2] * near};
    }

    /** The stack's own name, resolved with vanilla's own priority. */
    private static Component displayName(ItemStack stack) {
        if (stack == null) return null;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return null;
        if (meta.hasCustomName()) return meta.customName();
        if (meta.hasItemName()) return meta.itemName();
        return null;
    }

    /**
     * The slot layout, re-read whenever the plugin's config file changes. The mooncake plugin owns this
     * layout - slot positions decide which mooncake a click lands on, so a tooltip built from a stale copy
     * would name the wrong one.
     */
    private Layout layout() {
        File file = layoutFile();
        long stamp = file == null ? -1L : file.lastModified();
        if (stamp != layoutStamp) {
            synchronized (this) {
                if (stamp != layoutStamp) {
                    layout = parseLayout(file);
                    layoutStamp = stamp;
                }
            }
        }
        return layout;
    }

    private File layoutFile() {
        Plugin owner = plugin.getServer().getPluginManager().getPlugin(LAYOUT_PLUGIN);
        if (owner == null) return null;
        File file = new File(owner.getDataFolder(), LAYOUT_FILE);
        return file.isFile() ? file : null;
    }

    private Layout parseLayout(File file) {
        if (file == null) return DEFAULT_LAYOUT;
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection kinds = yaml.getConfigurationSection("kinds");
            if (kinds == null) return DEFAULT_LAYOUT;
            double[] base = parseTriple(yaml.getString("display_offset", "0.5,0,0.5"));
            if (base == null) base = new double[] {DEFAULT_BASE, 0.0D, DEFAULT_BASE};
            List<Slot> slots = new ArrayList<>();
            for (String kindId : kinds.getKeys(false)) {
                ConfigurationSection kind = kinds.getConfigurationSection(kindId);
                if (kind == null) continue;
                List<String> rawSlots = kind.getStringList("slots");
                if (rawSlots.isEmpty()) continue;
                String displayFormat = kind.getString("display_format", "%s_display_%d");
                String itemSuffix = kind.getString("item_suffix", "_mooncake");
                int stages = kind.getInt("stages", 4);
                int kindSlot = 0;
                for (String raw : rawSlots) {
                    double[] offset = parseTriple(raw);
                    if (offset == null) continue;
                    slots.add(new Slot(slots.size(), kindId, kindSlot++, offset[0], offset[2], displayFormat, itemSuffix, stages));
                }
            }
            if (slots.isEmpty()) return DEFAULT_LAYOUT;
            return new Layout(yaml.getString("namespace", DEFAULT_NAMESPACE), base[0], base[2], List.copyOf(slots));
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not read the mooncake slot layout from " + file + " (" + t + "); using the shipped layout");
            return DEFAULT_LAYOUT;
        }
    }

    private static double[] parseTriple(String raw) {
        if (raw == null) return null;
        String[] parts = raw.split(",");
        if (parts.length != 3) return null;
        try {
            return new double[] {
                    Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim())};
        } catch (NumberFormatException notAnOffset) {
            return null;
        }
    }

    private void logOnce(String key, String message) {
        if (loggedOnce.add(key)) {
            plugin.getLogger().info(message);
        }
    }

    /** One placed item: which slot it sits in, plus what that slot is showing. */
    private record Occupied(Slot slot, String flavor, int stage) {
    }

    /** A slot of the layout: where it sits, and how an item of it is named in the CraftEngine registry. */
    private record Slot(int index, String kind, int kindSlot, double x, double z, String displayFormat, String itemSuffix, int stages) {
    }

    /** One kind of mooncake's whole set of slots, as the plugin's own config describes it. */
    private record Layout(String namespace, double baseX, double baseZ, List<Slot> slots) {
    }

    /** The layout the mooncake plugin ships, for a server whose config cannot be read. */
    private static final Layout DEFAULT_LAYOUT = defaultLayout();

    private static Layout defaultLayout() {
        List<Slot> slots = new ArrayList<>();
        addKind(slots, "small", "%s_display_%d", "_mooncake", 4, new double[][] {
                {-0.18D, -0.18D}, {0.18D, -0.18D}, {-0.18D, 0.18D}, {0.18D, 0.18D}, {0.0D, 0.0D}});
        addKind(slots, "large", "%s_large_display_%d", "_mooncake_large", 5, new double[][] {{0.0D, 0.0D}});
        return new Layout(DEFAULT_NAMESPACE, DEFAULT_BASE, DEFAULT_BASE, List.copyOf(slots));
    }

    private static void addKind(List<Slot> slots, String kind, String displayFormat, String itemSuffix, int stages, double[][] offsets) {
        for (int kindSlot = 0; kindSlot < offsets.length; kindSlot++) {
            slots.add(new Slot(slots.size(), kind, kindSlot, offsets[kindSlot][0], offsets[kindSlot][1], displayFormat, itemSuffix, stages));
        }
    }

    /** The display item id of one resolved position, plus the appearance Jade should draw for it. */
    public record Resolved(Key itemId, byte[] appearance) {
    }
}
