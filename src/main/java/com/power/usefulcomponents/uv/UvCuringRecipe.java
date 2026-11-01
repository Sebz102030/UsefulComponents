package com.power.usefulcomponents.uv;

import com.power.usefulcomponents.UsefulComponents;
import com.power.usefulcomponents.registry.ModBlocks;
import com.simibubi.create.compat.jei.category.sequencedAssembly.SequencedAssemblySubCategory;
import com.simibubi.create.content.processing.recipe.ProcessingRecipeParams;
import com.simibubi.create.content.processing.recipe.StandardProcessingRecipe;
import com.simibubi.create.content.processing.sequenced.IAssemblyRecipe;
import com.simibubi.create.foundation.recipe.IRecipeTypeInfo;
import net.minecraft.network.chat.Component;
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
import java.util.function.Supplier;

/**
 * "usefulcomponents:uv_curing": one input item, up to three outputs (with chances,
 * same JSON as Create's pressing recipes). Usable on its own (item under the
 * machine) and as a step of a Create sequenced assembly recipe, exactly like
 * Power Grid's own magnetizing recipe.
 */
public class UvCuringRecipe extends StandardProcessingRecipe<RecipeInput> implements IAssemblyRecipe {
    public static final TypeInfo TYPE_INFO = new TypeInfo();

    public UvCuringRecipe(ProcessingRecipeParams params) {
        super(TYPE_INFO, params);
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
        return Component.translatable("recipe.assembly." + UsefulComponents.MODID + ".uv_curing");
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
        return null;
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
        public static final RecipeSerializer<UvCuringRecipe> SERIALIZER =
                new StandardProcessingRecipe.Serializer<>(UvCuringRecipe::new);

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
}
