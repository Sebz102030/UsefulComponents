package com.power.usefulcomponents.fluids;

import com.power.usefulcomponents.registry.ModFluids;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
import org.jetbrains.annotations.Nullable;

/**
 * Fluid handler shared by the vanilla glass bottle (empty) and every full placeholder bottle.
 * An empty glass bottle takes the bottle volume (mB, set per fluid) of any fluid that has a bottle and turns into
 * that fluid's bottle; a full bottle gives its fluid back and turns into a glass bottle.
 * Works on a single bottle (stack of 1), like other vanilla-style container handlers.
 */
public class FluidBottleHandler implements IFluidHandlerItem {
    private ItemStack container;

    public FluidBottleHandler(ItemStack container) {
        this.container = container;
    }

    /** The fluid this bottle holds, or null for an empty glass bottle. */
    @Nullable
    private FluidEntry fullOf() {
        Item item = container.getItem();
        for (FluidEntry entry : ModFluids.entries()) {
            if (entry.bottle() != null && entry.bottle().get() == item)
                return entry;
        }
        return null;
    }

    @Override
    public ItemStack getContainer() {
        return container;
    }

    @Override
    public int getTanks() {
        return 1;
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        FluidEntry full = fullOf();
        return full == null ? FluidStack.EMPTY : new FluidStack(full.source().get(), full.bottleVolume());
    }

    @Override
    public int getTankCapacity(int tank) {
        FluidEntry full = fullOf();
        return full != null ? full.bottleVolume() : ModFluids.largestBottleVolume();
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        FluidEntry full = fullOf();
        return full != null ? full.matches(stack.getFluid()) : ModFluids.withBottle(stack.getFluid()) != null;
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (container.getCount() != 1 || !container.is(Items.GLASS_BOTTLE))
            return 0;
        FluidEntry entry = ModFluids.withBottle(resource.getFluid());
        if (entry == null || resource.getAmount() < entry.bottleVolume())
            return 0;
        if (action.execute())
            container = new ItemStack(entry.bottle().get());
        return entry.bottleVolume();
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        FluidEntry full = fullOf();
        if (full == null || !full.matches(resource.getFluid()))
            return FluidStack.EMPTY;
        return drain(resource.getAmount(), action);
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        FluidEntry full = fullOf();
        if (container.getCount() != 1 || full == null || maxDrain < full.bottleVolume())
            return FluidStack.EMPTY;
        if (action.execute())
            container = new ItemStack(Items.GLASS_BOTTLE);
        return new FluidStack(full.source().get(), full.bottleVolume());
    }
}
