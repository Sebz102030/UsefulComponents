package com.power.usefulcomponents.uv;

import com.power.usefulcomponents.UsefulComponents;
import com.power.usefulcomponents.registry.ModBlocks;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.simibubi.create.compat.jei.category.sequencedAssembly.SequencedAssemblySubCategory;
import com.simibubi.create.content.processing.recipe.ProcessingRecipeParams;
import com.simibubi.create.content.processing.recipe.StandardProcessingRecipe;
import com.simibubi.create.content.processing.sequenced.IAssemblyRecipe;
import com.simibubi.create.foundation.recipe.IRecipeTypeInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * "usefulcomponents:uv_curing": one input item, up to three outputs (with chances,
 * same JSON as Create's pressing recipes). Usable on its own (item under the
 * machine) and as a step of a Create sequenced assembly recipe, exactly like
 * Power Grid's own magnetizing recipe.
 */
public class UvCuringRecipe extends StandardProcessingRecipe<RecipeInput> implements IAssemblyRecipe {
    public static final TypeInfo TYPE_INFO = new TypeInfo();

    /** Lowest / highest value of {@code heat_level}. */
    public static final int MIN_HEAT_LEVEL = 1;
    public static final int MAX_HEAT_LEVEL = 3;
    /** Used when a recipe (e.g. a sequenced-assembly step) leaves out {@code curing_time}. Ticks. */
    public static final int DEFAULT_CURING_TIME = 100;
    public static final int MAX_CURING_TIME = 72000;

    /** Ticks the lamp has to shine on the item ({@code curing_time}). */
    private int curingTime = DEFAULT_CURING_TIME;
    /** Heat level (1-3) the lamp has to reach ({@code heat_level}). */
    private int heatLevel = MIN_HEAT_LEVEL;

    public UvCuringRecipe(ProcessingRecipeParams params) {
        super(TYPE_INFO, params);
    }

    public int getCuringTime() {
        return curingTime;
    }

    public int getHeatLevel() {
        return heatLevel;
    }

    UvCuringRecipe withCuring(int curingTime, int heatLevel) {
        this.curingTime = curingTime;
        this.heatLevel = heatLevel;
        return this;
    }

    @Override
    protected int getMaxInputCount() {
        return 1;
    }

    @Override
    protected int getMaxOutputCount() {
        return 3;
    }

    @Override
    public Component getDescriptionForAssembly() {
        return Component.translatable("recipe.assembly." + UsefulComponents.MODID + ".uv_curing",
                heatLevel, UvTimeFormat.readable(curingTime));
    }

    @Override
    public void addRequiredMachines(Set<ItemLike> list) {
        list.add(ModBlocks.UV_CURING.get());
    }

    @Override
    public void addAssemblyIngredients(List<Ingredient> list) {
    }

    @Override
    public Supplier<Supplier<SequencedAssemblySubCategory>> getJEISubCategory() {
        return () -> com.power.usefulcomponents.compat.jei.UvCuringAssemblySubCategory::new;
    }

    @Override
    public boolean matches(RecipeInput input, Level level) {
        if (input.isEmpty())
            return false;
        return ingredients.get(0).test(input.getItem(0));
    }

    public static class TypeInfo implements IRecipeTypeInfo {
        public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(UsefulComponents.MODID, "uv_curing");
        public static final RecipeType<UvCuringRecipe> TYPE = new RecipeType<>() {
            @Override
            public String toString() {
                return ID.toString();
            }
        };
        public static final RecipeSerializer<UvCuringRecipe> SERIALIZER = new UvSerializer();

        TypeInfo() {
        }

        @Override
        public ResourceLocation getId() {
            return ID;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends RecipeSerializer<?>> T getSerializer() {
            return (T) SERIALIZER;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <I extends RecipeInput, R extends Recipe<I>> RecipeType<R> getType() {
            return (RecipeType<R>) TYPE;
        }
    }

    /**
     * Create's standard processing serializer (ingredients, results, ...) plus the two extra
     * keys {@code curing_time} (ticks, default 100) and {@code heat_level} (1-3, default 1).
     */
    public static class UvSerializer implements RecipeSerializer<UvCuringRecipe> {
        private final StandardProcessingRecipe.Serializer<UvCuringRecipe> base =
                new StandardProcessingRecipe.Serializer<>(UvCuringRecipe::new);

        private final MapCodec<UvCuringRecipe> codec = RecordCodecBuilder.mapCodec(instance -> instance.group(
                base.codec().forGetter(Function.identity()),
                Codec.intRange(1, MAX_CURING_TIME).optionalFieldOf("curing_time", DEFAULT_CURING_TIME)
                        .forGetter(UvCuringRecipe::getCuringTime),
                Codec.intRange(MIN_HEAT_LEVEL, MAX_HEAT_LEVEL).optionalFieldOf("heat_level", MIN_HEAT_LEVEL)
                        .forGetter(UvCuringRecipe::getHeatLevel)
        ).apply(instance, (recipe, time, heat) -> recipe.withCuring(time, heat)));

        private final StreamCodec<RegistryFriendlyByteBuf, UvCuringRecipe> streamCodec = StreamCodec.composite(
                base.streamCodec(), Function.identity(),
                ByteBufCodecs.VAR_INT, UvCuringRecipe::getCuringTime,
                ByteBufCodecs.VAR_INT, UvCuringRecipe::getHeatLevel,
                (recipe, time, heat) -> recipe.withCuring(time, heat));

        @Override
        public MapCodec<UvCuringRecipe> codec() {
            return codec;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, UvCuringRecipe> streamCodec() {
            return streamCodec;
        }
    }
}
