package com.power.usefulcomponents.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.power.usefulcomponents.UsefulComponents;
import com.power.usefulcomponents.components.RibbonCables;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws every ribbon cable from the level render stage instead of from one
 * board's block-entity renderer.
 *
 * The old way drew the whole cable while rendering ONE of the two boards, so
 * the cable vanished whenever that single board block was outside the
 * camera frustum (even with the other end and the whole cable in plain
 * view), or was too far for block entities to render. Here each cable is
 * tested on its own bounding box, so it is visible exactly when any part of
 * the cable is.
 */
@EventBusSubscriber(modid = UsefulComponents.MODID, value = Dist.CLIENT)
public final class RibbonCableClient {

    private static final double MAX_DISTANCE = 128;

    private RibbonCableClient() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES)
            return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null)
            return;
        var cables = RibbonCables.clientCables(level);
        if (cables.isEmpty())
            return;

        Vec3 camera = event.getCamera().getPosition();
        Frustum frustum = event.getFrustum();
        PoseStack ms = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        boolean drewAny = false;
        for (RibbonCables.Cable cable : cables) {
            try {
                Vec3[] line = RibbonCableGeometry.polyline(cable.first(), cable.second());
                AABB box = new AABB(line[0], line[3]).minmax(new AABB(line[1], line[2])).inflate(0.3);
                if (box.distanceToSqr(camera) > MAX_DISTANCE * MAX_DISTANCE || !frustum.isVisible(box))
                    continue;

                int light = maxLight(
                        LevelRenderer.getLightColor(level, BlockPos.containing(line[1])),
                        LevelRenderer.getLightColor(level, BlockPos.containing(line[2])));

                ms.pushPose();
                RibbonCableRenderer.renderCable(ms, buffers, camera, cable.first(), cable.second(),
                        light, OverlayTexture.NO_OVERLAY);
                ms.popPose();
                drewAny = true;
            } catch (RuntimeException e) {
                // A board mid-rebake etc. - skip this cable for this frame.
            }
        }
        if (drewAny)
            buffers.endBatch();
    }

    private static int maxLight(int a, int b) {
        int block = Math.max(a & 0xFFFF, b & 0xFFFF);
        int sky = Math.max(a >>> 16, b >>> 16);
        return sky << 16 | block;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        RibbonCables.clearClient();
    }
}
