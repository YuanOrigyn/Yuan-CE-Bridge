package com.ceclientbridge.sync;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.item.recipe.BukkitRecipeManager;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.item.ItemBuildContext;
import net.momirealms.craftengine.core.item.ItemDefinition;
import net.momirealms.craftengine.core.item.behavior.BlockItem;
import net.momirealms.craftengine.core.item.recipe.CustomBrewingRecipe;
import net.momirealms.craftengine.core.item.recipe.CustomCraftingTableRecipe;
import net.momirealms.craftengine.core.item.recipe.CustomSmithingTransformRecipe;
import net.momirealms.craftengine.core.item.recipe.Ingredient;
import net.momirealms.craftengine.core.item.recipe.Recipe;
import net.momirealms.craftengine.core.item.recipe.RecipeType;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.util.UniqueKey;
import com.ceclientbridge.protocol.JadeIconProtocol;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Builds the three synced payloads (items / blocks / brewing) from CraftEngine's public Bukkit API and
 * caches them so they can be pushed to any player on demand. Rebuilt on CraftEngineReloadEvent.
 * Item entries carry the handful of components that determine identity/appearance (custom_model_data,
 * item_model, display name) extracted via Bukkit's ItemMeta - not a full NBT dump - so the client can
 * reconstruct an equivalent ItemStack using plain vanilla DataComponents setters instead of having to
 * independently re-implement Minecraft's NBT/DataFixer item-loading pipeline.
 * <p>
 * CraftEngine only attaches purely client-visual components (item_model in particular) during its
 * server-to-client packet transform; {@code BukkitItemDefinition#buildItem} returns the logical/
 * server-bound stack and never carries them, no matter what the item's config declares. See
 * {@link #toClientBoundStack}.
 */
public final class SyncManager {

    private final JavaPlugin plugin;
    private volatile byte[] itemsPayload = emptyCountPayload();
    private volatile byte[] blocksPayload = emptyCountPayload();
    private volatile byte[] blockIconsPayload = JadeIconProtocol.encodeBlockIcons(List.of());
    /** Client-bound icon per CraftEngine block id, reused by the on-demand Jade block probe. */
    private volatile Map<Key, ItemStack> blockIconStacks = Map.of();
    /** Client-bound item per CraftEngine item id, for icons the block map has no entry for. */
    private final Map<Key, ItemStack> iconStacks = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile byte[] brewingPayload = emptyCountPayload();
    private volatile byte[] craftingDisplayPayload = emptyCountPayload();
    private volatile Set<String> craftingDisplayRecipeIds = Set.of();
    private volatile byte[] smithingDisplayPayload = emptyCountPayload();
    private volatile Set<String> smithingDisplayRecipeIds = Set.of();
    private volatile long generation;

    public SyncManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public byte[] itemsPayload() {
        return itemsPayload;
    }

    public byte[] blocksPayload() {
        return blocksPayload;
    }

    public byte[] blockIconsPayload() {
        return blockIconsPayload;
    }

    /**
     * Client-bound icon for one CraftEngine block id, encoded exactly like a {@code block_icons} sync entry.
     * Backs the per-position Jade block probe, which is what actually tells apart blocks that share one
     * client-visible visual state.
     *
     * @return the encoded appearance, or null when that block has no item icon to show
     */
    public byte[] jadeIconAppearance(Key blockId) {
        if (blockId == null) return null;
        ItemStack stack = jadeIconStack(blockId);
        if (stack == null) return null;
        try {
            return encodeItemAppearance(stack, blockId.asString());
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to encode a Jade icon for CraftEngine block '" + blockId + "'", t);
            return null;
        }
    }

    /**
     * Returns the cached client-bound icon for one CraftEngine block; callers must clone it before
     * changing item metadata.
     */
    public ItemStack jadeIconStack(Key blockId) {
        if (blockId == null) return null;
        return blockIconStacks.get(blockId);
    }

    /**
     * Client-bound stack for any CraftEngine item id, built the same way the icons in the block_icons sync
     * are. Backs Jade icons that a block entity decides per position instead of the block state - see
     * MooncakeIconResolver - and is cached per id, because such a probe is answered per looked-at position
     * rather than per item.
     *
     * @return null when CraftEngine has no such item, or it builds to nothing
     */
    public ItemStack clientBoundItem(Key itemId) {
        if (itemId == null) return null;
        return iconStacks.computeIfAbsent(itemId, id -> {
            try {
                BukkitItemDefinition definition = CraftEngineItems.byId(id);
                if (definition == null) return null;
                ItemStack stack = definition.buildBukkitItem(ItemBuildContext.empty(), 1);
                if (stack == null || stack.getType().isAir()) return null;
                return toClientBoundStack(stack);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Failed to build CraftEngine item '" + id + "' for a Jade icon", t);
                return null;
            }
        });
    }

    public byte[] brewingPayload() {
        return brewingPayload;
    }

    public byte[] craftingDisplayPayload() {
        return craftingDisplayPayload;
    }

    public byte[] smithingDisplayPayload() {
        return smithingDisplayPayload;
    }

    public long generation() {
        return generation;
    }

    /** Every recipe id (CraftEngine's own crafting-table recipes AND any other plugin's, e.g.
     *  Craftorithm, that involves a CraftEngine item anywhere) that got a precise per-slot JEI display
     *  entry - see buildCraftingDisplayPayload. RecipeSyncListener uses this as the single source of
     *  truth for which recipes to exclude from the native Fabric recipe resync, instead of guessing
     *  independently with a narrower (result-only) check that missed ingredient-only cases. */
    public Set<String> craftingDisplayRecipeIds() {
        return craftingDisplayRecipeIds;
    }

    /** Same idea as craftingDisplayRecipeIds but for the smithing table - see buildSmithingDisplayPayload
     *  and CeSmithingCategory (the custom JEI recipe category these entries feed). */
    public Set<String> smithingDisplayRecipeIds() {
        return smithingDisplayRecipeIds;
    }

    public void rebuild() {
        long nextGeneration = generation + 1;
        Set<Key> craftingOutputItems = collectCraftingOutputItemIds();
        // One claimant map per rebuild, so the blocks channel, the Jade icon channel and the conflict
        // report can never disagree with each other - or print the same warning twice.
        Map<String, List<Key>> claimants = visualStateClaimants();
        reportVisualStateConflicts(claimants);
        itemsPayload = buildItemsPayload(craftingOutputItems);
        blocksPayload = buildBlocksPayload(claimants);
        // Item definitions can change with the reload that triggered this rebuild, so anything built from
        // the previous ones must not stay cached.
        iconStacks.clear();
        blockIconsPayload = buildBlockIconsPayload(claimants);
        brewingPayload = buildBrewingPayload();
        craftingDisplayPayload = buildCraftingDisplayPayload();
        smithingDisplayPayload = buildSmithingDisplayPayload();
        generation = nextGeneration;
        plugin.getLogger().info("CraftEngine sync rebuilt (generation " + generation + "): " + itemsPayload.length + "B items ("
                + craftingOutputItems.size() + " crafting outputs), "
                + blocksPayload.length + "B blocks, " + blockIconsPayload.length + "B Jade block icons, "
                + brewingPayload.length + "B brewing, "
                + craftingDisplayPayload.length + "B crafting display, "
                + smithingDisplayPayload.length + "B smithing display");
    }

    /**
     * Per-slot EXACT appearance data (not just the underlying vanilla material) for CraftEngine's own
     * crafting-table recipes, so the client can feed JEI's IVanillaRecipeFactory a precisely-skinned
     * SlotDisplay per ingredient - something vanilla's own type-based Ingredient can never carry, since
     * it matches by item type, not by component data like item_model. This is purely a JEI display aid;
     * it doesn't touch actual crafting mechanics (that's still whatever CraftEngine itself registered).
     */
    private byte[] buildCraftingDisplayPayload() {
        List<byte[]> entries = new ArrayList<>();
        Set<String> allIds = new HashSet<>();

        try {
            for (Recipe recipe : BukkitRecipeManager.instance().recipesByType(RecipeType.CRAFTING)) {
                try {
                    byte[] entry = buildCraftEngineDisplayEntry(recipe);
                    if (entry != null) {
                        entries.add(entry);
                        allIds.add(recipe.id().asString());
                    }
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.WARNING, "Failed to export CraftEngine crafting recipe '" + recipe.id() + "' for display sync", t);
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to read CraftEngine crafting recipes for display sync", t);
        }

        // Also cover recipes registered by OTHER plugins via the plain Bukkit recipe API (e.g.
        // Craftorithm, which registers via Bukkit.addRecipe() rather than through CraftEngine's own
        // recipe system) - only bother building precise display data for ones that actually involve a
        // CraftEngine item somewhere; plain vanilla recipes already display correctly on their own.
        java.util.Iterator<org.bukkit.inventory.Recipe> bukkitRecipes = plugin.getServer().recipeIterator();
        while (bukkitRecipes.hasNext()) {
            org.bukkit.inventory.Recipe recipe = bukkitRecipes.next();
            try {
                byte[] entry = buildBukkitDisplayEntry(recipe, allIds);
                if (entry != null) {
                    entries.add(entry);
                    if (recipe instanceof org.bukkit.Keyed keyed) {
                        allIds.add(keyed.getKey().toString());
                    }
                }
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Failed to export a Bukkit-registered recipe for crafting display sync", t);
            }
        }

        // Authoritative record of "which recipe ids got a precise JEI display entry" - RecipeSyncListener
        // reads this to decide native-resync exclusion instead of re-deriving its own (narrower,
        // result-only) guess, which missed recipes where only an INGREDIENT (not the result) was a
        // CraftEngine item - exactly the case for Craftorithm recipes crafting a CraftEngine block/item
        // FROM CraftEngine materials where the two checks previously disagreed.
        craftingDisplayRecipeIds = Set.copyOf(allIds);
        return countPrefixed(entries);
    }

    private byte[] buildCraftEngineDisplayEntry(Recipe recipe) throws IOException {
        if (!(recipe instanceof CustomCraftingTableRecipe crafting)) return null;
        boolean shapeless;
        int width;
        int height;
        Ingredient[] grid;
        if (crafting instanceof net.momirealms.craftengine.core.item.recipe.CustomShapedRecipe shaped) {
            shapeless = false;
            var parsed = shaped.parsedPattern();
            width = parsed.width();
            height = parsed.height();
            if (width == 0 || height == 0) return null;
            var parsedIngredients = parsed.ingredients();
            grid = new Ingredient[parsedIngredients.length];
            for (int i = 0; i < grid.length; i++) {
                grid[i] = parsedIngredients[i].orElse(null);
            }
        } else if (crafting instanceof net.momirealms.craftengine.core.item.recipe.CustomShapelessRecipe shapelessRecipe) {
            shapeless = true;
            width = 3;
            height = 3;
            List<Ingredient> list = shapelessRecipe.ingredientsInUse();
            grid = new Ingredient[9];
            for (int i = 0; i < list.size() && i < 9; i++) {
                grid[i] = list.get(i);
            }
        } else {
            return null;
        }

        Item resultItem = crafting.buildVisualOrActualResult(ItemBuildContext.empty());
        if (resultItem == null || !(resultItem.platformItem() instanceof ItemStack resultStack)) return null;
        resultStack = toClientBoundStack(resultStack);

        ByteArrayOutputStream ebos = new ByteArrayOutputStream();
        DataOutputStream eout = new DataOutputStream(ebos);
        eout.writeUTF(recipe.id().asString());
        eout.writeBoolean(shapeless);
        eout.writeByte(width);
        eout.writeByte(height);
        for (Ingredient ingredient : grid) {
            ItemStack slotStack = representativeStack(ingredient);
            boolean hasItem = slotStack != null;
            eout.writeBoolean(hasItem);
            if (hasItem) {
                writeItemAppearance(eout, slotStack);
            }
        }
        writeItemAppearance(eout, resultStack);
        return ebos.toByteArray();
    }

    /** Mirrors buildCraftEngineDisplayEntry but sourced from Bukkit's own recipe API (ShapedRecipe/
     *  ShapelessRecipe + RecipeChoice) instead of CraftEngine's - covers recipes any OTHER plugin
     *  (Craftorithm in particular) registers via Bukkit.addRecipe(), which CraftEngine's own recipe
     *  list never tracks. Skipped entirely unless a CraftEngine item actually appears somewhere in it. */
    private byte[] buildBukkitDisplayEntry(org.bukkit.inventory.Recipe recipe, Set<String> seenIds) throws IOException {
        if (!(recipe instanceof org.bukkit.Keyed keyed)) return null;
        String recipeId = keyed.getKey().toString();
        if (seenIds.contains(recipeId)) return null;

        boolean shapeless;
        int width;
        int height;
        ItemStack[] grid;
        if (recipe instanceof org.bukkit.inventory.ShapedRecipe shaped) {
            shapeless = false;
            String[] shape = shaped.getShape();
            height = shape.length;
            width = height == 0 ? 0 : shape[0].length();
            if (width == 0 || height == 0 || width > 3 || height > 3) return null;
            Map<Character, org.bukkit.inventory.RecipeChoice> choiceMap = shaped.getChoiceMap();
            grid = new ItemStack[width * height];
            for (int row = 0; row < height; row++) {
                String line = shape[row];
                for (int col = 0; col < width; col++) {
                    char ch = col < line.length() ? line.charAt(col) : ' ';
                    org.bukkit.inventory.RecipeChoice choice = ch == ' ' ? null : choiceMap.get(ch);
                    grid[row * width + col] = choice == null ? null : choice.getItemStack();
                }
            }
        } else if (recipe instanceof org.bukkit.inventory.ShapelessRecipe shapelessRecipe) {
            shapeless = true;
            width = 3;
            height = 3;
            List<org.bukkit.inventory.RecipeChoice> choices = shapelessRecipe.getChoiceList();
            grid = new ItemStack[9];
            for (int i = 0; i < choices.size() && i < 9; i++) {
                grid[i] = choices.get(i).getItemStack();
            }
        } else {
            return null;
        }

        ItemStack result = recipe.getResult();
        if (result == null || result.getType().isAir()) return null;

        boolean involvesCraftEngine = isCraftEngineItem(result);
        if (!involvesCraftEngine) {
            for (ItemStack stack : grid) {
                if (isCraftEngineItem(stack)) {
                    involvesCraftEngine = true;
                    break;
                }
            }
        }
        if (!involvesCraftEngine) return null;

        result = toClientBoundStack(result);

        ByteArrayOutputStream ebos = new ByteArrayOutputStream();
        DataOutputStream eout = new DataOutputStream(ebos);
        eout.writeUTF(recipeId);
        eout.writeBoolean(shapeless);
        eout.writeByte(width);
        eout.writeByte(height);
        for (ItemStack slotStack : grid) {
            ItemStack corrected = (slotStack == null || slotStack.getType().isAir()) ? null : toClientBoundStack(slotStack);
            boolean hasItem = corrected != null;
            eout.writeBoolean(hasItem);
            if (hasItem) {
                writeItemAppearance(eout, corrected);
            }
        }
        writeItemAppearance(eout, result);
        return ebos.toByteArray();
    }

    /**
     * Same idea as buildCraftingDisplayPayload but for the smithing table: CraftEngine's own
     * CustomSmithingTransformRecipe (template/base/addition ingredients + a fixed result) and any other
     * plugin's Bukkit-registered SmithingTransformRecipe that involves a CraftEngine item anywhere.
     * Trim recipes (CustomSmithingTrimRecipe) are intentionally not covered - their "result" is computed
     * dynamically from whatever base/addition/pattern the player picks, not a fixed ItemStack, so there's
     * no single precise result to show.
     */
    private byte[] buildSmithingDisplayPayload() {
        List<byte[]> entries = new ArrayList<>();
        Set<String> allIds = new HashSet<>();

        try {
            for (Recipe recipe : BukkitRecipeManager.instance().recipesByType(RecipeType.SMITHING)) {
                try {
                    byte[] entry = buildCraftEngineSmithingDisplayEntry(recipe);
                    if (entry != null) {
                        entries.add(entry);
                        allIds.add(recipe.id().asString());
                    }
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.WARNING, "Failed to export CraftEngine smithing recipe '" + recipe.id() + "' for display sync", t);
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to read CraftEngine smithing recipes for display sync", t);
        }

        java.util.Iterator<org.bukkit.inventory.Recipe> smithingBukkitRecipes = plugin.getServer().recipeIterator();
        while (smithingBukkitRecipes.hasNext()) {
            org.bukkit.inventory.Recipe recipe = smithingBukkitRecipes.next();
            try {
                byte[] entry = buildBukkitSmithingDisplayEntry(recipe, allIds);
                if (entry != null) {
                    entries.add(entry);
                    if (recipe instanceof org.bukkit.Keyed keyed) {
                        allIds.add(keyed.getKey().toString());
                    }
                }
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Failed to export a Bukkit-registered smithing recipe for display sync", t);
            }
        }

        smithingDisplayRecipeIds = Set.copyOf(allIds);
        return countPrefixed(entries);
    }

    private byte[] buildCraftEngineSmithingDisplayEntry(Recipe recipe) throws IOException {
        if (!(recipe instanceof CustomSmithingTransformRecipe smithing)) return null;

        Item resultItem = smithing.buildVisualOrActualResult(ItemBuildContext.empty());
        if (resultItem == null || !(resultItem.platformItem() instanceof ItemStack resultStack)) return null;
        resultStack = toClientBoundStack(resultStack);

        ItemStack templateStack = representativeStack(smithing.template());
        ItemStack baseStack = representativeStack(smithing.base());
        ItemStack additionStack = representativeStack(smithing.addition());

        ByteArrayOutputStream ebos = new ByteArrayOutputStream();
        DataOutputStream eout = new DataOutputStream(ebos);
        eout.writeUTF(recipe.id().asString());
        writeOptionalAppearance(eout, templateStack);
        writeOptionalAppearance(eout, baseStack);
        writeOptionalAppearance(eout, additionStack);
        writeItemAppearance(eout, resultStack);
        return ebos.toByteArray();
    }

    /** Mirrors buildCraftEngineSmithingDisplayEntry but sourced from Bukkit's own SmithingTransformRecipe
     *  (RecipeChoice-based) - covers recipes any OTHER plugin registers via Bukkit.addRecipe(). Skipped
     *  entirely unless a CraftEngine item actually appears somewhere in it. */
    private byte[] buildBukkitSmithingDisplayEntry(org.bukkit.inventory.Recipe recipe, Set<String> seenIds) throws IOException {
        if (!(recipe instanceof org.bukkit.inventory.SmithingTransformRecipe smithing)) return null;
        String recipeId = smithing.getKey().toString();
        if (seenIds.contains(recipeId)) return null;

        ItemStack templateStack = firstChoice(smithing.getTemplate());
        ItemStack baseStack = firstChoice(smithing.getBase());
        ItemStack additionStack = firstChoice(smithing.getAddition());
        ItemStack result = smithing.getResult();
        if (result == null || result.getType().isAir()) return null;

        boolean involvesCraftEngine = isCraftEngineItem(result) || isCraftEngineItem(templateStack)
                || isCraftEngineItem(baseStack) || isCraftEngineItem(additionStack);
        if (!involvesCraftEngine) return null;

        result = toClientBoundStack(result);

        ByteArrayOutputStream ebos = new ByteArrayOutputStream();
        DataOutputStream eout = new DataOutputStream(ebos);
        eout.writeUTF(recipeId);
        writeOptionalAppearance(eout, templateStack == null ? null : toClientBoundStack(templateStack));
        writeOptionalAppearance(eout, baseStack == null ? null : toClientBoundStack(baseStack));
        writeOptionalAppearance(eout, additionStack == null ? null : toClientBoundStack(additionStack));
        writeItemAppearance(eout, result);
        return ebos.toByteArray();
    }

    private static ItemStack firstChoice(org.bukkit.inventory.RecipeChoice choice) {
        if (choice == null) return null;
        ItemStack stack = choice.getItemStack();
        return (stack == null || stack.getType().isAir()) ? null : stack;
    }

    /** [hasItem:bool][appearance?] - like writeItemAppearance but for a slot that may legitimately be
     *  empty (smithing's template/addition are optional on CraftEngine's own recipe type). */
    private static void writeOptionalAppearance(DataOutputStream out, ItemStack stack) throws IOException {
        boolean has = stack != null && !stack.getType().isAir();
        out.writeBoolean(has);
        if (has) {
            writeItemAppearance(out, stack);
        }
    }

    private static boolean isCraftEngineItem(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return false;
        return BukkitItemManager.instance().wrap(stack).getDefinition().isPresent();
    }

    /** One representative, appearance-correct ItemStack for an ingredient slot: prefers an actual
     *  CraftEngine custom item (so the slot shows the right skin, not just the right shape), falling
     *  back to the ingredient's underlying vanilla material otherwise. */
    private static ItemStack representativeStack(Ingredient ingredient) {
        if (ingredient == null) return null;
        for (UniqueKey key : ingredient.items()) {
            BukkitItemDefinition def = CraftEngineItems.byId(key.key());
            if (def != null) {
                ItemStack stack = def.buildItem(ItemBuildContext.empty(), 1).getBukkitItem();
                return stack == null ? null : toClientBoundStack(stack);
            }
        }
        for (UniqueKey key : ingredient.minecraftItems()) {
            org.bukkit.Material material = org.bukkit.Material.matchMaterial(key.key().asString());
            if (material != null) {
                return new ItemStack(material);
            }
        }
        return null;
    }

    /** Every CraftEngine item id produced by a recipe this bridge can send to JEI. */
    private Set<Key> collectCraftingOutputItemIds() {
        Set<Key> ids = new HashSet<>();
        try {
            for (Recipe recipe : BukkitRecipeManager.instance().recipesByType(RecipeType.CRAFTING)) {
                try {
                    if (!(recipe instanceof CustomCraftingTableRecipe crafting)) continue;
                    if (!(crafting instanceof net.momirealms.craftengine.core.item.recipe.CustomShapedRecipe)
                            && !(crafting instanceof net.momirealms.craftengine.core.item.recipe.CustomShapelessRecipe)) continue;
                    Item resultItem = crafting.buildVisualOrActualResult(ItemBuildContext.empty());
                    if (resultItem != null) {
                        resultItem.getDefinition().ifPresent(def -> ids.add(def.id()));
                    }
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.WARNING, "Failed to inspect CraftEngine crafting recipe '" + recipe.id() + "' while collecting output items", t);
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to read CraftEngine crafting recipes while collecting output items", t);
        }

        // Include CraftEngine results from shaped and shapeless recipes registered by other plugins.
        java.util.Iterator<org.bukkit.inventory.Recipe> bukkitRecipes = plugin.getServer().recipeIterator();
        while (bukkitRecipes.hasNext()) {
            org.bukkit.inventory.Recipe recipe = bukkitRecipes.next();
            try {
                if (recipe instanceof org.bukkit.inventory.ShapedRecipe
                        || recipe instanceof org.bukkit.inventory.ShapelessRecipe) {
                    addIfCraftEngineItem(recipe.getResult(), ids);
                }
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Failed to inspect a Bukkit-registered recipe while collecting output items", t);
            }
        }
        return ids;
    }

    private static void addIfCraftEngineItem(ItemStack stack, Set<Key> ids) {
        if (stack == null || stack.getType().isAir()) return;
        BukkitItemManager.instance().wrap(stack).getDefinition().ifPresent(def -> ids.add(def.id()));
    }

    private byte[] buildItemsPayload(Set<Key> craftingOutputItemIds) {
        Map<Key, ItemDefinition> loaded;
        try {
            loaded = CraftEngineItems.loadedItems();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to read CraftEngine loaded items", t);
            return emptyCountPayload();
        }
        List<byte[]> entries = new ArrayList<>();
        for (Key id : loaded.keySet()) {
            if (!craftingOutputItemIds.contains(id)) continue;
            try {
                BukkitItemDefinition def = CraftEngineItems.byId(id);
                if (def == null) continue;
                ItemStack stack = def.buildItem(ItemBuildContext.empty(), 1).getBukkitItem();
                if (stack == null) continue;
                stack = toClientBoundStack(stack);
                ByteArrayOutputStream ebos = new ByteArrayOutputStream();
                DataOutputStream eout = new DataOutputStream(ebos);
                eout.writeUTF(id.asString());
                // The id is already known here - passing it in keeps the client-bound stack from having to
                // be reverse-looked-up (see encodeItemAppearance), which is what made JEI entries lose
                // their craftengine:id marker and collapse into one entry per vanilla base material.
                writeItemAppearance(eout, stack, id.asString());
                entries.add(ebos.toByteArray());
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Failed to build CraftEngine item '" + id + "' for sync", t);
            }
        }
        return countPrefixed(entries);
    }

    /**
     * Every CraftEngine block claiming each client-visible visual block state, lowest id first.
     * <p>
     * A vanilla-protocol client only ever learns the visual (disguise) state from chunk data - never which
     * CraftEngine block really sits behind it - so this mapping is only meaningful while one visual state
     * belongs to one CraftEngine block. CraftEngine itself does not guarantee that: configs can point
     * several blocks at the same explicit {@code state:}, and its state allocator can hand the same
     * auto-allocated state to more than one block when its cache is stale (CraftEngine ships
     * {@code /craftengine debug visual_state_usage} precisely because one state can back several real
     * blocks). Emitting one entry per (block, state) pair therefore used to make the client's
     * visual-state maps silently overwrite each other, so Jade/JEI rendered block A with block B's icon
     * and name.
     * <p>
     * Callers that need one winner take the first id, which is what the blocks channel reports and what the
     * Jade icon fast path keeps using. The icon channel additionally forwards every claimant, because that
     * is what lets a client tell an ambiguous state from a clean one instead of confidently drawing the
     * wrong block.
     */
    private Map<String, List<Key>> visualStateClaimants() {
        Map<Key, BlockDefinition> loadedBlocks;
        try {
            loadedBlocks = CraftEngineBlocks.loadedBlocks();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to read CraftEngine loaded blocks", t);
            return Map.of();
        }
        List<Key> ids = new ArrayList<>(loadedBlocks.keySet());
        ids.sort(java.util.Comparator.comparing(Key::asString));
        Map<String, List<Key>> claimants = new java.util.LinkedHashMap<>();
        for (Key id : ids) {
            BlockDefinition definition = loadedBlocks.get(id);
            if (definition == null) continue;
            for (ImmutableBlockState state : definition.variantProvider().states()) {
                if (state == null || state.visualBlockState() == null) continue;
                String visual = state.visualBlockState().getAsString();
                if (visual == null || visual.isEmpty()) continue;
                List<Key> forState = claimants.computeIfAbsent(visual, k -> new ArrayList<>(1));
                if (!forState.contains(id)) forState.add(id);
            }
        }
        return claimants;
    }

    /** The single id a state is reported as: the lowest CraftEngine id, so the winner is stable across
     *  restarts and reloads and the blocks channel and the Jade icon fast path cannot disagree. */
    private Map<String, Key> visualStateOwners(Map<String, List<Key>> claimants) {
        Map<String, Key> owners = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, List<Key>> entry : claimants.entrySet()) {
            owners.put(entry.getKey(), entry.getValue().get(0));
        }
        return owners;
    }

    /**
     * Reports the conflict total once per rebuild and lists the individual states a level below it (the
     * same ids recur on every rebuild, so warning about each of them every time only trains admins to
     * ignore the console).
     */
    private void reportVisualStateConflicts(Map<String, List<Key>> claimants) {
        int conflicts = 0;
        for (Map.Entry<String, List<Key>> entry : claimants.entrySet()) {
            if (entry.getValue().size() <= 1) continue;
            conflicts++;
            if (plugin.getLogger().isLoggable(Level.FINE)) {
                plugin.getLogger().fine("Visual block state '" + entry.getKey() + "' is used by "
                        + entry.getValue().size() + " CraftEngine blocks " + entry.getValue()
                        + "; clients can only see the state itself, so '" + entry.getValue().get(0)
                        + "' is the one shown for all of them. Give each block its own 'state:' (or let it"
                        + " auto-allocate one) to fix this.");
            }
        }
        if (conflicts > 0) {
            plugin.getLogger().warning("Found " + conflicts + " visual block state(s) claimed by more than one"
                    + " CraftEngine block; Jade/JEI show the first listed id for each of them."
                    + " Enable FINE logging for '" + plugin.getName() + "' to list every one of them, or run"
                    + " '/craftengine debug visual_state_usage <block-id>' for a specific block.");
        }
    }

    private byte[] buildBlocksPayload(Map<String, List<Key>> claimants) {
        List<byte[]> entries = new ArrayList<>();
        for (Map.Entry<String, Key> owner : visualStateOwners(claimants).entrySet()) {
            try {
                ByteArrayOutputStream ebos = new ByteArrayOutputStream();
                DataOutputStream eout = new DataOutputStream(ebos);
                eout.writeUTF(owner.getValue().asString());
                eout.writeUTF(owner.getKey());
                entries.add(ebos.toByteArray());
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Failed to export CraftEngine block '" + owner.getValue() + "' for sync", t);
            }
        }
        return countPrefixed(entries);
    }

    private byte[] buildBlockIconsPayload(Map<String, List<Key>> claimants) {
        Map<Key, ItemDefinition> loadedItems;
        try {
            loadedItems = CraftEngineItems.loadedItems();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to read CraftEngine block items for Jade icons", t);
            blockIconStacks = Map.of();
            return JadeIconProtocol.encodeBlockIcons(List.of());
        }

        Map<Key, ItemStack> iconsByBlock = new java.util.LinkedHashMap<>();
        for (Map.Entry<Key, ItemDefinition> entry : loadedItems.entrySet()) {
            try {
                BlockItem blockItem = entry.getValue().behavior().getFirst(BlockItem.class);
                if (blockItem == null) continue;
                Key blockId = blockItem.block();
                if (blockId == null || iconsByBlock.containsKey(blockId)) continue;
                BukkitItemDefinition definition = CraftEngineItems.byId(entry.getKey());
                if (definition == null) continue;
                ItemStack stack = definition.buildBukkitItem(ItemBuildContext.empty(), 1);
                if (stack == null || stack.getType().isAir()) continue;
                iconsByBlock.put(blockId, toClientBoundStack(stack));
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Failed to build Jade icon from CraftEngine item '" + entry.getKey() + "'", t);
            }
        }
        blockIconStacks = Map.copyOf(iconsByBlock);

        List<JadeIconProtocol.BlockIcon> icons = new ArrayList<>();
        // One entry per claimant, icon-bearing claimant first. The claimants after the first are not there
        // to be used - they win nothing - but their presence is the only signal a client has that more than
        // one CraftEngine block sits behind this state, which is what makes it ask for the truth by position
        // instead of drawing one of them for all of them. A claimant with no item of its own reuses the
        // first appearance so every entry stays decodable; the client stops at the first entry for a state.
        //
        // The first entry used to be the lowest-id owner and the whole state was dropped when that owner had
        // no item. That silently removed states a client could still have resolved, and a two-block crop
        // whose halves disguise as different vanilla states (potatoes[age=2] below, a tripwire state above)
        // then had one half listed and the other missing - the same plant drawn two different ways depending
        // on where the crosshair landed.
        for (Map.Entry<String, List<Key>> entry : claimants.entrySet()) {
            List<Key> owners = entry.getValue();
            int iconIndex = -1;
            for (int i = 0; i < owners.size(); i++) {
                if (iconsByBlock.containsKey(owners.get(i))) {
                    iconIndex = i;
                    break;
                }
            }
            if (iconIndex < 0) continue;
            ItemStack stack = iconsByBlock.get(owners.get(iconIndex));
            try {
                icons.add(new JadeIconProtocol.BlockIcon(
                        entry.getKey(), owners.get(iconIndex).asString(),
                        encodeItemAppearance(stack, owners.get(iconIndex).asString())));
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Failed to export Jade icon for CraftEngine block '" + owners.get(iconIndex) + "'", t);
            }
            for (int i = 0; i < owners.size(); i++) {
                if (i == iconIndex) continue;
                Key claimant = owners.get(i);
                try {
                    icons.add(new JadeIconProtocol.BlockIcon(
                            entry.getKey(), claimant.asString(),
                            encodeItemAppearance(stack, claimant.asString())));
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.WARNING, "Failed to export Jade icon for CraftEngine block '" + claimant + "'", t);
                }
            }
        }
        return JadeIconProtocol.encodeBlockIcons(icons);
    }

    private byte[] buildBrewingPayload() {
        List<Recipe> recipes;
        try {
            recipes = BukkitRecipeManager.instance().recipesByType(RecipeType.BREWING);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to read CraftEngine brewing recipes", t);
            return emptyCountPayload();
        }
        List<byte[]> entries = new ArrayList<>();
        for (Recipe recipe : recipes) {
            try {
                if (!(recipe instanceof CustomBrewingRecipe brewing)) continue;
                String ingredientKey = representativeKey(brewing.ingredient());
                if (ingredientKey == null) continue;
                ItemStack result = (ItemStack) brewing.result().buildItem(ItemBuildContext.empty()).platformItem();
                if (result == null) continue;
                result = toClientBoundStack(result);
                ByteArrayOutputStream ebos = new ByteArrayOutputStream();
                DataOutputStream eout = new DataOutputStream(ebos);
                eout.writeUTF(recipe.id().asString());
                eout.writeUTF(ingredientKey);
                writeItemAppearance(eout, result);
                entries.add(ebos.toByteArray());
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Failed to export CraftEngine brewing recipe for sync", t);
            }
        }
        return countPrefixed(entries);
    }

    /**
     * CraftEngine only applies its client-bound processors - which set item_model, and may rewrite
     * name/lore text - during the server-to-client packet transform. {@code BukkitItemDefinition#buildItem}
     * alone returns the logical/server-bound stack and never carries them, no matter what the item's
     * config declares. {@link BukkitItemManager#s2c} is the same public entry point CraftEngine's own
     * packet listener uses, so this reproduces exactly what a connected player's client actually renders.
     * A null player is safe here: the processor that sets item_model (obfuscation included) keys off
     * CraftEngine's global item-model mapping, not per-player state, and this cache is built once and
     * shared across all players anyway.
     */
    public static ItemStack toClientBoundStack(ItemStack stack) {
        return BukkitItemManager.instance().s2c(stack, null).orElse(stack);
    }

    public static byte[] encodeItemAppearance(ItemStack stack) {
        return encodeItemAppearance(stack, null);
    }

    /**
     * @param knownCeId the CraftEngine id the stack was built from, or null to look it up off the stack.
     *                  Callers that already know the id should always pass it: the id lives in the
     *                  {@code craftengine:id} custom_data tag, and {@link #toClientBoundStack} runs
     *                  CraftEngine's own s2c transform - which may retarget the base item and rewrite that
     *                  tag - so recovering the id from an already client-bound stack is not reliable. An
     *                  appearance that ships without an id makes the client leave that marker off the
     *                  reconstructed stack, and JEI's subtype interpreter keys on exactly that marker:
     *                  several CraftEngine items sharing one vanilla base material then collapse into a
     *                  single JEI entry.
     */
    public static byte[] encodeItemAppearance(ItemStack stack, String knownCeId) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writeItemAppearance(out, stack, knownCeId);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** [baseItem:UTF][hasCraftEngineId:bool][craftEngineId:UTF?][hasCMD:bool][cmd:int?][hasItemModel:bool][itemModel:UTF?][hasName:bool][name:JSON?] */
    private static void writeItemAppearance(DataOutputStream out, ItemStack stack) throws IOException {
        writeItemAppearance(out, stack, null);
    }

    private static void writeItemAppearance(DataOutputStream out, ItemStack stack, String knownCeId) throws IOException {
        out.writeUTF(stack.getType().getKey().toString());
        String ceId = knownCeId != null
                ? knownCeId
                : BukkitItemManager.instance().wrap(stack).getDefinition()
                        .map(definition -> definition.id().asString())
                        .orElse(null);
        out.writeBoolean(ceId != null);
        if (ceId != null) {
            out.writeUTF(ceId);
        }
        ItemMeta meta = stack.getItemMeta();
        boolean hasCmd = meta != null && meta.hasCustomModelData();
        out.writeBoolean(hasCmd);
        if (hasCmd) {
            out.writeInt(meta.getCustomModelData());
        }
        boolean hasItemModel = meta != null && meta.hasItemModel();
        out.writeBoolean(hasItemModel);
        if (hasItemModel) {
            out.writeUTF(meta.getItemModel().toString());
        }
        // custom_name wins over item_name (matches vanilla's own tooltip name priority). CraftEngine
        // commonly sets item_name to a *translatable* component (e.g. a <lang:...> key) rather than
        // literal text, so this must travel as full JSON and get resolved client-side - plain-text
        // serialization would silently drop the translation and send an empty string.
        Component name = null;
        if (meta != null) {
            if (meta.hasCustomName()) name = meta.customName();
            else if (meta.hasItemName()) name = meta.itemName();
        }
        boolean hasName = name != null;
        out.writeBoolean(hasName);
        if (hasName) {
            out.writeUTF(GsonComponentSerializer.gson().serialize(name));
        }
    }

    private static String representativeKey(Ingredient ingredient) {
        if (ingredient == null) return null;
        if (!ingredient.minecraftItems().isEmpty()) return ingredient.minecraftItems().get(0).toString();
        if (!ingredient.items().isEmpty()) return ingredient.items().get(0).toString();
        return null;
    }

    private static byte[] countPrefixed(List<byte[]> entries) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(entries.size());
            for (byte[] entry : entries) {
                out.write(entry);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] emptyCountPayload() {
        return countPrefixed(List.of());
    }
}
