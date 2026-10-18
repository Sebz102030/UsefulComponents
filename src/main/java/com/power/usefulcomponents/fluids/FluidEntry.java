package com.power.usefulcomponents.fluids;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;

/**
 * One placeholder fluid (fluid type + source + flowing) and, optionally, its bucket and bottle.
 * Created with {@code ModFluids.fluid("id")}; the chained calls register the extra items:
 *
 * <pre>
 *   public static final FluidEntry MY_FLUID = fluid("my_fluid")
 *           .bucketable("my_fluid_bucket")
 *           .bottle("my_fluid_bottle", 250, 16)    // mB per bottle, stack size (both optional)
 *           .spread(7, 0);                         // optional: makes it placeable, see spread()
 * </pre>
 *
 * Without spread() the fluid has no world block (it is only for recipes, tanks and Create pipes), so its
 * bucket cannot place it. Everything is registered through the DeferredRegisters given by ModFluids, and the item
 * capabilities / client textures are wired up for every entry automatically.
 *
 * Resource convention for a fluid with id "x": textures/block/x_still.png and x_flow.png,
 * lang key fluid_type.morediodes.x. Bucket / bottle ids need an item model, texture and lang key each.
 * With spread() also: blockstates/x.json ({"variants":{"":{"model":"morediodes:block/x"}}}), models/block/x.json
 * (just {"textures":{"particle":"morediodes:block/x_still"}}) and lang key block.morediodes.x.
 */
public final class FluidEntry {
    /** Defaults for bottle(id). */
    public static final int DEFAULT_BOTTLE_VOLUME = 250;
    public static final int DEFAULT_BOTTLE_STACK_SIZE = 4;

    private final String id;
    private final DeferredRegister.Items items;
    private final DeferredRegister.Blocks blocks;
    private final DeferredHolder<FluidType, FluidType> type;
    private final DeferredHolder<Fluid, BaseFlowingFluid.Source> source;
    private final DeferredHolder<Fluid, BaseFlowingFluid.Flowing> flowing;
    @Nullable
    private DeferredItem<? extends Item> bucket;
    @Nullable
    private DeferredItem<? extends Item> bottle;
    private int bottleVolume = DEFAULT_BOTTLE_VOLUME;
    @Nullable
    private DeferredBlock<? extends LiquidBlock> block;
    private int spreadLength;
    private int temperature;

    public FluidEntry(String id, DeferredRegister<FluidType> types, DeferredRegister<Fluid> fluids,
                      DeferredRegister.Items items, DeferredRegister.Blocks blocks) {
        this.id = id;
        this.items = items;
        this.blocks = blocks;
        this.type = types.register(id, () -> new FluidType(typeProperties()));
        this.source = fluids.register(id, () -> {
            if (spreadLength > 0)
                return new SpreadFluid.Source(properties(), spreadLength);
            return new BaseFlowingFluid.Source(properties());
        });
        this.flowing = fluids.register(id + "_flowing", () -> {
            if (spreadLength > 0)
                return new SpreadFluid.Flowing(properties(), spreadLength);
            return new BaseFlowingFluid.Flowing(properties());
        });
    }

    /** Adds a bucket item that can be filled with / emptied into this fluid (but never places it). */
    public FluidEntry bucketable(String bucketId) {
        this.bucket = items.register(bucketId, () -> new PlaceholderBucketItem(source.get(),
                new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1), block != null));
        return this;
    }

    /** Bottle with the default volume ({@link #DEFAULT_BOTTLE_VOLUME} mB) and stack size ({@link #DEFAULT_BOTTLE_STACK_SIZE}). */
    public FluidEntry bottle(String bottleId) {
        return bottle(bottleId, DEFAULT_BOTTLE_VOLUME, DEFAULT_BOTTLE_STACK_SIZE);
    }

    /**
     * Adds a bottle item holding {@code mbPerBottle} mB of this fluid; the bottles stack to
     * {@code stackSize}. An empty bottle is the vanilla glass bottle.
     */
    public FluidEntry bottle(String bottleId, int mbPerBottle, int stackSize) {
        if (mbPerBottle <= 0)
            throw new IllegalArgumentException("mbPerBottle must be positive: " + bottleId);
        if (stackSize < 1 || stackSize > 99)
            throw new IllegalArgumentException("stackSize must be 1-99: " + bottleId);
        this.bottleVolume = mbPerBottle;
        this.bottle = items.register(bottleId, () -> new Item(
                new Item.Properties().craftRemainder(Items.GLASS_BOTTLE).stacksTo(stackSize)));
        return this;
    }

    /**
     * Makes the fluid placeable in the world (adds a fluid block; the bucket can place and pick it up).
     *
     * @param lengthFromSource how many blocks it spreads from a source on flat ground, not counting the
     *                         source: 1-7 (7 = like water)
     * @param temperature      0 = water-like, you can swim and nothing hurts. Above 0 it burns and damages
     *                         entities inside it, scaled from lava: {@link SpreadFluidBlock#LAVA_TEMPERATURE}
     *                         (1000) deals lava's damage (4 per hit) and fire time (15 s), 500 half of that.
     *                         Swimming works at every temperature.
     */
    public FluidEntry spread(int lengthFromSource, int temperature) {
        if (lengthFromSource < 1 || lengthFromSource > SpreadFluid.MAX_LENGTH)
            throw new IllegalArgumentException("lengthFromSource must be 1-" + SpreadFluid.MAX_LENGTH + ": " + id);
        if (temperature < 0)
            throw new IllegalArgumentException("temperature must be 0 or more: " + id);
        this.spreadLength = lengthFromSource;
        this.temperature = temperature;
        this.block = blocks.register(id, () -> new SpreadFluidBlock(source.get(),
                BlockBehaviour.Properties.ofFullCopy(Blocks.WATER), this.temperature));
        return this;
    }

    // Fluid type: temperature is kept in kelvin for other mods (water = 300 K, lava = 1300 K).
    private FluidType.Properties typeProperties() {
        var properties = FluidType.Properties.create().temperature(300 + temperature);
        if (temperature > 0)
            properties.canExtinguish(false);
        return properties;
    }

    // Built lazily (when the fluid registers), so bucketable() can be chained after fluid().
    private BaseFlowingFluid.Properties properties() {
        var properties = new BaseFlowingFluid.Properties(type, source, flowing);
        if (bucket != null)
            properties.bucket(bucket);
        if (block != null)
            properties.block(block).explosionResistance(100F);
        return properties;
    }

    /** True if the fluid is this entry's source or flowing fluid. */
    public boolean matches(Fluid fluid) {
        return fluid.isSame(source.get());
    }

    public String id() {
        return id;
    }

    public DeferredHolder<FluidType, FluidType> type() {
        return type;
    }

    public DeferredHolder<Fluid, BaseFlowingFluid.Source> source() {
        return source;
    }

    public DeferredHolder<Fluid, BaseFlowingFluid.Flowing> flowing() {
        return flowing;
    }

    /** The fluid's world block, or null if spread() was not called. */
    @Nullable
    public DeferredBlock<? extends LiquidBlock> block() {
        return block;
    }

    /** The bucket item, or null if bucketable() was not called. */
    @Nullable
    public DeferredItem<? extends Item> bucket() {
        return bucket;
    }

    /** Fluid amount (mB) of one bottle of this fluid. */
    public int bottleVolume() {
        return bottleVolume;
    }

    /** The full bottle item, or null if bottle() was not called. */
    @Nullable
    public DeferredItem<? extends Item> bottle() {
        return bottle;
    }
}
