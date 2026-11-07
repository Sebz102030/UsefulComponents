package com.power.usefulcomponents.uv;

import com.power.usefulcomponents.UsefulComponents;
import com.power.usefulcomponents.config.UsefulComponentsConfig;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.press.PressingBehaviour;
import com.simibubi.create.content.kinetics.press.PressingBehaviour.PressingBehaviourSpecifics;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.recipe.RecipeApplier;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.createmod.catnip.lang.Lang;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.collections.ModdedAdvancements;
import org.patryk3211.powergrid.electricity.base.ThermalBehaviour;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.kinetics.base.ElectricKineticBlockEntity;

import java.util.List;
import java.util.Optional;

/**
 * Works like Create's mechanical press (items on a belt / depot two blocks
 * below, or on the ground one block below), but applies
 * {@link UvCuringRecipe}s, and only while:
 *  - the lamp draws enough power AND the shaft turns fast enough for the recipe's {@code heat_level}:
 *    level 1 = 10 W + 16 RPM, level 2 = 30 W + 64 RPM, level 3 = 50 W + 128 RPM (all configurable).
 *    The machine's heat level is the lower of what power and speed allow; a higher level also
 *    satisfies a recipe that needs a lower one.
 *
 * Each recipe has a {@code curing_time}: the head comes down, then stays parked just above the
 * item for that many ticks (only counting while the heat level is high enough) before the press
 * stroke completes and the recipe is applied.
 *
 * At {@code uvBurnoutVoltage} (default 60 V) across the lamp the machine explodes.
 *
 * Electrically it is a single resistor (like the basin heater's coil); power
 * is measured on it each tick. The press logic is Create's own
 * {@link PressingBehaviour}; the machine tells it the shaft speed is 0
 * whenever the requirements above aren't met (or while curing), which is exactly how
 * Create stops a press that has no rotation.
 */
public class UvCuringBlockEntity extends ElectricKineticBlockEntity implements PressingBehaviourSpecifics {

    /** Measured power may be this fraction below the requirement and still count (solver noise). */
    private static final float POWER_TOLERANCE = 0.95f;
    /** Consecutive electrical ticks at/above the burnout voltage before the machine explodes. */
    private static final int OVERVOLTAGE_TICKS = 3;
    /** While curing the head parks this many pixels above the item (it never goes lower than that). */
    private static final float PARK_GAP_PIXELS = 4f;
    /** Press speed (RPM) is capped, so a tick never moves the head far. */
    private static final float MAX_PRESS_SPEED = 128f;
    /** Slow speed used for the last part of the descent, so the head can stop exactly at the parking height. */
    private static final float APPROACH_SPEED = 24f;
    /** Head offset (0 = up, 1 = fully down) from which the slow approach starts. */
    private static final float APPROACH_FROM = 0.45f;
    /** Speed used to run through the rest of the stroke once curing is done (the head stays parked meanwhile). */
    private static final float FINISH_SPEED = 256f;

    public PressingBehaviour pressingBehaviour;
    private ElectricWire coil;
    /** Heat level 0-3 reached by the lamp (limited by BOTH power and speed). Server decides, client mirrors (synced). */
    private int heatLevel;
    /** Heat level the electrical power alone would allow (synced; used for the "too slow" warning). */
    private int powerLevel;
    /** Head is parked above the item, curing. Server decides, client mirrors (synced). */
    private boolean holding;
    private int holdTicksLeft;
    /** The curing for this press stroke is over; don't park again until the stroke ends. */
    private boolean holdDone;
    private int overVoltageTicks;
    /** Curing time / heat level of the recipe the press is about to apply (latched when a stroke starts). */
    private int pendingCuringTime;
    private int pendingHeatLevel;

    public UvCuringBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    protected AABB createRenderBoundingBox() {
        return new AABB(worldPosition).expandTowards(0, -1.5, 0).expandTowards(0, 1, 0);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        pressingBehaviour = new PressingBehaviour(this);
        behaviours.add(pressingBehaviour);
        // Needed so the "blow up" advancement can be awarded when the lamp burns out.
        registerAwardables(behaviours, ModdedAdvancements.BLOW_UP);
    }

    @Override
    public void buildCircuit(CircuitBuilder builder) {
        builder.setTerminalCount(2);
        coil = builder.connect(resistance(), builder.terminalNode(0), builder.terminalNode(1));
    }

    // ---------------------------------------------------------------- power / speed

