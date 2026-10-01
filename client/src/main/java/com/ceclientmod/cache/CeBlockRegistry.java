package com.ceclientmod.cache;

import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Client-side cache mapping a CraftEngine block's *visual* (disguise) blockstate string - what any
 * vanilla-protocol client, including us, actually receives in chunk data - back to its real CraftEngine id.
 * Built from the server's {@code ceclientbridge:blocks} sync. See CraftEngineClientBridge's SyncManager
 * for how the visual string is produced server-side (ImmutableBlockState#visualBlockState#getAsString()).
 */
public final class CeBlockRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("ceclientmod");

    private final Map<String, String> visualToCeId = new HashMap<>();

    public void readFrom(DataInputStream in) throws IOException {
        Map<String, String> fresh = new HashMap<>();
        String firstDuplicate = null;
        int duplicates = 0;
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            String ceId = in.readUTF();
            String visual = in.readUTF();
            // First entry wins, same as the Jade icon cache and the server's owner map - a duplicate here
            // means two CraftEngine blocks share one client-visible state, and last-wins would let this
            // lookup disagree with the icon Jade draws for that very state.
            if (fresh.putIfAbsent(visual, ceId) != null) {
                duplicates++;
                if (firstDuplicate == null) firstDuplicate = visual;
            }
        }
        if (duplicates > 0) {
            LOGGER.warn("ceclientmod: {} CraftEngine block state(s) mapped to more than one id (first: '{}'); kept the first id for each", duplicates, firstDuplicate);
        }
        visualToCeId.clear();
        visualToCeId.putAll(fresh);
    }

    public Optional<String> ceIdFor(BlockState state) {
        return Optional.ofNullable(visualToCeId.get(BlockStateStringifier.stringify(state)));
    }
}
