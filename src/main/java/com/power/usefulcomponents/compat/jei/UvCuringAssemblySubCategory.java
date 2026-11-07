package com.power.usefulcomponents.compat.jei;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.compat.jei.category.sequencedAssembly.SequencedAssemblySubCategory;
import com.simibubi.create.content.processing.sequenced.SequencedRecipe;
import net.minecraft.client.gui.GuiGraphics;

/**
 * How a UV curing step looks inside Create's sequenced-assembly JEI page: the same animated machine as in
 * the UV curing tab, small. The step's description (hover) carries the heat level and curing time, see
 * UvCuringRecipe#getDescriptionForAssembly.
 */
public class UvCuringAssemblySubCategory extends SequencedAssemblySubCategory {
    private final AnimatedUvCuring machine = new AnimatedUvCuring();

    public UvCuringAssemblySubCategory() {
        super(25);
    }

    @Override
    public void draw(SequencedRecipe<?> recipe, GuiGraphics graphics, double mouseX, double mouseY, int index) {
        PoseStack ms = graphics.pose();
        ms.pushPose();
        ms.translate(-5, 50, 0);
        ms.scale(0.6f, 0.6f, 0.6f);
        machine.offset = index;
        machine.draw(graphics, getWidth() / 2, 0);
        ms.popPose();
    }
}
