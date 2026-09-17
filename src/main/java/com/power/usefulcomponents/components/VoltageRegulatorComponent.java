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
    private static final float MIN_LOSS_RESISTANCE = 0.001f;
    private static final float MAX_LOSS_RESISTANCE = 1.0e6f;
    private static final float SOURCE_RESISTANCE = 0.02f;
    private static final double AMBIENT_TEMP = 20.0D;

    public static final IntProperty TARGET_VOLTAGE =
            new IntProperty(UsefulComponents.MODID, "regulator_target_voltage",
                    DEFAULT_TARGET_VOLTAGE, MIN_TARGET_VOLTAGE, MAX_TARGET_VOLTAGE);

    public static final BooleanProperty BURNT =
            new BooleanProperty(UsefulComponents.MODID, "regulator_burnt");

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
        source.setVoltageProvider(() -> placed.get(BURNT) ? 0.0D : (double) placed.get(TARGET_VOLTAGE));
        builder.add(source);
        placed.add(source);

        ElectricWire lossWire = builder.connect(MIN_LOSS_RESISTANCE, idealNode, vout);
        placed.add(lossWire);

        double[] temperature = new double[] { AMBIENT_TEMP };
        placed.customData = new Runtime(vin, vout, gnd, source, lossWire, temperature);

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
            return true;
        }

        double maxInputVoltage = UsefulComponentsConfig.REGULATOR_MAX_INPUT_VOLTAGE.get();
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

        // Two burnout causes only, per spec: overvoltage or overheating.
        // (The 2A figure above is a numerical clamp for the loss-resistor
        // math, not a third burnout trigger.)
        if (vinVoltage > maxInputVoltage || rt.temperature[0] > maxTemp) {
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
                            double[] temperature) {
    }
}
