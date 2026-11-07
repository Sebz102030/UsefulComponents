package com.power.usefulcomponents.compat.jei;

import com.power.usefulcomponents.UsefulComponents;
import com.power.usefulcomponents.registry.ModItems;
import com.power.usefulcomponents.uv.UvCuringBlockEntity;
import com.power.usefulcomponents.uv.UvCuringRecipe;
import com.power.usefulcomponents.uv.UvTimeFormat;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.List;

/**
 * One JEI page per UV curing recipe, laid out like Create's pressing page (slot, long arrow, ground shadow,
 * animated machine, slot) with the heat level stage and the curing time above it.
 */
public class UvCuringJeiCategory implements IRecipeCategory<RecipeHolder<UvCuringRecipe>> {
    @SuppressWarnings("unchecked")
    public static final RecipeType<RecipeHolder<UvCuringRecipe>> TYPE =
            (RecipeType<RecipeHolder<UvCuringRecipe>>) (RecipeType<?>) new RecipeType<>(
                    ResourceLocation.fromNamespaceAndPath(UsefulComponents.MODID, "uv_curing"), RecipeHolder.class);

    private static final int WIDTH = 177;
    /** Create's pressing page is 70 tall; the info lines add this much on top. */
    private static final int INFO_HEIGHT = 36;
    private static final int HEIGHT = 70 + INFO_HEIGHT + 4;

    private final AnimatedUvCuring machine = new AnimatedUvCuring();
    private final IDrawable icon;

    public UvCuringJeiCategory(IGuiHelper guiHelper) {
        this.icon = guiHelper.createDrawableIngredient(VanillaTypes.ITEM_STACK,
                new ItemStack(ModItems.UV_CURING_MACHINE.get()));
    }

    @Override
    public RecipeType<RecipeHolder<UvCuringRecipe>> getRecipeType() {
        return TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("usefulcomponents.recipe.uv_curing");
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, RecipeHolder<UvCuringRecipe> holder, IFocusGroup focuses) {
        UvCuringRecipe recipe = holder.value();

        List<Ingredient> ingredients = recipe.getIngredients();
        for (int i = 0; i < ingredients.size(); i++) {
            builder.addSlot(RecipeIngredientRole.INPUT, 27 - 19 * i, 51 + INFO_HEIGHT)
                    .addIngredients(ingredients.get(i));
        }

        List<ProcessingOutput> results = recipe.getRollableResults();
        for (int i = 0; i < results.size(); i++) {
            builder.addSlot(RecipeIngredientRole.OUTPUT, 131 + 19 * i, 50 + INFO_HEIGHT)
                    .addItemStack(results.get(i).getStack());
        }
    }

    @Override
    public void draw(RecipeHolder<UvCuringRecipe> holder, IRecipeSlotsView slots, GuiGraphics graphics,
                     double mouseX, double mouseY) {
        UvCuringRecipe recipe = holder.value();
        Font font = Minecraft.getInstance().font;
        int level = recipe.getHeatLevel();
        int max = UvCuringRecipe.MAX_HEAT_LEVEL;

        // ---- info lines: heat level stage, what it needs, curing time
        ChatFormatting color = switch (level) {
            case 1 -> ChatFormatting.DARK_GREEN;
            case 2 -> ChatFormatting.GOLD;
            default -> ChatFormatting.DARK_RED;
        };
        MutableComponent bar = Component.literal("\u2588".repeat(level)).withStyle(color)
                .append(Component.literal("\u2592".repeat(max - level)).withStyle(ChatFormatting.GRAY));
        graphics.drawString(font,
                Component.translatable("usefulcomponents.jei.uv_curing.heat", level, max).withStyle(color)
                        .append(Component.literal("  ")).append(bar),
                4, 3, 0xFF404040, false);
        graphics.drawString(font,
                Component.translatable("usefulcomponents.jei.uv_curing.requirement",
                        fmt(UvCuringBlockEntity.heatPower(level)), UvCuringBlockEntity.heatSpeed(level))
                        .withStyle(ChatFormatting.DARK_GRAY),
                4, 14, 0xFF404040, false);
        graphics.drawString(font,
                Component.translatable("usefulcomponents.jei.uv_curing.time",
                        UvTimeFormat.withTicks(recipe.getCuringTime())).withStyle(ChatFormatting.DARK_GRAY),
                4, 25, 0xFF404040, false);

        // ---- Create-style pressing layout, shifted down by the info block
        int dy = INFO_HEIGHT;
        AllGuiTextures.JEI_SLOT.render(graphics, 26, 50 + dy);
        List<ProcessingOutput> results = recipe.getRollableResults();
        for (int i = 0; i < results.size(); i++) {
            boolean chance = results.get(i).getChance() < 1f;
            (chance ? AllGuiTextures.JEI_CHANCE_SLOT : AllGuiTextures.JEI_SLOT)
                    .render(graphics, 130 + 19 * i, 49 + dy);
            if (chance) {
                graphics.drawString(font, Math.round(results.get(i).getChance() * 100) + "%",
                        131 + 19 * i, 50 + dy + 20, 0xFF404040, false);
            }
        }

        AllGuiTextures.JEI_SHADOW.render(graphics, 61, 41 + dy);
        AllGuiTextures.JEI_LONG_ARROW.render(graphics, 52, 54 + dy);
        machine.draw(graphics, WIDTH / 2 - 17, 22 + dy);
    }

    private static String fmt(float watts) {
        return watts == Math.rint(watts) ? Integer.toString((int) watts) : String.format("%.1f", watts);
    }
}