    /** Watts the lamp must draw for the given heat level (1-3). */
    public static float heatPower(int level) {
        return switch (level) {
            case 1 -> UsefulComponentsConfig.UV_HEAT1_POWER.get().floatValue();
            case 2 -> UsefulComponentsConfig.UV_HEAT2_POWER.get().floatValue();
            case 3 -> UsefulComponentsConfig.UV_HEAT3_POWER.get().floatValue();
            default -> 0f;
        };
    }

    /** Rotation speed (RPM) needed for the given heat level (1-3). */
    public static int heatSpeed(int level) {
        return switch (level) {
            case 1 -> UsefulComponentsConfig.UV_HEAT1_SPEED.get();
            case 2 -> UsefulComponentsConfig.UV_HEAT2_SPEED.get();
            case 3 -> UsefulComponentsConfig.UV_HEAT3_SPEED.get();
            default -> 0;
        };
    }

    /** Highest heat level the electrical power alone allows. */
    private int measurePowerLevel() {
        float power = (float) Math.abs(coil.power());
        for (int lvl = UvCuringRecipe.MAX_HEAT_LEVEL; lvl >= UvCuringRecipe.MIN_HEAT_LEVEL; lvl--) {
            if (power >= heatPower(lvl) * POWER_TOLERANCE)
                return lvl;
        }
        return 0;
    }

    /** Highest heat level the shaft speed alone allows. */
    private int measureSpeedLevel() {
        float speed = Math.abs(getSpeed());
        for (int lvl = UvCuringRecipe.MAX_HEAT_LEVEL; lvl >= UvCuringRecipe.MIN_HEAT_LEVEL; lvl--) {
            if (speed >= heatSpeed(lvl))
                return lvl;
        }
        return 0;
    }

    @Override
    public void electricalTick() {
        super.electricalTick();
        if (coil == null)
            return;

        // Burnout: too much voltage across the lamp and the machine blows up.
        if (coil.isConverged()) {
            double volts = Math.abs(coil.potentialDifference());
            if (volts >= UsefulComponentsConfig.UV_BURNOUT_VOLTAGE.get()) {
                if (++overVoltageTicks >= OVERVOLTAGE_TICKS) {
                    award(ModdedAdvancements.BLOW_UP);
                    ThermalBehaviour.explode(level, worldPosition, getBlockState(), 1.0f);
                    return;
                }
            } else {
                overVoltageTicks = 0;
            }
        }

        int power = measurePowerLevel();
        int measured = Math.min(power, measureSpeedLevel());
        if (measured != heatLevel || power != powerLevel) {
            heatLevel = measured;
            powerLevel = power;
            if (level != null && !level.isClientSide)
                sendData();
        }
    }

    public boolean isPowered() {
        return heatLevel > 0;
    }

    /** Heat level 0 (lamp off / too slow) to 3. */
    public int getHeatLevel() {
        return heatLevel;
    }

    public boolean isHolding() {
        return holding;
    }

    /**
     * Head offset (0 = up, 1 = fully down, as Create's PressingBehaviour measures it) at which the
     * head is parked while curing: {@value #PARK_GAP_PIXELS} px above the item. Also used by the renderer
     * so the head never goes lower than this.
     */
    public float getParkFraction() {
        float travel = pressingBehaviour != null && pressingBehaviour.mode != null
                ? pressingBehaviour.mode.headOffset : 1f;
        return Math.max(0.3f, Math.min(0.99f, 1f - (PARK_GAP_PIXELS / 16f) / travel));
    }

    /**
     * The speed Create's PressingBehaviour sees: 0 stops the stroke (no power, too slow, or parked while
     * curing). Fast descent, a slow last bit so the head stops exactly at the parking height, and a fast
     * run-through after curing (the head is held at the parking height visually until it retracts).
     */
    @Override
    public float getKineticSpeed() {
        float speed = getSpeed();
        if (heatLevel == 0)
            return 0f;
        float mag = Math.min(Math.abs(speed), MAX_PRESS_SPEED);

        PressingBehaviour press = pressingBehaviour;
        if (press != null && press.running) {
            float park = getParkFraction();
            float offset = press.getRenderedHeadOffset(1f);
            if (press.runningTicks < PressingBehaviour.CYCLE / 2) {
                if (offset >= park) {
                    if (holding)
                        return 0f;                      // parked, curing
                } else if (offset >= APPROACH_FROM) {
                    mag = Math.min(mag, APPROACH_SPEED); // creeping to the parking height
                }
            } else if (offset >= park) {
                mag = FINISH_SPEED;                      // curing done, get past the pressing point quickly
            }
        }
        return Math.copySign(mag, speed);
    }

