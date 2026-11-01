package com.power.usefulcomponents.uv;

import static net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;

import com.mojang.blaze3d.vertex.PoseStack;
import com.power.usefulcomponents.UsefulComponents;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.press.PressingBehaviour;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws the spinning shaft (Create's own shaft model) and the lamp head, which
 * lowers and rises with {@link PressingBehaviour}'s animation just like the
 * press head. No Flywheel visual is registered for this block entity, so the
 * block entity renderer always draws everything.
 */
public class UvCuringRenderer extends KineticBlockEntityRenderer<UvCuringBlockEntity> {

    public static final PartialModel HEAD = PartialModel.of(
            ResourceLocation.fromNamespaceAndPath(UsefulComponents.MODID, "block/uv_curing_head"));

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
        super.renderSafe(be, partialTicks, ms, buffer, light, overlay);

        BlockState state = be.getBlockState();
        PressingBehaviour pressing = be.getPressingBehaviour();
        float offset = pressing.getRenderedHeadOffset(partialTicks) * pressing.mode.headOffset;

        SuperByteBuffer head = CachedBuffers.partialFacing(HEAD, state, state.getValue(HORIZONTAL_FACING));
        head.translate(0, -offset, 0)
                .light(light)
                .renderInto(ms, buffer.getBuffer(RenderType.solid()));
    }

    @Override
    protected BlockState getRenderedBlockState(UvCuringBlockEntity be) {
        return shaft(getRotationAxisOf(be));
    }
}
