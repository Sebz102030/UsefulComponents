package com.power.usefulcomponents.components;

import com.google.common.collect.ImmutableCollection;
import com.power.usefulcomponents.UsefulComponents;
import com.power.usefulcomponents.config.UsefulComponentsConfig;
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
import org.patryk3211.powergrid.circuits.components.properties.ComponentProperty;
import org.patryk3211.powergrid.circuits.components.properties.IntProperty;
import org.patryk3211.powergrid.circuits.schematic.ComponentFootprint;
import org.patryk3211.powergrid.circuits.schematic.PlacedComponent;
import org.patryk3211.powergrid.circuits.thermal.ThermalBuilder;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.ProvidedVoltageSourceCoupling;

import java.util.List;

/**
 * Signal Oscillator.
 *
 * Footprint: 2 (w) x 3 (l) board-grid cells; extends {@link OrientableComponent}
 * so 90-degree rotation on the board is free (the framework rotates the
 * footprint and remaps pads automatically - no custom rotation logic
 * needed). The requested 4px model height is a voxel-shape concern
 * expressed via {@link IInteractableComponent#extrudedFootprint}, not part
 * of the 2D footprint.
 *
 * Pins: 0=VIN, 1=GND, 2=SIGNAL.
 *
 * Behavior:
 *  - Requires (VIN - GND) >= 1.0V to oscillate; otherwise SIGNAL is held low.
 *  - Produces a 0V/supply-amplitude square wave on SIGNAL via a
 *    {@link ProvidedVoltageSourceCoupling} whose provider is recomputed
 *    every solver iteration from the board's absolute game time, so
 *    multiple oscillators at the same frequency stay in phase.
 *  - Frequency is a synced, player-tunable {@link IntProperty}, adjusted by
 *    right-clicking (cycles +1 Hz, or +10 Hz while sneaking, wrapping back
 *    to 1 Hz past the effective max), clamped every solve iteration against
 *    both the 240 Hz hard cap and the live {@code oscillatorMaxFrequency}
 *    config value.
 *
 * NOTE: an earlier draft of this tuned frequency through a
 * CustomValueSettingsScreen slider (matching PotentiometerComponent's real
 * in-game UI). That pulls in Create's Catnip/Ponder libraries transitively,
 * which aren't necessarily on your compile classpath unless you also add
 * Create itself as a dependency (Power Grid alone isn't enough). This
 * simpler right-click-cycle interaction needs nothing beyond Power Grid and
 * vanilla, and reuses the same synced-property mechanism already used for
 * VoltageRegulatorComponent's burnt state. Swap in the slider UI later if/
 * when Create + Catnip + Ponder are wired into your build.gradle.
 */
public class SignalOscillatorComponent extends OrientableComponent
        implements IComponentGoggleInformation, IInteractableComponent {

    public static final int PIN_VIN = 0;
    public static final int PIN_GND = 1;
    public static final int PIN_SIGNAL = 2;

    public static final int DEFAULT_FREQUENCY_HZ = 1;
    public static final int HARD_CAP_FREQUENCY_HZ = 240;

    private static final double MIN_ACTIVATION_VOLTAGE = 1.0D;
    private static final float SOURCE_RESISTANCE = 1.0f;
    private static final double SIMULATION_TICKS_PER_SECOND = 20.0D;

    public static final IntProperty FREQUENCY =
            new IntProperty(UsefulComponents.MODID, "oscillator_frequency",
                    DEFAULT_FREQUENCY_HZ, 1, HARD_CAP_FREQUENCY_HZ);

    public SignalOscillatorComponent(ComponentFootprint footprint) {
        super(footprint);
    }

    @Override
    protected void addProperties(ImmutableCollection.Builder<ComponentProperty<?>> properties) {
        super.addProperties(properties);
        properties.add(FREQUENCY);
    }

    @Override
    public void bake(@NotNull PlacedComponent placed, @NotNull ComponentCircuitBuilder builder,
                      ThermalBuilder.@NotNull IEmitter thermals) {
        FloatingNode vin = builder.terminalNode(PIN_VIN);
        FloatingNode gnd = builder.terminalNode(PIN_GND);
        FloatingNode signal = builder.terminalNode(PIN_SIGNAL);

        var source = new ProvidedVoltageSourceCoupling(signal, gnd, SOURCE_RESISTANCE);
        source.setVoltageProvider(() -> computeSignalDifferential(placed, vin, gnd));
        builder.add(source);
        placed.add(source);
        // No thermal modeling requested for this component.
    }

    private static double computeSignalDifferential(@NotNull PlacedComponent placed,
                                                      FloatingNode vin, FloatingNode gnd) {
        double supply = vin.getVoltage() - gnd.getVoltage();
        if (supply < MIN_ACTIVATION_VOLTAGE)
            return 0.0D;

        int frequency = effectiveFrequency(placed);
        double periodTicks = SIMULATION_TICKS_PER_SECOND / frequency;
        long gameTime = placed.getWorld().getGameTime();
        double phase = (gameTime % Math.max(1L, Math.round(periodTicks))) / periodTicks;

        return phase < 0.5D ? supply : 0.0D;
    }

    private static int effectiveFrequency(@NotNull PlacedComponent placed) {
        int configMax = UsefulComponentsConfig.OSCILLATOR_MAX_FREQUENCY.get();
        int effectiveMax = Math.min(HARD_CAP_FREQUENCY_HZ, configMax);
        return Math.max(1, Math.min(placed.get(FREQUENCY), effectiveMax));
    }

    @Override
    public VoxelShape getShape(@NotNull PlacedComponent placed) {
        // Requested 4px model height, expressed as a fraction of a full block.
        return IInteractableComponent.extrudedFootprint(placed, 4 / 16f);
    }

    @Override
    public InteractionResult use(@NotNull CircuitBoardBlockEntity be, @NotNull PlacedComponent component,
                                  @NotNull Player player) {
        if (player.level().isClientSide)
            return InteractionResult.SUCCESS;

        int configMax = UsefulComponentsConfig.OSCILLATOR_MAX_FREQUENCY.get();
        int effectiveMax = Math.min(HARD_CAP_FREQUENCY_HZ, configMax);

        int step = player.isShiftKeyDown() ? 10 : 1;
        int current = component.get(FREQUENCY);
        int next = current + step;
        if (next > effectiveMax)
            next = 1;

        component.set(FREQUENCY, next);
        component.notifyClients(FREQUENCY);
        player.displayClientMessage(Component.literal("Oscillator frequency: " + next + " Hz"), true);
        return InteractionResult.SUCCESS;
    }

    @Override
    public @NotNull ResourceLocation getModelId(@NotNull PlacedComponent component) {
        return super.getModelId(component);
    }

    @Override
    public boolean addToGoggleTooltip(@NotNull PlacedComponent placed, @NotNull List<Component> tooltip,
                                       boolean isPlayerSneaking) {
        tooltip.add(Component.literal("Frequency: " + placed.get(FREQUENCY) + " Hz"));
        return true;
    }
}
