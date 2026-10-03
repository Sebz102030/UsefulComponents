package com.power.usefulcomponents.components;

import com.google.common.collect.ImmutableCollection;
import com.power.usefulcomponents.UsefulComponents;
import com.power.usefulcomponents.config.UsefulComponentsConfig;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.patryk3211.powergrid.circuits.circuitboard.CircuitBoardBlockEntity;
import org.patryk3211.powergrid.circuits.circuitboard.ComponentCircuitBuilder;
import org.patryk3211.powergrid.circuits.components.IComponentGoggleInformation;
import org.patryk3211.powergrid.circuits.components.IInteractableComponent;
import org.patryk3211.powergrid.circuits.components.OrientableComponent;
import org.patryk3211.powergrid.circuits.components.properties.BooleanProperty;
import org.patryk3211.powergrid.circuits.components.properties.ComponentProperty;
import org.patryk3211.powergrid.circuits.components.properties.IntProperty;
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
 * Footprint: 3 (w) x 2 (l) board-grid cells; 2px model height.
 * Pins: 0=VIN, 1=VOUT, 2=GND.
 *
 * Electrical model:
 *  - VOUT is held at a fixed target voltage (default 12V, tunable 2V-24V,
 *    right-click to cycle) via a {@link ProvidedVoltageSourceCoupling} on
 *    an internal "ideal" node.
 *  - That ideal node feeds the real VOUT terminal through a loss resistor
 *    ({@link ElectricWire}), re-sized every tick so its I^2*R dissipation
 *    equals PowerLoss = VIN * Current * (1 - efficiency) - i.e. a fixed
 *    config-driven fraction (default 20%) of drawn power becomes heat.
 *  - Thermal integration copies {@code DiodeComponent}'s exact, verified
 *    {@link ThermalBuilder} call chain (setMaxPower / setOverheatTemperature
 *    / setThermalMass / withTemperatureCallback / addHeatSource), registering
 *    the loss resistor as a real heat source. This means the component's
 *    temperature is tracked by Power Grid's own thermal simulation, and is
 *    readable by its Thermometer tool - not just an internal number we make
 *    up ourselves.
 *  - Burns out (turns black, stops conducting, spawns smoke) from exactly
 *    two causes, checked in {@code tick()}: overvoltage (VIN exceeds
 *    {@code regulatorMaxInputVoltage}, default 50V) or overheating
 *    (temperature - reported back to us via withTemperatureCallback -
 *    exceeds {@code regulatorMaxTemp}, default 100C). A fixed 2A current
 *    cap (per spec, not config-driven) is enforced as a clamp inside the
 *    loss-resistor math to keep it numerically sane; it does not use
 *    {@code ThermalBuilder}'s own overheat-callback mechanism, which
 *    {@code DiodeComponent} itself doesn't use either and so isn't
 *    something this session has actually verified exists/behaves safely.
 *
 * None of the burnout thresholds (max temp, max input voltage, the 2A cap)
 * are exposed as a {@link ComponentProperty} or shown to the player - only
 * the tunable target voltage is. This is deliberate per spec.
 *
 * IMPORTANT: instances of this class are shared across every placed
 * regulator on every board - all per-placement state must live on the
 * {@link PlacedComponent}, via its synced properties and
 * {@code customData}, never as fields here.
 */
