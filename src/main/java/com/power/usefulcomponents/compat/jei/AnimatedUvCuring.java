package com.power.usefulcomponents.compat.jei;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.power.usefulcomponents.registry.ModBlocks;
import com.power.usefulcomponents.uv.UvPartialModels;
import com.simibubi.create.compat.jei.category.animations.AnimatedKinetics;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The UV curing machine as drawn in JEI, animated like Create's press: the shaft spins, the head comes
 * down, stays parked 4 px above the item while curing, then retracts.
 *
 * Everything is drawn from the same resources as the block in the world, so restyling the models/textures
 * (models/block/uv_curing_machine.json, uv_curing_head.json, uv_curing_shaft.json) restyles this too.
 */
public class AnimatedUvCuring extends AnimatedKinetics {
    /** Phase shift in ticks, so several machines in one JEI page don't move in lockstep. */
    public float offset;

    /** Blocks the head travels down to its parking position (a full block minus the 4 px gap). */
    private static final float PARK_DEPTH = 0.75f;
    private static final float PERIOD = 70f;

    @Override
    public void draw(GuiGraphics graphics, int xOffset, int yOffset) {
        PoseStack ms = graphics.pose();
        ms.pushPose();
        ms.translate(xOffset, yOffset, 200);
        ms.mulPose(Axis.XP.rotationDegrees(-15.5f));
        ms.mulPose(Axis.YP.rotationDegrees(22.5f));
        int scale = 24;

        blockElement(UvPartialModels.SHAFT)
                .rotateBlock(0, 0, getCurrentAngle())
                .scale(scale)
                .render(graphics);

        blockElement(ModBlocks.UV_CURING.get().defaultBlockState())
                .scale(scale)
                .render(graphics);

        blockElement(UvPartialModels.HEAD)
                .atLocal(0, -getAnimatedHeadOffset(), 0)
                .scale(scale)
                .render(graphics);

        ms.popPose();
    }

    private float getAnimatedHeadOffset() {
        float ticks = Util.getMillis() / 50f;
        float t = (ticks - offset * 8f) % PERIOD;
        if (t < 0)
            t += PERIOD;

        if (t < 12f) {              // coming down
            float p = t / 12f;
            return PARK_DEPTH * p * p * p;
        }
        if (t < 50f)                // parked, curing
            return PARK_DEPTH;
        if (t < 62f)                // retracting
            return PARK_DEPTH * (1f - (t - 50f) / 12f);
        return 0f;                  // rest
    }
}
