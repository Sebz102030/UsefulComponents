package com.power.usefulcomponents;

import com.power.usefulcomponents.config.UsefulComponentsConfig;
import com.power.usefulcomponents.registry.ModItems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(UsefulComponents.MODID)
public class UsefulComponents {

    public static final String MODID = "usefulcomponents";
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    public UsefulComponents(IEventBus modEventBus, ModContainer modContainer) {
        // Register the common config, "Configured"-mod compatible.
        modContainer.registerConfig(ModConfig.Type.COMMON, UsefulComponentsConfig.SPEC,
                MODID + "-common.toml");

        ModItems.ITEMS.register(modEventBus);

        LOGGER.info("Useful Components initializing");
    }
}