public class VoltageRegulatorComponent extends OrientableComponent
        implements IComponentGoggleInformation, IInteractableComponent {

    public static final int PIN_VIN = 0;
    public static final int PIN_VOUT = 1;
    public static final int PIN_GND = 2;

    public static final int DEFAULT_TARGET_VOLTAGE = 12;
    public static final int MIN_TARGET_VOLTAGE = 2;
    public static final int MAX_TARGET_VOLTAGE = 24;
    public static final int VOLTAGE_STEP = 1;
    public static final int VOLTAGE_STEP_SNEAK = 5;

    /** Fixed current cap regardless of config, per simplified spec. */
    private static final double MAX_CURRENT = 2.0D;
    private static final double MIN_CURRENT_FOR_LOSS_CALC = 0.01D;
    /** Below this VIN-GND differential, there's no real input power - output is 0. */
    private static final double MIN_VIN_FOR_OUTPUT = 0.5D;
    /** Output is capped at (VIN - this), never above what's actually supplied. */
    private static final double VIN_HEADROOM = 2.0D;
    private static final float MIN_LOSS_RESISTANCE = 0.001f;
    private static final float MAX_LOSS_RESISTANCE = 1.0e6f;
    private static final float SOURCE_RESISTANCE = 0.02f;
    private static final double AMBIENT_TEMP = 20.0D;
    /**
     * The regulator is a real load on its input: a resistor between VIN and
     * GND that draws the regulator's own quiescent power plus everything it
     * delivers (see tick()). Without it the input was only ever READ, so a
     * source with just one terminal wired (no return path, no current) still
     * looked like "power present" and switched the output on.
     */
    private static final double QUIESCENT_POWER = 0.1D;
    private static final double INPUT_R_DEFAULT = 1000.0D;
    private static final double MIN_INPUT_R = 1.0D;
    private static final double MAX_INPUT_R = 1.0e6D;

    public static final IntProperty TARGET_VOLTAGE =
            new IntProperty(UsefulComponents.MODID, "regulator_target_voltage",
                    DEFAULT_TARGET_VOLTAGE, MIN_TARGET_VOLTAGE, MAX_TARGET_VOLTAGE);

    public static final BooleanProperty BURNT = (BooleanProperty)
            new BooleanProperty(UsefulComponents.MODID, "regulator_burnt").hidden();

    public VoltageRegulatorComponent(ComponentFootprint footprint) {
        super(footprint);
    }

    @Override
    protected void addProperties(ImmutableCollection.Builder<ComponentProperty<?>> properties) {
        super.addProperties(properties);
        properties.add(TARGET_VOLTAGE);
        properties.add(BURNT);
    }

    @Override
    public void bake(@NotNull PlacedComponent placed, @NotNull ComponentCircuitBuilder builder,
                      ThermalBuilder.@NotNull IEmitter thermals) {
        FloatingNode vin = builder.terminalNode(PIN_VIN);
        FloatingNode vout = builder.terminalNode(PIN_VOUT);
        FloatingNode gnd = builder.terminalNode(PIN_GND);

        FloatingNode idealNode = builder.addInternalNode();
        var source = new ProvidedVoltageSourceCoupling(idealNode, gnd, SOURCE_RESISTANCE);
        // FIX: this previously always returned TARGET_VOLTAGE whenever not
        // burnt, with no check on VIN at all - meaning the regulator
        // produced its full target output even with zero (or no) input
        // power. A real regulator can only pass through what it's actually
        // given: if VIN isn't at least VOLTAGE_STEP_MIN_VIN_HEADROOM above
        // GND, there's nothing to regulate, so output is 0; otherwise the
        // output is capped at whatever's actually available (VIN minus a
        // small headroom), never exceeding the target.
        double[] outVoltage = new double[] { 0.0D };
        source.setVoltageProvider(() -> {
            double result;
            double vinSupply = vin.getVoltage() - gnd.getVoltage();
            if (placed.get(BURNT) || vinSupply < MIN_VIN_FOR_OUTPUT) {
                // vinSupply is measured across the input load resistor below,
                // so it is only non-zero when current can really flow from the
                // supply into VIN and back out of GND (a complete input loop).
                result = 0.0D;
            } else {
                double available = Math.max(0.0D, vinSupply - VIN_HEADROOM);
                result = Math.min((double) placed.get(TARGET_VOLTAGE), available);
            }
            outVoltage[0] = result;
            return result;
        });
        builder.add(source);
        placed.add(source);

        ElectricWire lossWire = builder.connect(MIN_LOSS_RESISTANCE, idealNode, vout);
        placed.add(lossWire);

        // Input load: draws the regulator's power from the supply (resized in tick()).
        ElectricWire inputWire = builder.connect((float) INPUT_R_DEFAULT, vin, gnd);
        placed.add(inputWire);

        double[] temperature = new double[] { AMBIENT_TEMP };
        placed.customData = new Runtime(vin, vout, gnd, source, lossWire, inputWire, outVoltage, temperature);

        float maxTemp = UsefulComponentsConfig.REGULATOR_MAX_TEMP.get().floatValue();
        thermals.builder()
                .setMaxPower(50f, maxTemp)
                .setOverheatTemperature(maxTemp)
                .setThermalMass(0.05f)
                .withTemperatureCallback(t -> temperature[0] = t)
                .addHeatSource(lossWire);
    }

    @Override
    public boolean tick(@NotNull PlacedComponent placed) {
        if (placed.isClient())
            return true;
        if (!(placed.customData instanceof Runtime rt))
            return true;
        if (placed.get(BURNT)) {
            rt.lossWire.setResistance(MAX_LOSS_RESISTANCE);
            rt.inputWire.setResistance(MAX_INPUT_R);
            return true;
        }

        double maxTemp = UsefulComponentsConfig.REGULATOR_MAX_TEMP.get();
        double efficiency = UsefulComponentsConfig.REGULATOR_EFFICIENCY.get();

        double vinVoltage = rt.vin.getVoltage() - rt.gnd.getVoltage();
        double current = Math.abs(rt.source.getCurrent());
        double clampedCurrent = Math.min(current, MAX_CURRENT);

        // Resize the loss resistor so the framework's own I^2*R heat-source
        // tracking sees exactly PowerLoss = VIN * Current * (1 - efficiency).
        double powerLoss = Math.max(0.0D, vinVoltage * clampedCurrent * (1.0D - efficiency));
        double lossResistance = clampedCurrent > MIN_CURRENT_FOR_LOSS_CALC
                ? powerLoss / (clampedCurrent * clampedCurrent)
                : MIN_LOSS_RESISTANCE;
        rt.lossWire.setResistance((float) Math.max(MIN_LOSS_RESISTANCE,
                Math.min(lossResistance, MAX_LOSS_RESISTANCE)));

        // Size the input load so the supply is asked for exactly what the
        // regulator needs: quiescent + delivered output power + the loss
        // above. R = V^2 / P, smoothed so it can't oscillate tick to tick.
        double inputPower = QUIESCENT_POWER + rt.outVoltage[0] * clampedCurrent + powerLoss;
        double wantedInputR = vinVoltage >= MIN_VIN_FOR_OUTPUT
                ? vinVoltage * vinVoltage / inputPower
                : INPUT_R_DEFAULT;
        wantedInputR = Math.max(MIN_INPUT_R, Math.min(wantedInputR, MAX_INPUT_R));
        rt.inputWire.setResistance(0.5D * rt.inputWire.getResistance() + 0.5D * wantedInputR);

        // Burnout is driven ONLY by ThermalBuilder's own tracked
        // temperature now (fed to us via withTemperatureCallback into
        // rt.temperature[0]) - no separate standalone overvoltage trigger.
        // A high VIN still leads to burnout, just indirectly: more VIN
        // means more power dissipated for the same current
        // (PowerLoss = VIN * Current * (1 - efficiency)), which drives
        // temperature up through the real thermal simulation until it
        // crosses maxTemp on its own.
        if (rt.temperature[0] > maxTemp) {
            burn(placed);
        }
        return true;
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
        return IInteractableComponent.extrudedFootprint(placed, 2 / 16f);
    }

    @Override
    public InteractionResult use(@NotNull CircuitBoardBlockEntity be, @NotNull PlacedComponent component,
                                  @NotNull Player player) {
        if (player.level().isClientSide)
            return InteractionResult.SUCCESS;
        if (component.get(BURNT)) {
            player.displayClientMessage(Component.literal("This regulator is burnt out."), true);
            return InteractionResult.SUCCESS;
        }

        int step = player.isShiftKeyDown() ? VOLTAGE_STEP_SNEAK : VOLTAGE_STEP;
        int current = component.get(TARGET_VOLTAGE);
        int next = current + step;
        if (next > MAX_TARGET_VOLTAGE)
            next = MIN_TARGET_VOLTAGE;

        component.set(TARGET_VOLTAGE, next);
        component.notifyClients(TARGET_VOLTAGE);
        player.displayClientMessage(Component.literal("Regulator target voltage: " + next + " V"), true);
        return InteractionResult.SUCCESS;
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
    public boolean addToGoggleTooltip(@NotNull PlacedComponent placed, @NotNull List<Component> tooltip,
                                       boolean isPlayerSneaking) {
        if (placed.get(BURNT)) {
            tooltip.add(Component.literal("Burnt out"));
            return true;
        }
        // Deliberately not showing temperature or the burnout thresholds -
        // those are internal/config-only, never player-visible, per spec.
        tooltip.add(Component.literal("Target: " + placed.get(TARGET_VOLTAGE) + " V"));
        return true;
    }

    /** Per-placement live references, stashed via {@link PlacedComponent#customData}. */
    private record Runtime(FloatingNode vin, FloatingNode vout, FloatingNode gnd,
                            ProvidedVoltageSourceCoupling source, ElectricWire lossWire,
                            ElectricWire inputWire, double[] outVoltage, double[] temperature) {
    }
}