    @Override
    public void tick() {
        super.tick();
        if (level != null && !level.isClientSide)
            curingTick();
    }

    /** Parks the head 4 px above the item for the recipe's curing time, then lets the stroke finish (head retracts). */
    private void curingTick() {
        PressingBehaviour press = pressingBehaviour;
        if (!press.running) {
            holdDone = false;
            if (holding) {
                holding = false;
                holdTicksLeft = 0;
                sendData();
            }
            return;
        }

        if (holding) {
            // The curing clock only runs while the lamp is hot enough for the recipe.
            if (heatLevel >= pendingHeatLevel && --holdTicksLeft <= 0) {
                holding = false;
                holdDone = true;
                sendData();
            }
            return;
        }

        int pressPoint = PressingBehaviour.CYCLE / 2;
        if (!holdDone && pendingCuringTime > 0 && heatLevel >= pendingHeatLevel
                && press.runningTicks < pressPoint && press.getRenderedHeadOffset(1f) >= getParkFraction()) {
            holding = true;
            holdTicksLeft = pendingCuringTime;
            sendData();
        }
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        compound.putInt("UvHeatLevel", heatLevel);
        compound.putInt("UvPowerLevel", powerLevel);
        compound.putBoolean("UvHolding", holding);
        compound.putInt("UvHoldLeft", holdTicksLeft);
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        heatLevel = compound.getInt("UvHeatLevel");
        powerLevel = compound.getInt("UvPowerLevel");
        holding = compound.getBoolean("UvHolding");
        holdTicksLeft = compound.getInt("UvHoldLeft");
    }

