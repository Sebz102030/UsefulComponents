package com.power.usefulcomponents.components;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.patryk3211.powergrid.circuits.circuitboard.ComponentCircuitBuilder;
import org.patryk3211.powergrid.circuits.components.IComponentGoggleInformation;
import org.patryk3211.powergrid.circuits.components.OrientableComponent;
import org.patryk3211.powergrid.circuits.schematic.ComponentFootprint;
import org.patryk3211.powergrid.circuits.schematic.PlacedComponent;
import org.patryk3211.powergrid.circuits.thermal.ThermalBuilder;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.ProvidedVoltageSourceCoupling;

import java.util.List;

public class DifferentialComparatorComponent extends OrientableComponent implements IComponentGoggleInformation {

    public static final int PIN_VCC = 0;
    public static final int PIN_GND = 1;
    public static final int PIN_IN_PLUS = 2;
    public static final int PIN_IN_MINUS = 3;
    public static final int PIN_OUT = 4;

    private static final float OUTPUT_RESISTANCE = 1.0f;

    public DifferentialComparatorComponent(ComponentFootprint footprint) {
        super(footprint);
    }

    @Override
    public void bake(@NotNull PlacedComponent placed, @NotNull ComponentCircuitBuilder builder,
                      ThermalBuilder.@NotNull IEmitter thermals) {
        FloatingNode vcc = builder.terminalNode(PIN_VCC);
        FloatingNode gnd = builder.terminalNode(PIN_GND);
        FloatingNode out = builder.terminalNode(PIN_OUT);
        FloatingNode inPlus = builder.terminalNode(PIN_IN_PLUS);
        FloatingNode inMinus = builder.terminalNode(PIN_IN_MINUS);
        

        var source = new ProvidedVoltageSourceCoupling(out, gnd, OUTPUT_RESISTANCE);
        source.setVoltageProvider(() -> computeOutputDifferential(vcc, gnd, inPlus, inMinus));
        builder.add(source);
        placed.add(source);
    }

    private static double computeOutputDifferential(FloatingNode vcc, FloatingNode gnd,
                                                      FloatingNode inPlus, FloatingNode inMinus) {
        double railLow = Math.min(gnd.getVoltage(), vcc.getVoltage());
        double railHigh = Math.max(gnd.getVoltage(), vcc.getVoltage());

        double raw = inPlus.getVoltage() - inMinus.getVoltage();
        double absoluteOutput = Math.max(railLow, Math.min(raw, railHigh));

        return absoluteOutput - gnd.getVoltage();
    }

    @Override
    public @NotNull ResourceLocation getModelId(@NotNull PlacedComponent component) {
        return super.getModelId(component);
    }

    @Override
    public boolean addToGoggleTooltip(@NotNull PlacedComponent placed, @NotNull List<Component> tooltip,
                                       boolean isPlayerSneaking) {
        tooltip.add(Component.literal("Differential comparator (OUT = IN+ - IN-)"));
        return true;
    }
}