package com.power.usefulcomponents.registry;

import com.power.usefulcomponents.UsefulComponents;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(UsefulComponents.MODID);

    public static final DeferredHolder<Item, Item> VOLTAGE_REGULATOR =
            ITEMS.registerSimpleItem("voltage_regulator", new Item.Properties());

    public static final DeferredHolder<Item, Item> SIGNAL_OSCILLATOR =
            ITEMS.registerSimpleItem("signal_oscillator", new Item.Properties());

    public static final DeferredHolder<Item, Item> DIFFERENTIAL_COMPARATOR =
            ITEMS.registerSimpleItem("differential_comparator", new Item.Properties());

    private ModItems() {
    }
}
