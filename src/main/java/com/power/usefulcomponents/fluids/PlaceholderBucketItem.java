package com.power.usefulcomponents.fluids;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;

/**
 * Bucket of a fluid from ModFluids. A fluid without a world block (no spread()) cannot be placed, so its
 * bucket does nothing on use; with a block it behaves like a normal bucket.
 */
public class PlaceholderBucketItem extends BucketItem {
    private final boolean placeable;

    public PlaceholderBucketItem(Fluid fluid, Properties properties, boolean placeable) {
        super(fluid, properties);
        this.placeable = placeable;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!placeable)
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        return super.use(level, player, hand);
    }
}
