package com.power.usefulcomponents;

import com.power.usefulcomponents.registry.ModBlockEntities;
import com.power.usefulcomponents.uv.UvCuringRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = UsefulComponents.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class UsefulComponentsClient {

    private UsefulComponentsClient() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.UV_CURING.get(), UvCuringRenderer::new);
    }
}
