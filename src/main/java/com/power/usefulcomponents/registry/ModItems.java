package com.power.usefulcomponents.registry;

import com.power.usefulcomponents.UsefulComponents;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Plain items. Power Grid links an item to a component purely through data
 * files (data/usefulcomponents/powergrid/component_items/*.json) - these
 * items need no special class or behavior of their own. The one exception
 * is RIBBON_CABLE, which isn't a component-placing item at all: it's a
 * plain item whose "linking" behavior lives entirely in
 * RibbonConnectorComponent.use(), which checks the player's held item.
 */
public final class ModItems {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(UsefulComponents.MODID);

    public static final DeferredHolder<Item, Item> VOLTAGE_REGULATOR =
            ITEMS.registerSimpleItem("voltage_regulator", new Item.Properties());

    public static final DeferredHolder<Item, Item> SIGNAL_OSCILLATOR =
            ITEMS.registerSimpleItem("signal_oscillator", new Item.Properties());

    public static final DeferredHolder<Item, Item> DIFFERENTIAL_COMPARATOR =
            ITEMS.registerSimpleItem("differential_comparator", new Item.Properties());

    public static final DeferredHolder<Item, Item> RIBBON_CONNECTOR_SIDE =
            ITEMS.registerSimpleItem("ribbon_connector_side", new Item.Properties());

    public static final DeferredHolder<Item, Item> RIBBON_CONNECTOR_FRONT =
            ITEMS.registerSimpleItem("ribbon_connector_front", new Item.Properties());

    /** Not a component-placing item - see class javadoc. */
    public static final DeferredHolder<Item, Item> RIBBON_CABLE =
            ITEMS.registerSimpleItem("ribbon_cable", new Item.Properties());

    private ModItems() {
    }
}
