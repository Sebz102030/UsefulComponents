package com.power.usefulcomponents.components;

import com.google.common.collect.ImmutableCollection;
import com.power.usefulcomponents.UsefulComponents;
import com.power.usefulcomponents.compat.SableCompat;
import com.power.usefulcomponents.config.UsefulComponentsConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.patryk3211.powergrid.circuits.circuitboard.CircuitBoardBlock;
import org.patryk3211.powergrid.circuits.circuitboard.CircuitBoardBlockEntity;
import org.patryk3211.powergrid.circuits.circuitboard.ComponentCircuitBuilder;
import org.patryk3211.powergrid.circuits.components.IComponentGoggleInformation;
import org.patryk3211.powergrid.circuits.components.IInteractableComponent;
import org.patryk3211.powergrid.circuits.components.OrientableComponent;
import org.patryk3211.powergrid.circuits.components.properties.BooleanProperty;
import org.patryk3211.powergrid.circuits.components.properties.ComponentProperty;
import org.patryk3211.powergrid.circuits.components.properties.IntProperty;
import org.patryk3211.powergrid.circuits.components.properties.Orientation;
import org.patryk3211.powergrid.circuits.schematic.ComponentFootprint;
import org.patryk3211.powergrid.circuits.schematic.PlacedComponent;
import org.patryk3211.powergrid.circuits.thermal.ThermalBuilder;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.ProvidedVoltageSourceCoupling;
import org.patryk3211.powergrid.utility.Lang;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Motion sensor (PIR style).
 *
 * Footprint: 3 (w) x 2 (l). Pads sit on the footprint's y = 0 row:
 * 0 = GND, 1 = VCC, 2 = OUT. The "eye" looks away from the pads (footprint
 * +y, drawn as the footprint arrow in the circuit design table), and
 * extends {@link OrientableComponent} so it can be rotated in 90 degree steps
 * exactly like Power Grid's own gauges. The arrow rotates with it, and so
 * does the world direction the sensor looks in (see {@link #senseDirection}).
 * Because that direction is mapped through the board block's own facing, a
 * sensor that points at the edge of a board looks out sideways from it.
 *
 * Electrical model (same style as the signal oscillator): OUT is driven by a
 * {@link ProvidedVoltageSourceCoupling} between OUT and GND. While motion is
 * latched the source provides (VCC - GND), so (OUT - GND) equals the supply
 * differential; otherwise it provides 0 V. It needs at least 1 V of supply.
 *
 * Settings (both are normal component properties, so they show up in the
 * circuit design table's property panel; right-click on the board also cycles
 * them - sneak for the duration):
 *  - range:    detection depth in blocks (capped by the config).
 *  - duration: how many seconds OUT stays high after the last motion.
 *
 * Burnout is driven purely by Power Grid's {@link ThermalBuilder}: an
 * internal load resistor between VCC and GND is the heat source. Its value
 * is chosen from the config so the steady state of that heat reaches the
 * overheat temperature at {@code sensorBurnoutVoltage} (default 6 V). Power
 * Grid removes the heat source on overheating; the overheat callback also
 * removes the output source. The BURNT property only mirrors that state so
 * the model can switch to the burnt version.
 *
 * Per-placement state lives in {@link PlacedComponent#customData}, never in
 * fields (one instance of this class serves every placed sensor).
 */
public class MotionSensorComponent extends OrientableComponent
        implements IComponentGoggleInformation, IInteractableComponent {

    public static final int PIN_GND = 0;
    public static final int PIN_VCC = 1;
    public static final int PIN_OUT = 2;

    public static final int DEFAULT_RANGE = 4;
    public static final int MIN_RANGE = 1;
    public static final int HARD_MAX_RANGE = 32;
    public static final int DEFAULT_DURATION_SECONDS = 2;
    public static final int MIN_DURATION_SECONDS = 1;
    public static final int HARD_MAX_DURATION_SECONDS = 120;

    /** Looking direction in the UNROTATED footprint: away from the pad row. */
    public static final Orientation BASE_FACING = Orientation.DOWN;

    private static final double MIN_ACTIVATION_VOLTAGE = 1.0D;
    private static final float OUTPUT_RESISTANCE = 1.0f;

    /** Heat (W) the internal load makes at the burnout voltage. */
    private static final double BURN_POWER = 0.5D;
    /**
     * The cooling is sized for slightly LESS than BURN_POWER at the overheat
     * temperature, so at exactly the burnout voltage the steady state sits a
     * little above it (it burns) and at 95 % of that voltage a little below.
     */
    private static final float COOLING_MARGIN = 0.95f;
    private static final float OVERHEAT_TEMPERATURE = 85.0f;
    private static final float THERMAL_MASS = 0.02f;

    private static final int SCAN_INTERVAL_TICKS = 2;
    /** Blocks an entity must have moved between two scans to count as motion. */
    private static final double MOTION_THRESHOLD = 0.02D;

    public static final IntProperty RANGE =
            new IntProperty(UsefulComponents.MODID, "motion_range", DEFAULT_RANGE, MIN_RANGE, HARD_MAX_RANGE);

    public static final IntProperty DURATION =
            new IntProperty(UsefulComponents.MODID, "motion_duration",
                    DEFAULT_DURATION_SECONDS, MIN_DURATION_SECONDS, HARD_MAX_DURATION_SECONDS);

    /** Mirror of the thermal state, for the model and the tooltip. Never copied with a schematic. */
    public static final BooleanProperty BURNT =
            new BooleanProperty(UsefulComponents.MODID, "motion_burnt").hidden().unsafe().cast();

    /** Whether OUT is currently high because of motion. Never copied with a schematic. */
    public static final BooleanProperty ACTIVE =
            new BooleanProperty(UsefulComponents.MODID, "motion_active").hidden().unsafe().cast();

    public MotionSensorComponent(ComponentFootprint footprint) {
        super(footprint);
    }

    @Override
    protected void addProperties(ImmutableCollection.Builder<ComponentProperty<?>> properties) {
        super.addProperties(properties);
        properties.add(RANGE);
        properties.add(DURATION);
        properties.add(LABEL);
        properties.add(BURNT);
        properties.add(ACTIVE);
    }

    // ------------------------------------------------------------------ circuit

    @Override
    public void bake(@NotNull PlacedComponent placed, @NotNull ComponentCircuitBuilder builder,
                      ThermalBuilder.@NotNull IEmitter thermals) {
        FloatingNode gnd = builder.terminalNode(PIN_GND);
        FloatingNode vcc = builder.terminalNode(PIN_VCC);
        FloatingNode out = builder.terminalNode(PIN_OUT);

        Runtime rt = new Runtime(vcc, gnd);

        var source = new ProvidedVoltageSourceCoupling(out, gnd, OUTPUT_RESISTANCE);
        source.setVoltageProvider(() -> outputVoltage(placed, rt));
        builder.add(source);
        placed.add(source);
        rt.source = source;

        // Internal consumption: R = Vburn^2 / P, so P(Vburn) == BURN_POWER.
        double burnVoltage = UsefulComponentsConfig.SENSOR_BURNOUT_VOLTAGE.get();
        float loadResistance = (float) (burnVoltage * burnVoltage / BURN_POWER);
        ElectricWire load = builder.connect(loadResistance, vcc, gnd);
        placed.add(load);
        rt.load = load;

        placed.customData = rt;

        thermals.builder()
                .setMaxPower((float) (BURN_POWER * COOLING_MARGIN), OVERHEAT_TEMPERATURE)
                .setOverheatTemperature(OVERHEAT_TEMPERATURE)
                .setThermalMass(THERMAL_MASS)
                .withTemperatureCallback(t -> rt.temperature = t)
                .withOverheatCallback(() -> {
                    rt.overheated = true;
                    rt.active = false;
                    source.remove();
                })
                .addHeatSource(load);
    }

    private static double outputVoltage(@NotNull PlacedComponent placed, @NotNull Runtime rt) {
        if (rt.overheated || !rt.active)
            return 0.0D;
        double supply = rt.vcc.getVoltage() - rt.gnd.getVoltage();
        return supply >= MIN_ACTIVATION_VOLTAGE ? supply : 0.0D;
    }

    // --------------------------------------------------------------------- tick

    @Override
    public boolean tick(@NotNull PlacedComponent placed) {
        if (placed.isClient())
            return true;
        if (!(placed.customData instanceof Runtime rt))
            return true;

        if (rt.overheated && !placed.get(BURNT))
            burn(placed, rt);
        if (placed.get(BURNT)) {
            setActive(placed, rt, false);
            return true;
        }

        Level level = placed.getWorld();
        long now = level.getGameTime();

        double supply = rt.vcc.getVoltage() - rt.gnd.getVoltage();
        if (supply < MIN_ACTIVATION_VOLTAGE) {
            // Unpowered: no detection, no latched output, forget what it saw.
            rt.activeUntil = 0L;
            rt.lastPositions.clear();
            setActive(placed, rt, false);
            return true;
        }

        if (now >= rt.nextScan) {
            rt.nextScan = now + SCAN_INTERVAL_TICKS;
            if (scan(placed, rt, level)) {
                int seconds = Math.min(placed.get(DURATION), UsefulComponentsConfig.SENSOR_MAX_DURATION.get());
                rt.activeUntil = now + seconds * 20L;
            }
        }

        setActive(placed, rt, now < rt.activeUntil);
        return true;
    }

    private static void setActive(@NotNull PlacedComponent placed, @NotNull Runtime rt, boolean active) {
        if (rt.active == active && placed.get(ACTIVE) == active)
            return;
        rt.active = active;
        placed.set(ACTIVE, active);
        placed.notifyClients(ACTIVE);
    }

    private static void burn(@NotNull PlacedComponent placed, @NotNull Runtime rt) {
        if (placed.get(BURNT))
            return;
        rt.active = false;
        placed.set(BURNT, true);
        placed.set(ACTIVE, false);
        placed.notifyClients(BURNT);
        placed.notifyClients(ACTIVE);
        if (placed.getWorld() instanceof ServerLevel server) {
            Vec3 pos = placed.getExactPos();
            if (SableCompat.isInSubLevel(server, placed.getPos()))
                pos = SableCompat.toGlobal(server, placed.getPos(), pos);
            server.sendParticles(ParticleTypes.SMOKE, pos.x, pos.y + 0.1, pos.z, 10, 0.05, 0.05, 0.05, 0.01);
        }
    }

    // ---------------------------------------------------------------- detection

    /** World direction the sensor looks in (accounts for the board's own orientation). */
    public static Direction senseDirection(@NotNull PlacedComponent placed) {
        var state = placed.getWorld().getBlockState(placed.getPos());
        Orientation facing = BASE_FACING.rotate(placed.get(ORIENTATION));
        return CircuitBoardBlock.getDirection(state, facing);
    }

    /** Returns true if something moved inside the sensing cone during this scan. */
    private static boolean scan(@NotNull PlacedComponent placed, @NotNull Runtime rt, @NotNull Level level) {
        int range = Math.min(placed.get(RANGE), UsefulComponentsConfig.SENSOR_MAX_RANGE.get());
        BlockPos board = placed.getPos();

        Direction direction = senseDirection(placed);
        Vec3 origin = placed.getExactPos();
        Vec3 look = new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());

        // A board on a Sable sub-level lives at far away plot coordinates: look in the real world instead.
        boolean onSubLevel = SableCompat.isInSubLevel(level, board);
        if (onSubLevel) {
            Vec3 plotOrigin = origin;
            origin = SableCompat.toGlobal(level, board, plotOrigin);
            look = SableCompat.toGlobalDirection(level, board, plotOrigin, look).normalize();
        }

        // Widening cone: 0.6 blocks wide at the sensor, +0.35 per block of distance.
        double reach = range + 0.5D;
        double maxHalfWidth = 0.6D + 0.35D * range;
        AABB box = new AABB(origin, origin.add(look.scale(reach))).inflate(maxHalfWidth);

        boolean detectItems = UsefulComponentsConfig.SENSOR_DETECT_ITEMS.get();
        boolean needLineOfSight = UsefulComponentsConfig.SENSOR_NEEDS_LINE_OF_SIGHT.get() && !onSubLevel;

        List<Entity> candidates = level.getEntities((Entity) null, box, e ->
                e.isAlive() && !e.isSpectator() && (e instanceof LivingEntity || (detectItems && e instanceof ItemEntity)));

        Map<Integer, Vec3> seen = new HashMap<>();
        boolean motion = false;
        for (Entity entity : candidates) {
            Vec3 center = entity.getBoundingBox().getCenter();
            Vec3 rel = center.subtract(origin);
            double along = rel.dot(look);
            if (along < 0.0D || along > reach)
                continue;
            double lateralSqr = rel.lengthSqr() - along * along;
            double allowed = 0.6D + 0.35D * along;
            if (lateralSqr > allowed * allowed)
                continue;

            Vec3 now = entity.position();
            seen.put(entity.getId(), now);
            if (motion)
                continue; // already triggered, only keep tracking positions

            Vec3 before = rt.lastPositions.get(entity.getId());
            // Something that just entered the cone counts as motion too.
            boolean moved = before == null || before.distanceToSqr(now) > MOTION_THRESHOLD * MOTION_THRESHOLD;
            if (moved && (!needLineOfSight || hasLineOfSight(level, board, origin, look, center, entity)))
                motion = true;
        }

        rt.lastPositions.clear();
        rt.lastPositions.putAll(seen);
        return motion;
    }

    private static boolean hasLineOfSight(Level level, BlockPos board, Vec3 origin, Vec3 look, Vec3 target, Entity entity) {
        // Start just outside the board's own block so it can't block its own sensor.
        Vec3 from = origin.add(look.scale(0.55D));
        BlockHitResult hit = level.clip(new ClipContext(from, target, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, entity));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(board);
    }

    // ------------------------------------------------------------- interaction

    @Override
    public VoxelShape getShape(@NotNull PlacedComponent placed) {
        return IInteractableComponent.extrudedFootprint(placed, 3 / 16f);
    }

    @Override
    public InteractionResult use(@NotNull CircuitBoardBlockEntity be, @NotNull PlacedComponent component,
                                  @NotNull Player player) {
        if (player.level().isClientSide)
            return InteractionResult.SUCCESS;
        if (component.get(BURNT)) {
            player.displayClientMessage(Component.literal("This motion sensor is burnt out."), true);
            return InteractionResult.SUCCESS;
        }

        if (player.isShiftKeyDown()) {
            int max = Math.min(HARD_MAX_DURATION_SECONDS, UsefulComponentsConfig.SENSOR_MAX_DURATION.get());
            int next = component.get(DURATION) + 1;
            if (next > max)
                next = MIN_DURATION_SECONDS;
            component.set(DURATION, next);
            component.notifyClients(DURATION);
            player.displayClientMessage(Component.literal("Motion sensor duration: " + next + " s"), true);
        } else {
            int max = Math.min(HARD_MAX_RANGE, UsefulComponentsConfig.SENSOR_MAX_RANGE.get());
            int next = component.get(RANGE) + 1;
            if (next > max)
                next = MIN_RANGE;
            component.set(RANGE, next);
            component.notifyClients(RANGE);
            player.displayClientMessage(Component.literal("Motion sensor range: " + next + " blocks"), true);
        }
        be.setChanged();
        return InteractionResult.SUCCESS;
    }

    // ------------------------------------------------------------------ visuals

    @Override
    public void stateUpdated(@NotNull PlacedComponent placed) {
        super.stateUpdated(placed);
        // Only the burnt flag changes the model; the active flag must not rebuild the board mesh.
        if (placed.get(BURNT))
            placed.onClientWorld(() -> world -> modelChanged(placed.getPos()));
    }

    @Override
    public @NotNull ResourceLocation getModelId(@NotNull PlacedComponent component) {
        return component.get(BURNT)
                ? ResourceLocation.fromNamespaceAndPath(UsefulComponents.MODID, "motion_sensor_burnt")
                : super.getModelId(component);
    }

    @Override
    public @NotNull Collection<ResourceLocation> requestedModels() {
        return List.of(
                ResourceLocation.fromNamespaceAndPath(UsefulComponents.MODID, "motion_sensor"),
                ResourceLocation.fromNamespaceAndPath(UsefulComponents.MODID, "motion_sensor_burnt")
        );
    }

    @Override
    public boolean addToGoggleTooltip(@NotNull PlacedComponent placed, @NotNull List<Component> tooltip,
                                       boolean isPlayerSneaking) {
        if (placed.has(LABEL) && !placed.get(LABEL).isEmpty()) {
            Lang.text(placed.get(LABEL)).forGoggles(tooltip);
            tooltip.add(Component.literal("Motion sensor"));
        } else {
            Lang.text("Motion sensor").forGoggles(tooltip);
        }
        if (placed.get(BURNT)) {
            tooltip.add(Component.literal("Burnt out"));
            return true;
        }
        tooltip.add(Component.literal("Range: " + placed.get(RANGE) + " blocks"));
        tooltip.add(Component.literal("Duration: " + placed.get(DURATION) + " s"));
        tooltip.add(Component.literal("Output: " + (placed.get(ACTIVE) ? "motion" : "idle")));
        return true;
    }

    /** Per-placement live state, stashed via {@link PlacedComponent#customData}. */
    private static final class Runtime {
        final FloatingNode vcc;
        final FloatingNode gnd;
        ProvidedVoltageSourceCoupling source;
        ElectricWire load;

        /** Written by the solver / thermal thread and the server tick, so volatile. */
        volatile boolean active;
        volatile boolean overheated;
        volatile float temperature = 20.0f;

        long activeUntil;
        long nextScan;
        final Map<Integer, Vec3> lastPositions = new HashMap<>();

        Runtime(FloatingNode vcc, FloatingNode gnd) {
            this.vcc = vcc;
            this.gnd = gnd;
        }
    }
}
