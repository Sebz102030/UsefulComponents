package com.power.usefulcomponents.registry;

import com.power.usefulcomponents.UsefulComponents;
import com.power.usefulcomponents.fluids.FluidBottleHandler;
import com.power.usefulcomponents.fluids.FluidEntry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.capability.wrappers.FluidBucketWrapper;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Placeholder fluids (recipes, Create pipes, fluid tanks). To add one, just declare it:
 *
 * <pre>
 *   public static final FluidEntry MY_FLUID = fluid("my_fluid")
 *           .bucketable("my_fluid_bucket")   // optional
 *           .bottle("my_fluid_bottle", 250, 16)  // optional: mB per bottle, stack size
 *           .spread(7, 0);                       // optional: placeable; blocks from source, temperature
 * </pre>
 *
 * spread(7, 0) is water-like; spread(3, 1000) spreads 3 blocks and burns like lava.
 *
 * Item capabilities, client textures and registration are handled for every entry. See FluidEntry
 * for the resource files each id needs.
 */
public final class ModFluids {
    public static final DeferredRegister<FluidType> FLUID_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, UsefulComponents.MODID);
    public static final DeferredRegister<Fluid> FLUIDS =
            DeferredRegister.create(Registries.FLUID, UsefulComponents.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(UsefulComponents.MODID);
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(UsefulComponents.MODID);

    // Must stay above the fluid declarations below (static initialisation order).
    private static final List<FluidEntry> ENTRIES = new ArrayList<>();

    // ---- declare fluids here ----
    public static final FluidEntry PLACEHOLDER = fluid("placeholder")
            .bucketable("placeholder_bucket")
            .bottle("placeholder_bottle", 250, 4).spread(3, 1000); // 250 mB per bottle, stacks to 4

    /** Registers a fluid (type + source + flowing); chain .bucketable(id) / .bottle(id) / .spread(length, temperature). */
    public static FluidEntry fluid(String id) {
        FluidEntry entry = new FluidEntry(id, FLUID_TYPES, FLUIDS, ITEMS, BLOCKS);
        ENTRIES.add(entry);
        return entry;
    }

    public static List<FluidEntry> entries() {
        return Collections.unmodifiableList(ENTRIES);
    }

    /** The entry whose fluid is {@code fluid} and that has a bottle, or null. */
    @Nullable
    public static FluidEntry withBottle(Fluid fluid) {
        for (FluidEntry entry : ENTRIES) {
            if (entry.bottle() != null && entry.matches(fluid))
                return entry;
        }
        return null;
    }

    /** Largest bottle volume (mB) of any fluid with a bottle (tank capacity shown for an empty glass bottle). */
    public static int largestBottleVolume() {
        int largest = FluidEntry.DEFAULT_BOTTLE_VOLUME;
        for (FluidEntry entry : ENTRIES) {
            if (entry.bottle() != null)
                largest = Math.max(largest, entry.bottleVolume());
        }
        return largest;
    }

    /** Call once from the mod constructor. */
    public static void register(IEventBus modEventBus) {
        FLUID_TYPES.register(modEventBus);
        FLUIDS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCKS.register(modEventBus);
        modEventBus.addListener(ModFluids::registerCapabilities);
    }

    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        boolean anyBottle = false;
        for (FluidEntry entry : ENTRIES) {
            if (entry.bucket() != null)
                event.registerItem(Capabilities.FluidHandler.ITEM,
                        (stack, ctx) -> new FluidBucketWrapper(stack), entry.bucket().get());
            if (entry.bottle() != null) {
                event.registerItem(Capabilities.FluidHandler.ITEM,
                        (stack, ctx) -> new FluidBottleHandler(stack), entry.bottle().get());
                anyBottle = true;
            }
        }
        // One handler on the vanilla glass bottle serves every fluid that has a bottle.
        if (anyBottle)
            event.registerItem(Capabilities.FluidHandler.ITEM,
                    (stack, ctx) -> new FluidBottleHandler(stack), Items.GLASS_BOTTLE);
    }

    private ModFluids() {
    }
}
