package com.power.usefulcomponents.components;

import com.power.usefulcomponents.UsefulComponents;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.patryk3211.powergrid.circuits.circuitboard.CircuitBoardBlockEntity;
import org.patryk3211.powergrid.circuits.schematic.PlacedComponent;

import java.util.ArrayList;

/**
 * A player breaking a circuit board that has linked ribbon connectors cuts
 * those cables: both ends are unlinked (so the partner board is not left
 * "connected" to nothing) and the cable items drop where the board was.
 * Creative players don't get items, same as when linking costs nothing.
 *
 * Boards destroyed some other way (explosion, command, ...) are handled by
 * the surviving connector, see RibbonConnectorComponent#tick.
 */
@EventBusSubscriber(modid = UsefulComponents.MODID)
public final class RibbonBreaking {

    private RibbonBreaking() {
    }

    // LOWEST so that if another mod cancels the break we never see it (we'd
    // otherwise have cut the cable for a board that is still standing).
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level))
            return;
        if (!(level.getBlockEntity(event.getPos()) instanceof CircuitBoardBlockEntity board))
            return;

        int total = 0;
        for (PlacedComponent connector : new ArrayList<>(board.getComponents(RibbonConnectorComponent.class))) {
            int items = RibbonConnectorComponent.unlink(connector);
            if (items > 0)
                total += items;
        }
        if (total > 0 && !event.getPlayer().isCreative())
            RibbonConnectorComponent.dropItems(level, event.getPos(), total);
    }
}
