package com.power.usefulcomponents.registry;

import com.power.usefulcomponents.UsefulComponents;
import com.power.usefulcomponents.uv.UvCuringRecipe;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModRecipes {

    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, UsefulComponents.MODID);
    public static final DeferredRegister<RecipeType<?>> TYPES =
            DeferredRegister.create(Registries.RECIPE_TYPE, UsefulComponents.MODID);

    static {
        var uv = UvCuringRecipe.TYPE_INFO;
        SERIALIZERS.register(uv.getId().getPath(), uv::getSerializer);
        TYPES.register(uv.getId().getPath(), uv::getType);
    }

    private ModRecipes() {
    }
}
