package com.power.usefulcomponents;

import com.power.usefulcomponents.registry.ModBlockEntities;
import com.power.usefulcomponents.registry.ModItems;
import com.power.usefulcomponents.uv.UvCuringBlock;
import com.simibubi.create.foundation.item.ItemDescription;
import com.simibubi.create.foundation.item.KineticStats;
import com.simibubi.create.foundation.item.TooltipModifier;
import net.createmod.catnip.lang.FontHelper;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.patryk3211.powergrid.electricity.info.ElectricPropertiesUtils;

import com.power.usefulcomponents.uv.UvCuringRenderer;
import com.power.usefulcomponents.uv.UvPartialModels;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = UsefulComponents.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class UsefulComponentsClient {

    static {
        // Must run before models are registered, or the head renders as an error cube.
        UvPartialModels.init();
    }

    private UsefulComponentsClient() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.UV_CURING.get(), UvCuringRenderer::new);
    }

    /**
     * Item tooltip of the UV curing machine: Create's description (summary / conditions), the
     * "Stress Impact" lines Create shows for kinetic blocks, and Power Grid's electrical properties
     * (hold shift). Registered after the item exists, the same way Power Grid does it for its own items.
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void registerTooltips(RegisterEvent event) {
        if (!event.getRegistryKey().equals(Registries.ITEM))
            return;
        Item item = ModItems.UV_CURING_MACHINE.get();
        TooltipModifier electric = new TooltipModifier() {
            @Override
            public void modify(ItemTooltipEvent tooltipEvent) {
                if (item instanceof BlockItem blockItem && blockItem.getBlock() instanceof UvCuringBlock block)
                    ElectricPropertiesUtils.modify(block, tooltipEvent.getItemStack(), tooltipEvent.getEntity(),
                            tooltipEvent.getFlags(), tooltipEvent.getToolTip());
            }
        };
        TooltipModifier.REGISTRY.register(item,
                new ItemDescription.Modifier(item, FontHelper.Palette.STANDARD_CREATE)
                        .andThen(TooltipModifier.mapNull(KineticStats.create(item)))
                        .andThen(electric));
    }
}