package com.power.usefulcomponents.compat.jei;

import com.power.usefulcomponents.UsefulComponents;
import com.power.usefulcomponents.registry.ModItems;
import com.power.usefulcomponents.uv.UvCuringRecipe;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * JEI tab for UV curing recipes (shows the heat level stage and the curing time).
 * This package is only ever loaded by JEI itself, so the mod still runs without JEI installed.
 */
@JeiPlugin
public class UvCuringJEI implements IModPlugin {
    public static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath(UsefulComponents.MODID, "jei");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(new UvCuringJeiCategory(registration.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null)
            return;
        registration.addRecipes(UvCuringJeiCategory.TYPE,
                level.getRecipeManager().getAllRecipesFor(UvCuringRecipe.TypeInfo.TYPE));
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        registration.addRecipeCatalyst(new ItemStack(ModItems.UV_CURING_MACHINE.get()), UvCuringJeiCategory.TYPE);
    }
}
