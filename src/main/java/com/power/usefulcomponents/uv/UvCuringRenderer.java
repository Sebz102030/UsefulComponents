package com.power.usefulcomponents.uv;

import static net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.press.PressingBehaviour;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws the spinning shaft (uv_curing_shaft model) and the lamp head (uv_curing_head model), which
 * lowers and rises with {@link PressingBehaviour}'s animation just like the
 * press head. No Flywheel visual is registered for this block entity, so the
 * block entity renderer always draws everything.
 */
public class UvCuringRenderer extends KineticBlockEntityRenderer<UvCuringBlockEntity> {

    public UvCuringRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public boolean shouldRenderOffScreen(UvCuringBlockEntity be) {
        return true;
    }

    @Override
    protected void renderSafe(UvCuringBlockEntity be, float partialTicks, PoseStack ms, MultiBufferSource buffer,
                              int light, int overlay) {
        BlockState state = be.getBlockState();

        // Create's super.renderSafe() returns early when Flywheel is active (it expects a visual to draw the
        // shaft). We have no visual, so draw the rotating shaft ourselves, from the mod's own shaft model
        // (models/block/uv_curing_shaft.json, built along Z) so it can be restyled with a resource pack.
        SuperByteBuffer shaft = CachedBuffers.partialFacing(UvPartialModels.SHAFT, state,
                state.getValue(HORIZONTAL_FACING));
        renderRotatingBuffer(be, shaft, ms, buffer.getBuffer(RenderType.solid()), light);

        PressingBehaviour pressing = be.getPressingBehaviour();
        // The head never goes lower than the parking height (4 px above the item): it stops there while
        // curing, and retracts from there instead of slamming down onto the item.
        float offset = Math.min(pressing.getRenderedHeadOffset(partialTicks), be.getParkFraction())
                * pressing.mode.headOffset;

        SuperByteBuffer head = CachedBuffers.partialFacing(UvPartialModels.HEAD, state, state.getValue(HORIZONTAL_FACING));
        head.translate(0, -offset, 0)
                .light(light)
                .renderInto(ms, buffer.getBuffer(RenderType.solid()));
    }

    @Override
    protected BlockState getRenderedBlockState(UvCuringBlockEntity be) {
        return shaft(getRotationAxisOf(be));
    }
}
