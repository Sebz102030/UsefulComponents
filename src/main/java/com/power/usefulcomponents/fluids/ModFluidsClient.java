package com.power.usefulcomponents.fluids;

import com.power.usefulcomponents.UsefulComponents;
import com.power.usefulcomponents.registry.ModFluids;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/**
 * Fluid textures for every ModFluids entry: textures/block/{id}_still.png and {id}_flow.png
 * (they must be in textures/block so they get stitched into the block atlas).
 */
@EventBusSubscriber(modid = UsefulComponents.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ModFluidsClient {
    private ModFluidsClient() {
    }

    @SubscribeEvent
    public static void registerFluidExtensions(RegisterClientExtensionsEvent event) {
        for (FluidEntry entry : ModFluids.entries()) {
            ResourceLocation still = ResourceLocation.fromNamespaceAndPath(UsefulComponents.MODID, "block/" + entry.id() + "_still");
            ResourceLocation flowing = ResourceLocation.fromNamespaceAndPath(UsefulComponents.MODID, "block/" + entry.id() + "_flow");
            event.registerFluidType(new IClientFluidTypeExtensions() {
                @Override
                public ResourceLocation getStillTexture() {
                    return still;
                }

                @Override
                public ResourceLocation getFlowingTexture() {
                    return flowing;
                }
            }, entry.type().get());
        }
    }
}
