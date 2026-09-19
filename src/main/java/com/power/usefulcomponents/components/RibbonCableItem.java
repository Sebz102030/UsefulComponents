package com.power.usefulcomponents.components;

import com.power.usefulcomponents.registry.ModDataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The {@code ribbon_cable} item. Its only special behaviour is glowing
 * (enchantment-glint foil) while it's carrying a
 * {@link RibbonCableConnectionRequest} - i.e. the player has right-clicked
 * one connector and is waiting to right-click a second one, mirroring how
 * PowerGrid's copper/gold/iron wire items glow while a connection is in
 * progress.
 * <p>
 * All the actual linking/cutting logic lives in
 * {@code RibbonConnectorComponent#use}, which reads and writes the data
 * component on the stack. This class only needs to exist so
 * {@link #isFoil(ItemStack)} has something to override.
 */
public class RibbonCableItem extends Item {
    public RibbonCableItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return super.isFoil(stack) || stack.has(ModDataComponents.PENDING_RIBBON_CONNECTION.get());
    }
}
