package com.power.usefulcomponents.registry;

import com.power.usefulcomponents.UsefulComponents;
import com.power.usefulcomponents.components.RibbonCableConnectionRequest;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Data components registered by this mod. Currently only holds the
 * "pending ribbon cable connection" marker that lives on a held
 * {@code ribbon_cable} item between selecting the first and second
 * connector - see {@link RibbonCableConnectionRequest}.
 */
public final class ModDataComponents {

    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, UsefulComponents.MODID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<RibbonCableConnectionRequest>>
            PENDING_RIBBON_CONNECTION = DATA_COMPONENTS.registerComponentType(
                    "pending_ribbon_connection",
                    builder -> builder
                            .persistent(RibbonCableConnectionRequest.CODEC)
                            .networkSynchronized(RibbonCableConnectionRequest.STREAM_CODEC)
            );

    private ModDataComponents() {
    }
}
