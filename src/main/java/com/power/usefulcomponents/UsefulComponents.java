package com.power.usefulcomponents;

import com.power.usefulcomponents.config.UsefulComponentsConfig;
import com.power.usefulcomponents.registry.ModBlockEntities;
import com.power.usefulcomponents.registry.ModBlocks;
import com.power.usefulcomponents.registry.ModItems;
import com.power.usefulcomponents.registry.ModRecipes;
import com.simibubi.create.api.stress.BlockStressValues;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.patryk3211.powergrid.config.ResistanceValues;
import com.power.usefulcomponents.config.UsefulComponentsConfig;
import java.util.function.DoubleSupplier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.core.registries.Registries;
import org.patryk3211.powergrid.PowerGrid;

@Mod(UsefulComponents.MODID)
public class UsefulComponents {

    public static final String MODID = "usefulcomponents";
    public static final Logger LOGGER = LogManager.getLogger(MODID);
    private static final ResourceKey<CreativeModeTab> POWERGRID_MAIN_TAB =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, PowerGrid.asResource("main"));

    public UsefulComponents(IEventBus modEventBus, ModContainer modContainer) {
        // Register the common config, "Configured"-mod compatible.
        modContainer.registerConfig(ModConfig.Type.COMMON, UsefulComponentsConfig.SPEC,
                MODID + "-common.toml");

        ModBlocks.BLOCKS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModRecipes.SERIALIZERS.register(modEventBus);
        ModRecipes.TYPES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);

        modEventBus.addListener(this::commonSetup);

        modEventBus.addListener(this::addItemsToPowerGridTab);
        LOGGER.info("Useful Components initializing");
    }
    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // Lamp resistance of the UV curing machine comes from this mod's config.
            ResistanceValues.register(new ResistanceValues.Provider() {
                @Override
                public DoubleSupplier get(Block block) {
                    return block == ModBlocks.UV_CURING.get() ? UsefulComponentsConfig.UV_COIL_RESISTANCE::get : null;
                }

                @Override
                public DoubleSupplier get(Block block, String suffix) {
                    return null;
                }
            });
            // Stress impact (SU per RPM) of the UV curing machine.
            BlockStressValues.IMPACTS.registerProvider(block ->
                    block == ModBlocks.UV_CURING.get() ? UsefulComponentsConfig.UV_STRESS_IMPACT::get : null);
        });
    }

    private void addItemsToPowerGridTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() != POWERGRID_MAIN_TAB)
            return;

        for (var entry : ModItems.ITEMS.getEntries()) {
            event.accept(entry.get());
            //event.accept(ModItems.DIODE_WHITW.get());
        }
    }
}
