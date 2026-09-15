package com.power.usefulcomponents.components;

import com.google.common.collect.ImmutableCollection;
import com.power.usefulcomponents.UsefulComponents;
import com.power.usefulcomponents.config.UsefulComponentsConfig;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.patryk3211.powergrid.circuits.circuitboard.ComponentCircuitBuilder;
import org.patryk3211.powergrid.circuits.components.IComponentGoggleInformation;
import org.patryk3211.powergrid.circuits.components.IInteractableComponent;
import org.patryk3211.powergrid.circuits.components.OrientableComponent;
import org.patryk3211.powergrid.circuits.components.properties.BooleanProperty;
import org.patryk3211.powergrid.circuits.components.properties.ComponentProperty;
import org.patryk3211.powergrid.circuits.schematic.ComponentFootprint;
import org.patryk3211.powergrid.circuits.schematic.PlacedComponent;
import org.patryk3211.powergrid.circuits.thermal.ThermalBuilder;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.ProvidedVoltageSourceCoupling;

import java.util.List;

/**
 * Adjustable Voltage Regulator.
 *
 * Footprint: 3 (w) x 3 (l) board-grid cells ("px" in the board's 16x16 grid,
 * matching {@code ComponentFootprint}'s coordinate space 1:1 - see
 * {@link com.power.usefulcomponents.registry.PowerchipRegistries}). The
 * requested 2px model height is a voxel-shape/render concern, not a
 * footprint dimension (Power Grid's board components are 2D footprints);
 * it's expressed below via {@link IInteractableComponent#extrudedFootprint}.
 *
 * Pins: 0=VIN, 1=VOUT, 2=GND, 3=VSTEER.
 *
 * Electrical model:
 *  - An internal {@link ProvidedVoltageSourceCoupling} targets
 *    VOUT = VSTEER + 2V, clamped so the VOUT-GND differential never exceeds
 *    20V and VOUT never exceeds VIN - 2V. The provider recomputes this each
 *    solver iteration from the previous iteration's node voltages, which is
 *    exactly what {@code ProvidedVoltageSourceCoupling} exists for.
 *  - That ideal internal node feeds the real VOUT terminal through a
 *    dynamically-sized loss resistor. Each tick we resize that resistor so
 *    its I^2*R dissipation equals PowerLoss = (VIN * Current) * (1 - eff);
 *    this both models efficiency loss physically and lets us register the
 *    resistor as a normal {@link ThermalBuilder} heat source, so burnout on
 *    over-temperature comes from the framework's own thermal simulation
 *    instead of a hand-rolled one.
 *  - Overcurrent and the linear voltage/current burnout curve are checked
 *    each tick directly against {@code regulatorMaxCurrent},
 *    {@code regulatorMaxVoltageAt1A} and {@code regulatorMaxVoltageAtMaxCurrent}.
 *
 * IMPORTANT: instances of this class are shared across every placed
 * regulator on every board (same convention as vanilla Power Grid
 * components like ResistorComponent) - all per-placement state (burnt flag,
 * live node/wire references) must live on the {@link PlacedComponent}, via
 * its synced properties and {@code customData}, never as fields here.
 */

//todo fix crash on place

