package com.power.usefulcomponents.items;

import com.simibubi.create.content.processing.sequenced.SequencedAssemblyItem;
import net.minecraft.world.item.Item;

/**
 * Intermediate item for Create sequenced assembly recipes (registered as
 * "incomplete_oscillator"). Extending SequencedAssemblyItem is what gives it
 * the progress bar in the inventory, exactly like Create's
 * incomplete precision mechanism.
 */
public class IncompletePlaceholder extends SequencedAssemblyItem {

    public IncompletePlaceholder(Item.Properties properties) {
        super(properties);
    }
}
