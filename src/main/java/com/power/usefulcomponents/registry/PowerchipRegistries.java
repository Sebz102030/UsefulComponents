package com.power.usefulcomponents.registry;

import com.power.usefulcomponents.UsefulComponents;
import com.power.usefulcomponents.components.DifferentialComparatorComponent;
import com.power.usefulcomponents.components.SignalOscillatorComponent;
import com.power.usefulcomponents.components.VoltageRegulatorComponent;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.patryk3211.powergrid.circuits.components.ComponentRegistry;
import org.patryk3211.powergrid.circuits.schematic.ComponentFootprint;

@EventBusSubscriber(modid = UsefulComponents.MODID)
public final class PowerchipRegistries {

    public static final ResourceLocation VOLTAGE_REGULATOR_ID = id("voltage_regulator");
    public static final ResourceLocation SIGNAL_OSCILLATOR_ID = id("signal_oscillator");
    public static final ResourceLocation DIFFERENTIAL_COMPARATOR_ID = id("differential_comparator");

    private PowerchipRegistries() {
    }

    private static VoltageRegulatorComponent buildVoltageRegulator() {
        // 3 (w) x 3 (l) footprint. VIN/VOUT on the top row, GND/VSTEER on
        // the bottom row.
        String base = "component." + UsefulComponents.MODID + ".voltage_regulator";
        var footprint = new ComponentFootprint.Builder(3, 3, base, null)
                .addPad(0, 0, VoltageRegulatorComponent.PIN_VIN, "VIN", "VIN")
                .addPad(2, 0, VoltageRegulatorComponent.PIN_VOUT, "VOUT", "VOUT")
                .addPad(0, 2, VoltageRegulatorComponent.PIN_GND, "GND", "GND")
                .addPad(2, 2, VoltageRegulatorComponent.PIN_VSTEER, "VSTEER", "VST")
                .withItem()
                .withOutline()
                .build();
        return new VoltageRegulatorComponent(footprint);
    }

    private static SignalOscillatorComponent buildSignalOscillator() {
        // 2 (w) x 3 (l) footprint.
        String base = "component." + UsefulComponents.MODID + ".signal_oscillator";
        var footprint = new ComponentFootprint.Builder(2, 3, base, null)
                .addPad(0, 0, SignalOscillatorComponent.PIN_VIN, "VIN", "VIN")
                .addPad(1, 0, SignalOscillatorComponent.PIN_GND, "GND", "GND")
                .addPad(0, 2, SignalOscillatorComponent.PIN_SIGNAL, "SIGNAL", "SIG")
                .withItem()
                .withOutline()
                .build();
        return new SignalOscillatorComponent(footprint);
    }

    private static DifferentialComparatorComponent buildDifferentialComparator() {
        // 2 (w) x 3 (l) footprint.
        String base = "component." + UsefulComponents.MODID + ".differential_comparator";
        var footprint = new ComponentFootprint.Builder(2, 3, base, null)
                .addPad(0, 0, DifferentialComparatorComponent.PIN_VCC, "VCC", "VCC")
                .addPad(1, 0, DifferentialComparatorComponent.PIN_GND, "GND", "GND")
                .addPad(0, 1, DifferentialComparatorComponent.PIN_IN_PLUS, "IN+", "IN+")
                .addPad(0, 2, DifferentialComparatorComponent.PIN_IN_MINUS, "IN-", "IN-")
                .addPad(1, 2, DifferentialComparatorComponent.PIN_OUT, "OUT", "OUT")
                .withItem()
                .withOutline()
                .build();
        return new DifferentialComparatorComponent(footprint);
    }

    @SubscribeEvent
    public static void onRegister(RegisterEvent event) {
        // Matches the exact pattern used by Create-Power-Chip's own
        // ModComponents.java: guard on the registry key, then register each
        // component with the simple (key, id, supplier) overload.
        if (event.getRegistryKey().equals(ComponentRegistry.REGISTRY_KEY)) {
            //event.register(ComponentRegistry.REGISTRY_KEY, VOLTAGE_REGULATOR_ID, () -> buildVoltageRegulator());
            event.register(ComponentRegistry.REGISTRY_KEY, SIGNAL_OSCILLATOR_ID, () -> buildSignalOscillator());
            event.register(ComponentRegistry.REGISTRY_KEY, DIFFERENTIAL_COMPARATOR_ID, () -> buildDifferentialComparator());
            UsefulComponents.LOGGER.info("Registered {} custom powergrid components", 3);
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(UsefulComponents.MODID, path);
    }
}