public class VoltageRegulatorComponent extends OrientableComponent
        implements IComponentGoggleInformation, IInteractableComponent {

    public static final int PIN_VIN = 0;
    public static final int PIN_VOUT = 1;
    public static final int PIN_GND = 2;
    public static final int PIN_VSTEER = 3;

    private static final double VOUT_STEER_OFFSET = 2.0D;
    private static final double MAX_VOUT_DIFFERENTIAL = 20.0D;
    private static final double VIN_HEADROOM = 2.0D;
    private static final double MIN_CURRENT_FOR_LOSS_CALC = 0.01D;
    private static final float MIN_LOSS_RESISTANCE = 0.001f;
    private static final float MAX_LOSS_RESISTANCE = 1.0e6f;
    /** Small series resistance of the ideal internal source, for solver stability. */
    private static final float SOURCE_RESISTANCE = 0.02f;

    // Using the 2-arg constructor (default false) rather than the 3-arg
    // overload: some published Power Grid builds don't have the 3-arg
    // overload yet, so this is the safer, universally-available call.
    public static final BooleanProperty BURNT =
            new BooleanProperty(UsefulComponents.MODID, "regulator_burnt");

    public VoltageRegulatorComponent(ComponentFootprint footprint) {
        super(footprint);
    }
/*
    @Override
    protected void addProperties(ImmutableCollection.Builder<ComponentProperty<?>> properties) {
        super.addProperties(properties);
        properties.add(BURNT);
    }*/

    @Override
    public void bake(@NotNull PlacedComponent placed, @NotNull ComponentCircuitBuilder builder,
                      ThermalBuilder.@NotNull IEmitter thermals) {
        FloatingNode vin = builder.terminalNode(PIN_VIN);
        FloatingNode vout = builder.terminalNode(PIN_VOUT);
        FloatingNode gnd = builder.terminalNode(PIN_GND);
        FloatingNode vsteer = builder.terminalNode(PIN_VSTEER);

        FloatingNode idealNode = builder.addInternalNode();

        var source = new ProvidedVoltageSourceCoupling(idealNode, gnd, SOURCE_RESISTANCE);
        source.setVoltageProvider(() -> computeTargetDifferential(placed, vin, vsteer, gnd));
        builder.add(source);
        placed.add(source);

        // Loss resistor between the ideal regulated node and the real VOUT
        // terminal; its resistance is re-tuned every tick() so its I^2*R
        // dissipation equals the configured-efficiency power loss.
        ElectricWire lossWire = builder.connect(MIN_LOSS_RESISTANCE, idealNode, vout);
        placed.add(lossWire);

        placed.customData = new Runtime(vin, vout, gnd, source, lossWire);

        thermals.builder()
                .setThermalMass(0.05f)
                // Reference point only: at 50W steady dissipation the unit settles
                // near its configured max temperature. Tune to taste.
                // .floatValue() rather than (float) cast: casting a boxed
                // Double straight to float is an illegal narrowing cast in
                // Java; floatValue() does the narrowing itself.
                .setMaxPower(50f, UsefulComponentsConfig.REGULATOR_MAX_TEMP.get().floatValue())
                .setOverheatTemperature(UsefulComponentsConfig.REGULATOR_MAX_TEMP.get().floatValue())
                .withOverheatCallback(() -> burn(placed))
                .addHeatSource(lossWire);
    }

    @Override
    public boolean tick(@NotNull PlacedComponent placed) {
        if (placed.isClient())
            return true;
        if (!(placed.customData instanceof Runtime rt))
            return true;
        if (placed.get(BURNT)) {
            // Ensure a burnt regulator well and truly stops conducting even
            // if it was burnt via the manual checks below rather than the
            // thermal overheat callback.
            rt.lossWire.setResistance(MAX_LOSS_RESISTANCE);
            return true;
        }

        double efficiency = UsefulComponentsConfig.REGULATOR_EFFICIENCY.get();
        double maxCurrent = UsefulComponentsConfig.REGULATOR_MAX_CURRENT.get();
        double maxVoltageAt1A = UsefulComponentsConfig.REGULATOR_MAX_VOLTAGE_AT_1A.get();
        double maxVoltageAtMaxCurrent = UsefulComponentsConfig.REGULATOR_MAX_VOLTAGE_AT_MAX_CURRENT.get();

        double current = Math.abs(rt.source.getCurrent());
        double vinVoltage = rt.vin.getVoltage();
        double voutVoltage = Math.abs(rt.vout.getVoltage() - rt.gnd.getVoltage());

        double powerLoss = Math.max(0.0D, vinVoltage * current * (1.0D - efficiency));
        double lossResistance = current > MIN_CURRENT_FOR_LOSS_CALC
                ? powerLoss / (current * current)
                : MIN_LOSS_RESISTANCE;
        rt.lossWire.setResistance((float) Math.max(MIN_LOSS_RESISTANCE,
                Math.min(lossResistance, MAX_LOSS_RESISTANCE)));

        double burnoutCurve = burnoutVoltageThreshold(current, maxCurrent, maxVoltageAt1A, maxVoltageAtMaxCurrent);
        if (current > maxCurrent || voutVoltage > burnoutCurve) {
            burn(placed);
        }
        return true;
    }

    /** (VOUT - VSTEER) = 2V target, clamped, returned as the positive-negative differential the coupling expects. */
    private static double computeTargetDifferential(@NotNull PlacedComponent placed, FloatingNode vin,
                                                      FloatingNode vsteer, FloatingNode gnd) {
        if (placed.get(BURNT))
            return 0.0D;

        double target = vsteer.getVoltage() + VOUT_STEER_OFFSET;
        target = Math.min(target, MAX_VOUT_DIFFERENTIAL);
        target = Math.min(target, vin.getVoltage() - VIN_HEADROOM);
        target = Math.max(target, 0.0D);
        return target - gnd.getVoltage();
    }

    /** Linear interpolation between (1A, voltageAt1A) and (maxCurrent, voltageAtMaxCurrent). */
    private static double burnoutVoltageThreshold(double current, double maxCurrent,
                                                    double voltageAt1A, double voltageAtMaxCurrent) {
        double lowCurrent = 1.0D;
        if (maxCurrent <= lowCurrent)
            return voltageAtMaxCurrent;
        double clamped = Math.max(lowCurrent, Math.min(current, maxCurrent));
        double t = (clamped - lowCurrent) / (maxCurrent - lowCurrent);
        return voltageAt1A + t * (voltageAtMaxCurrent - voltageAt1A);
    }

    private static void burn(@NotNull PlacedComponent placed) {
        if (placed.get(BURNT))
            return;
        placed.set(BURNT, true);
        placed.notifyClients(BURNT);
        modelChanged(placed.getPos());
        placed.onClientWorld(() -> world -> {
            var pos = placed.getExactPos();
            for (int i = 0; i < 8; i++) {
                world.addParticle(ParticleTypes.SMOKE, pos.x, pos.y + 0.1, pos.z,
                        (world.random.nextDouble() - 0.5) * 0.05, 0.05, (world.random.nextDouble() - 0.5) * 0.05);
            }
        });
    }

    @Override
    public VoxelShape getShape(@NotNull PlacedComponent placed) {
        // Requested 2px model height, expressed as a fraction of a full block.
        return IInteractableComponent.extrudedFootprint(placed, 2 / 16f);
    }

    @Override
    public net.minecraft.world.InteractionResult use(
            org.patryk3211.powergrid.circuits.circuitboard.CircuitBoardBlockEntity be,
            PlacedComponent component, net.minecraft.world.entity.player.Player player) {
        // No player-facing tuning for this component (VSTEER is wired, not
        // dialed in-world), so interaction is a no-op. IInteractableComponent
        // is still implemented so getShape() participates in click/goggle
        // targeting like other 3D board components (see FuseHolderComponent).
        return net.minecraft.world.InteractionResult.PASS;
    }

    @Override
    public @NotNull ResourceLocation getModelId(@NotNull PlacedComponent component) {
        return component.get(BURNT)
                ? ResourceLocation.fromNamespaceAndPath(UsefulComponents.MODID, "voltage_regulator_burnt")
                : super.getModelId(component);
    }

    @Override
    public @NotNull java.util.Collection<ResourceLocation> requestedModels() {
        return List.of(
                ResourceLocation.fromNamespaceAndPath(UsefulComponents.MODID, "voltage_regulator"),
                ResourceLocation.fromNamespaceAndPath(UsefulComponents.MODID, "voltage_regulator_burnt")
        );
    }

    @Override
    public boolean addToGoggleTooltip(@NotNull PlacedComponent placed,
                                       @NotNull List<net.minecraft.network.chat.Component> tooltip,
                                       boolean isPlayerSneaking) {
        if (placed.get(BURNT)) {
            tooltip.add(net.minecraft.network.chat.Component.literal("Burnt out"));
            return true;
        }
        if (!(placed.customData instanceof Runtime rt))
            return false;
        tooltip.add(net.minecraft.network.chat.Component.literal(
                String.format("VOUT: %.1fV", rt.vout.getVoltage() - rt.gnd.getVoltage())));
        return true;
    }

    /** Per-placement live references, stashed via {@link PlacedComponent#customData}. */
    private record Runtime(FloatingNode vin, FloatingNode vout, FloatingNode gnd,
                            ProvidedVoltageSourceCoupling source, ElectricWire lossWire) {
    }
}
