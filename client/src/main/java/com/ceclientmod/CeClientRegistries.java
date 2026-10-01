package com.ceclientmod;

import com.ceclientmod.cache.CeBlockIconRegistry;
import com.ceclientmod.cache.CeBlockRegistry;
import com.ceclientmod.cache.CeBrewingRegistry;
import com.ceclientmod.cache.CeCraftingRegistry;
import com.ceclientmod.cache.CeFurnitureIconRegistry;
import com.ceclientmod.cache.CeItemRegistry;
import com.ceclientmod.cache.CeSmithingRegistry;
import com.ceclientmod.net.ClientNetwork;

/**
 * The client-side CraftEngine caches, shared by every loader. The Fabric and the NeoForge entrypoint fill
 * the same instances from the same bridge payloads, and the optional Jade/JEI plugins read them. Kept
 * loader-neutral on purpose: only {@link ClientNetwork} and the entrypoints know which loader is running.
 */
public final class CeClientRegistries {

    private static final CeItemRegistry ITEMS = new CeItemRegistry();
    private static final CeBlockRegistry BLOCKS = new CeBlockRegistry();
    private static final CeBrewingRegistry BREWING = new CeBrewingRegistry();
    private static final CeCraftingRegistry CRAFTING_DISPLAY = new CeCraftingRegistry();
    private static final CeSmithingRegistry SMITHING_DISPLAY = new CeSmithingRegistry();
    private static final CeBlockIconRegistry BLOCK_ICONS = new CeBlockIconRegistry();
    private static final CeFurnitureIconRegistry FURNITURE_ICONS = new CeFurnitureIconRegistry();

    private CeClientRegistries() {
    }

    public static CeItemRegistry items() {
        return ITEMS;
    }

    public static CeBlockRegistry blocks() {
        return BLOCKS;
    }

    public static CeBrewingRegistry brewing() {
        return BREWING;
    }

    public static CeCraftingRegistry craftingDisplay() {
        return CRAFTING_DISPLAY;
    }

    public static CeSmithingRegistry smithingDisplay() {
        return SMITHING_DISPLAY;
    }

    public static CeBlockIconRegistry blockIcons() {
        return BLOCK_ICONS;
    }

    public static CeFurnitureIconRegistry furnitureIcons() {
        return FURNITURE_ICONS;
    }

    /** Guarded so com.ceclientmod.jei (which references mezz.jei.api.*) is only ever classloaded if JEI is present. */
    public static void notifyJeiItemsUpdated() {
        if (ClientNetwork.isModLoaded("jei")) {
            com.ceclientmod.jei.CeJeiPlugin.onItemsUpdated();
        }
    }

    public static void notifyJeiCraftingDisplayUpdated() {
        if (ClientNetwork.isModLoaded("jei")) {
            com.ceclientmod.jei.CeJeiPlugin.onCraftingDisplayUpdated();
        }
    }

    public static void notifyJeiSmithingDisplayUpdated() {
        if (ClientNetwork.isModLoaded("jei")) {
            com.ceclientmod.jei.CeJeiPlugin.onSmithingDisplayUpdated();
        }
    }
}