    // --------------------------------------------------------------------- goggles

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        // Create's own kinetic stats (stress impact etc.) first.
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);

        Lang.builder(UsefulComponents.MODID).translate("gui.goggles.uv_curing.header")
                .style(ChatFormatting.GRAY)
                .forGoggles(tooltip);

        ChatFormatting color = switch (heatLevel) {
            case 1 -> ChatFormatting.YELLOW;
            case 2 -> ChatFormatting.GOLD;
            case 3 -> ChatFormatting.RED;
            default -> ChatFormatting.DARK_GRAY;
        };
        int max = UvCuringRecipe.MAX_HEAT_LEVEL;
        MutableComponent bar = Component.literal("\u2588".repeat(heatLevel)).withStyle(color)
                .append(Component.literal("\u2592".repeat(max - heatLevel)).withStyle(ChatFormatting.DARK_GRAY));
        MutableComponent label = heatLevel == 0
                ? Component.translatable("usefulcomponents.gui.goggles.uv_curing.off")
                : Component.translatable("usefulcomponents.gui.goggles.uv_curing.level", heatLevel, max);
        Lang.builder(UsefulComponents.MODID)
                .add(bar)
                .text(" ")
                .add(label.withStyle(color))
                .forGoggles(tooltip, 1);

        Lang.builder(UsefulComponents.MODID).translate("gui.goggles.uv_curing.thresholds",
                        fmt(heatPower(1)), fmt(heatPower(2)), fmt(heatPower(3)))
                .style(ChatFormatting.DARK_GRAY)
                .forGoggles(tooltip, 1);
        Lang.builder(UsefulComponents.MODID).translate("gui.goggles.uv_curing.thresholds_speed",
                        heatSpeed(1), heatSpeed(2), heatSpeed(3))
                .style(ChatFormatting.DARK_GRAY)
                .forGoggles(tooltip, 1);

        // Power is good enough for a higher stage, but the shaft is too slow for it.
        if (powerLevel > heatLevel) {
            int blocked = heatLevel + 1;
            Lang.builder(UsefulComponents.MODID).translate("gui.goggles.uv_curing.too_slow",
                            blocked, heatSpeed(blocked))
                    .style(ChatFormatting.RED)
                    .forGoggles(tooltip, 1);
        }

        if (holding) {
            Lang.builder(UsefulComponents.MODID).translate("gui.goggles.uv_curing.curing")
                    .style(ChatFormatting.AQUA)
                    .forGoggles(tooltip, 1);
        }
        return true;
    }

    private static String fmt(float watts) {
        return watts == Math.rint(watts) ? Integer.toString((int) watts) : String.format("%.1f", watts);
    }

    // ------------------------------------------------------------------- processing

    public PressingBehaviour getPressingBehaviour() {
        return pressingBehaviour;
    }

    /**
     * Recipe for the item: a sequenced-assembly step if there is one, otherwise the matching recipe with the
     * highest {@code heat_level} the lamp currently reaches (or, if the lamp is too cold for all of them, the
     * lowest one, so the caller can see that the heat is the problem).
     */
    public Optional<RecipeHolder<UvCuringRecipe>> getRecipe(ItemStack item) {
        Optional<RecipeHolder<UvCuringRecipe>> assembly = SequencedAssemblyRecipe.getRecipe(level, item,
                UvCuringRecipe.TYPE_INFO.getType(), UvCuringRecipe.class);
        if (assembly.isPresent())
            return assembly;

        SingleRecipeInput input = new SingleRecipeInput(item);
        RecipeHolder<UvCuringRecipe> best = null;
        for (RecipeHolder<UvCuringRecipe> holder : level.getRecipeManager().getAllRecipesFor(UvCuringRecipe.TypeInfo.TYPE)) {
            UvCuringRecipe recipe = holder.value();
            if (!recipe.matches(input, level))
                continue;
            if (best == null) {
                best = holder;
                continue;
            }
            int bestHeat = best.value().getHeatLevel();
            int heat = recipe.getHeatLevel();
            boolean bestOk = bestHeat <= heatLevel;
            boolean thisOk = heat <= heatLevel;
            if ((thisOk && (!bestOk || heat > bestHeat)) || (!thisOk && !bestOk && heat < bestHeat))
                best = holder;
        }
        return Optional.ofNullable(best);
    }

    /** Remembers the curing time / heat level for the stroke that is about to start. */
    private void latch(UvCuringRecipe recipe) {
        if (pressingBehaviour.running)
            return;
        pendingCuringTime = recipe.getCuringTime();
        pendingHeatLevel = recipe.getHeatLevel();
    }

    @Override
    public boolean tryProcessInBasin(boolean simulate) {
        return false; // curing is for items on belts, depots and the floor only
    }

    @Override
    public boolean tryProcessOnBelt(TransportedItemStack input, @Nullable List<ItemStack> outputList, boolean simulate) {
        Optional<RecipeHolder<UvCuringRecipe>> recipe = getRecipe(input.stack);
        if (recipe.isEmpty() || heatLevel < recipe.get().value().getHeatLevel())
            return false;
        if (simulate) {
            latch(recipe.get().value());
            return true;
        }
        pressingBehaviour.particleItems.add(input.stack);
        List<ItemStack> outputs = RecipeApplier.applyRecipeOn(level,
                canProcessInBulk() ? input.stack : input.stack.copyWithCount(1), recipe.get().value(), true);
        outputList.addAll(outputs);
        return true;
    }

    @Override
    public boolean tryProcessInWorld(ItemEntity itemEntity, boolean simulate) {
        ItemStack item = itemEntity.getItem();
        Optional<RecipeHolder<UvCuringRecipe>> recipe = getRecipe(item);
        if (recipe.isEmpty() || heatLevel < recipe.get().value().getHeatLevel())
            return false;
        if (simulate) {
            latch(recipe.get().value());
            return true;
        }

        pressingBehaviour.particleItems.add(item);
        if (canProcessInBulk() || item.getCount() == 1) {
            RecipeApplier.applyRecipeOn(itemEntity, recipe.get().value(), true);
        } else {
            for (ItemStack result : RecipeApplier.applyRecipeOn(level, item.copyWithCount(1),
                    recipe.get().value(), true)) {
                ItemEntity created = new ItemEntity(level, itemEntity.getX(), itemEntity.getY(), itemEntity.getZ(), result);
                created.setDefaultPickUpDelay();
                created.setDeltaMovement(VecHelper.offsetRandomly(Vec3.ZERO, level.random, .05f));
                level.addFreshEntity(created);
            }
            item.shrink(1);
        }
        return true;
    }

    @Override
    public boolean canProcessInBulk() {
        return AllConfigs.server().recipes.bulkPressing.get();
    }

    @Override
    public void onPressingCompleted() {
    }

    @Override
    public int getParticleAmount() {
        return 10;
    }
}
