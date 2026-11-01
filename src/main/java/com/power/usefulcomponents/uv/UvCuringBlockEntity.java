package com.power.usefulcomponents.uv;

import com.power.usefulcomponents.config.UsefulComponentsConfig;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.kinetics.press.PressingBehaviour;
import com.simibubi.create.content.kinetics.press.PressingBehaviour.PressingBehaviourSpecifics;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.recipe.RecipeApplier;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.electricity.base.ThermalBehaviour;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.kinetics.base.ElectricKineticBlockEntity;

import java.util.List;
import java.util.Optional;

/**
 * Works like Create's mechanical press (items on a belt / depot two blocks
 * below, or on the ground one block below), but applies
 * {@link UvCuringRecipe}s, and only while:
 *  - the shaft turns at least {@code uvMinSpeed} RPM (default 32), and
 *  - the lamp is fed the power that speed tier asks for: {@code uvPowerLow}
 *    (default 10 W) from min speed up to just under {@code uvHighSpeed}
 *    (default 128 RPM), {@code uvPowerHigh} (default 20 W) from there up.
 *
 * Electrically it is a single resistor (like the basin heater's coil); power
 * is measured on it each tick. The press logic is Create's own
 * {@link PressingBehaviour}; the machine tells it the shaft speed is 0
 * whenever the requirements above aren't met, which is exactly how Create
 * stops a press that has no rotation.
 */
public class UvCuringBlockEntity extends ElectricKineticBlockEntity implements PressingBehaviourSpecifics {

    /** Measured power may be this fraction below the requirement and still count (solver noise). */
    private static final float POWER_TOLERANCE = 0.95f;

    public PressingBehaviour pressingBehaviour;
    private ElectricWire coil;
    /** Server decides, client mirrors (synced). */
    private boolean powered;

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
    }

    @Override
    public @Nullable ThermalBehaviour specifyThermalBehaviour() {
        return ThermalBehaviour.forMaxPower(this, 2.0f, UsefulComponentsConfig.UV_MAX_POWER.get().floatValue());
    }

    @Override
    public void buildCircuit(CircuitBuilder builder) {
        builder.setTerminalCount(2);
        coil = builder.connect(resistance(), builder.terminalNode(0), builder.terminalNode(1));
    }

    // ---------------------------------------------------------------- power / speed

    /** Watts the lamp must be fed at the current shaft speed, or -1 if the shaft is too slow to run at all. */
    public float requiredPower() {
        float speed = Math.abs(getSpeed());
        if (speed < UsefulComponentsConfig.UV_MIN_SPEED.get())
            return -1f;
        return speed < UsefulComponentsConfig.UV_HIGH_SPEED.get()
                ? UsefulComponentsConfig.UV_POWER_LOW.get().floatValue()
                : UsefulComponentsConfig.UV_POWER_HIGH.get().floatValue();
    }

    @Override
    public void electricalTick() {
        super.electricalTick();
        applyPower(coil);

        float required = requiredPower();
        boolean nowPowered = required > 0 && Math.abs(coil.power()) >= required * POWER_TOLERANCE;
        if (nowPowered != powered) {
            powered = nowPowered;
            if (level != null && !level.isClientSide)
                sendData();
        }
    }

    public boolean isPowered() {
        return powered;
    }

    @Override
    public float getKineticSpeed() {
        if (!powered || Math.abs(getSpeed()) < UsefulComponentsConfig.UV_MIN_SPEED.get())
            return 0f;
        return getSpeed();
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(compound, registries, clientPacket);
        compound.putBoolean("UvPowered", powered);
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(compound, registries, clientPacket);
        powered = compound.getBoolean("UvPowered");
    }

    // ------------------------------------------------------------------- processing

    public PressingBehaviour getPressingBehaviour() {
        return pressingBehaviour;
    }

    public Optional<RecipeHolder<UvCuringRecipe>> getRecipe(ItemStack item) {
        Optional<RecipeHolder<UvCuringRecipe>> assembly = SequencedAssemblyRecipe.getRecipe(level, item,
                UvCuringRecipe.TYPE_INFO.getType(), UvCuringRecipe.class);
        if (assembly.isPresent())
            return assembly;
        return level.getRecipeManager().getRecipeFor(UvCuringRecipe.TYPE_INFO.getType(),
                new SingleRecipeInput(item), level);
    }

    @Override
    public boolean tryProcessInBasin(boolean simulate) {
        return false; // curing is for items on belts, depots and the floor only
    }

    @Override
    public boolean tryProcessOnBelt(TransportedItemStack input, @Nullable List<ItemStack> outputList, boolean simulate) {
        Optional<RecipeHolder<UvCuringRecipe>> recipe = getRecipe(input.stack);
        if (recipe.isEmpty())
            return false;
        if (simulate)
            return true;
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
        if (recipe.isEmpty())
            return false;
        if (simulate)
            return true;

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
