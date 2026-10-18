package com.ceclientmod.jade;

import com.ceclientmod.CeClientRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.entity.Entity;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IEntityComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.JadeIds;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.theme.IThemeHelper;
import snownee.jade.api.ui.Element;
import snownee.jade.api.ui.JadeUI;

/**
 * Instantiated by Jade itself: Fabric uses the "jade" fabric.mod.json entrypoint (an explicit class name, no
 * classpath scanning) and NeoForge scans for the {@link WailaPlugin} annotation below. Never referenced
 * from our own client entrypoint, so the game still launches fine without Jade installed.
 *
 * CraftEngine blocks and furniture arrive as vanilla block states/entities. The bridge replaces Jade's
 * icon with the exact client-bound source item and also replaces disguised blocks' titles with that
 * item's client-resolved hover name.
 * <p>
 * Blocks are looked up by the position being targeted, not by the block state alone: CraftEngine can point
 * several custom blocks at one visual state, and only the server knows which of them is really there.
 */
@WailaPlugin("ceclientmod")
public final class CeJadePlugin implements IWailaPlugin {

    private static final Identifier UID = Identifier.fromNamespaceAndPath("ceclientmod", "jade_plugin");

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        CeBlockComponentProvider blocks = new CeBlockComponentProvider();
        registration.registerBlockComponent(blocks, Block.class);
        registration.registerBlockIcon(blocks, Block.class);
        registration.registerEntityIcon(new CeFurnitureIconProvider(), Entity.class);
        registration.addTooltipCollectedCallback(Integer.MAX_VALUE, (box, accessor) -> {
            if (!(accessor instanceof BlockAccessor blockAccessor)) return;
            CeClientRegistries.blockIcons()
                    .iconFor(blockAccessor.getLevel().dimension(), blockAccessor.getPosition(), blockAccessor.getBlockState())
                    .ifPresent(stack -> {
                        box.getTooltip().remove(JadeIds.MC_NOTE_BLOCK);
                        box.getTooltip().remove(JadeIds.MC_REDSTONE);
                        box.getTooltip().replace(
                                JadeIds.CORE_OBJECT_NAME,
                                IThemeHelper.get().title(stack.getHoverName()));
                    });
        });
    }

    private static final class CeBlockComponentProvider implements IBlockComponentProvider {

        @Override
        public Element getIcon(BlockAccessor accessor, IPluginConfig config, Element currentIcon) {
            return CeClientRegistries.blockIcons()
                    .iconFor(accessor.getLevel().dimension(), accessor.getPosition(), accessor.getBlockState())
                    .<Element>map(JadeUI::item)
                    .orElse(currentIcon);
        }

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            // Deliberately empty. This used to append a second tooltip line reading "CraftEngine: ce:..."
            // - an internal identifier with no business being in a player-facing tooltip. A CraftEngine
            // block now shows as icon + name only (the name replacement happens in registerClient above).
            // Nothing else changed: the id is still synced and still used internally, and no namespace,
            // item id, data component or lore data is touched.
        }

        @Override
        public Identifier getUid() {
            return UID;
        }
    }

    private static final class CeFurnitureIconProvider implements IEntityComponentProvider {
        @Override
        public Element getIcon(EntityAccessor accessor, IPluginConfig config, Element currentIcon) {
            return CeClientRegistries.furnitureIcons().iconFor(accessor.getEntity())
                    .<Element>map(JadeUI::item)
                    .orElse(currentIcon);
        }

        @Override
        public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
        }

        @Override
        public Identifier getUid() {
            return UID;
        }
    }
}
