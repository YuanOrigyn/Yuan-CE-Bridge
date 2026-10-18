package com.ceclientbridge.sync;

import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Jade icons for YuanGuoDelight cauldrons: the block and icon stay the same while only the item
 * name changes with the cauldron's kind state.
 */
public final class CauldronIconResolver {

    private static final String NAMESPACE = "yuanguo";
    private static final String SUFFIX = "_cauldron";

    static String nameKey(Key blockId, ImmutableBlockState state) {
        if (blockId == null || state == null || !NAMESPACE.equals(blockId.namespace())) return null;
        String path = blockId.value();
        if (!path.endsWith(SUFFIX)) return null;
        String fruit = path.substring(0, path.length() - SUFFIX.length());

        Property<?> kindProperty = state.getProperty("kind");
        if (kindProperty == null) {
            return "block." + NAMESPACE + "." + path;
        }
        Object kind = state.getNullable(kindProperty);
        if (kind == null) return null;
        return switch (kind.toString()) {
            case "jelly" -> "block." + NAMESPACE + "." + fruit + "_jelly" + SUFFIX;
            case "jello" -> "block." + NAMESPACE + "." + fruit + "_jello" + SUFFIX;
            default -> "block." + NAMESPACE + "." + path;
        };
    }

    public byte[] appearance(Key blockId, ImmutableBlockState state, ItemStack icon) {
        String nameKey = nameKey(blockId, state);
        if (nameKey == null || icon == null) return null;

        // This icon comes from SyncManager's shared block cache and must be copied before mutation.
        ItemStack named = icon.clone();
        ItemMeta meta = named.getItemMeta();
        if (meta == null) return null;
        meta.itemName(Component.translatable(nameKey));
        named.setItemMeta(meta);
        return SyncManager.encodeItemAppearance(named, blockId.asString());
    }
}
