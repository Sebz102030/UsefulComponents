package com.power.usefulcomponents.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.power.usefulcomponents.components.RibbonConnectorComponent;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;
import org.patryk3211.powergrid.circuits.circuitboard.CircuitBoardBlock;
import org.patryk3211.powergrid.circuits.circuitboard.CircuitBoardBlockEntity;
import org.patryk3211.powergrid.circuits.components.OrientableComponent;
import org.patryk3211.powergrid.circuits.schematic.PlacedComponent;

/**
 * Turns a linked pair of ribbon connectors into an actual quad mesh: two
 * end caps plus a run of repeating middle tiles between them, all read
 * from {@link CableRenderConfig}.
 * <p>
 * This is the "rendering" half of what used to be a single
 * {@code RibbonConnectorComponent} file - the other half (linking,
 * cutting, item cost, networking) stays in {@link RibbonConnectorComponent}
 * itself, which only calls into this class for the actual draw call and
 * for cable-length math shared between rendering and the item-cost
 * calculation.
 */
public final class RibbonCableRenderer {
    private static final float CABLE_WIDTH = 4 / 16f;
    private static final float END_LENGTH = 2 / 16f;
    private static final float MIDDLE_LENGTH = 4 / 16f;

    private RibbonCableRenderer() {
    }

    public record Anchor(Vec3 point, Vec3 facing, Vec3 up) {
    }

    /**
     * World-space position/direction a cable should leave a connector from,
     * derived from the connector's footprint, orientation and the circuit
     * board's own rotation.
     */
    public static Anchor computeAnchor(@NotNull PlacedComponent placed) {
        var footprint = placed.footprint();
        float w = footprint.getWidth();
        float h = footprint.getHeight();

        var state = placed.getWorld().getBlockState(placed.getPos());
        int angleX = CircuitBoardBlock.getAngleX(state);
        int angleY = CircuitBoardBlock.getAngleY(state);
        BlockPos boardPos = placed.getPos();

        RibbonConnectorComponent.Orientation placedOrientation = placed.component instanceof RibbonConnectorComponent rcc
                ? rcc.orientation() : RibbonConnectorComponent.Orientation.SIDE;

        float heightOffset = 3 / 16f;

        if (placedOrientation == RibbonConnectorComponent.Orientation.FRONT) {
            float centerX = placed.x + w / 2f;
            float centerY = placed.y + h / 2f;

            Vec3 base = transformLocal(centerX / 16f, heightOffset, centerY / 16f, angleX, angleY, boardPos);
            Vec3 facingProbe = transformLocal(centerX / 16f, heightOffset, centerY / 16f + 0.01f, angleX, angleY, boardPos);
            Vec3 upProbe = transformLocal(centerX / 16f, heightOffset + 0.01f, centerY / 16f, angleX, angleY, boardPos);

            return new Anchor(base, facingProbe.subtract(base).normalize(), upProbe.subtract(base).normalize());
        }

        float dx, dy;
        switch (placed.get(OrientableComponent.ORIENTATION)) {
            case RIGHT -> { dx = 1; dy = 0; }
            case DOWN -> { dx = 0; dy = 1; }
            case LEFT -> { dx = -1; dy = 0; }
            case UP -> { dx = 0; dy = -1; }
            default -> throw new IllegalStateException("Unknown orientation: " + placed.get(OrientableComponent.ORIENTATION));
        }

        float edgeX = placed.x + w / 2f + dx * (w / 2f);
        float edgeY = placed.y + h / 2f + dy * (h / 2f);

        Vec3 base = transformLocal(edgeX / 16f, heightOffset, edgeY / 16f, angleX, angleY, boardPos);
        Vec3 facingProbe = transformLocal(edgeX / 16f + dy * 0.01f, heightOffset, edgeY / 16f + dx * 0.01f, angleX, angleY, boardPos);
        Vec3 upProbe = transformLocal(edgeX / 16f, heightOffset + 0.01f, edgeY / 16f, angleX, angleY, boardPos);

        return new Anchor(base, facingProbe.subtract(base).normalize(), upProbe.subtract(base).normalize());
    }

    private static Vec3 transformLocal(float x, float y, float z, int angleX, int angleY, BlockPos boardPos) {
        Vec3 pos = new Vec3(x, y, z);
        pos = VecHelper.rotateCentered(pos, angleX, Direction.Axis.X);
        pos = VecHelper.rotateCentered(pos, angleY, Direction.Axis.Y);
        return pos.add(boardPos.getX(), boardPos.getY(), boardPos.getZ());
    }

    /**
     * Total cable length (end segments + middle run) between two linked
     * connectors, in blocks. Shared by the renderer (to know how many
     * middle tiles to draw) and by {@code RibbonConnectorComponent} (to
     * price connecting/cutting the cable).
     */
    public static double cableLength(@NotNull PlacedComponent placed, @NotNull PlacedComponent partner) {
        Anchor a = computeAnchor(placed);
        Anchor b = computeAnchor(partner);
        Vec3 p0 = a.point();
        Vec3 p3 = b.point();
        Vec3 p1 = p0.add(a.facing().scale(END_LENGTH));
        Vec3 p2 = p3.add(b.facing().scale(END_LENGTH));
        return p0.distanceTo(p1) + p1.distanceTo(p2) + p2.distanceTo(p3);
    }

    /** Draws the full cable mesh between {@code placed} and {@code partner}. Call once per linked pair. */
    public static void render(@NotNull CircuitBoardBlockEntity be, @NotNull PlacedComponent placed,
                               @NotNull PlacedComponent partner, @NotNull PoseStack ms,
                               @NotNull MultiBufferSource bufferSource, int light, int overlay) {
        Anchor a = computeAnchor(placed);
        Anchor b = computeAnchor(partner);
        CableRenderConfig cfg = CableRenderConfig.get();
        float halfThickness = cfg.thickness() / 16f / 2f;

        ms.popPose();
        ms.pushPose();
        Matrix4f matrix = ms.last().pose();

        Vec3 origin = Vec3.atLowerCornerOf(be.getBlockPos());
        Vec3 p0 = a.point().subtract(origin);
        Vec3 p3 = b.point().subtract(origin);
        Vec3 p1 = p0.add(a.facing().scale(END_LENGTH));
        Vec3 p2 = p3.add(b.facing().scale(END_LENGTH));

        Vec3 midDirVec = p2.subtract(p1);
        Vec3 midDir = midDirVec.lengthSqr() < 1.0e-8 ? a.facing() : midDirVec.normalize();
        double midLength = midDirVec.length();

        Vec3 rightA = perpendicular(a.facing(), a.up());
        Vec3 rightB = perpendicular(b.facing(), b.up());

        Vec3 rightMid = perpendicular(midDir, a.up());
        if (rightMid.lengthSqr() < 1.0e-4) {
            rightMid = rightA;
        }

        var atlas = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS);
        TextureAtlasSprite endSprite = atlas.apply(cfg.end().texture());
        TextureAtlasSprite endFlipSprite = atlas.apply(cfg.endFlip().texture());
        TextureAtlasSprite middleSprite = atlas.apply(cfg.middle().texture());

        VertexConsumer buffer = bufferSource.getBuffer(RenderType.entityCutout(InventoryMenu.BLOCK_ATLAS));

        // Draw cap ends - "end" at the first (placed) connector, "endflip" at the second (partner) one.
        drawSegment(matrix, buffer, endSprite, p0, rightA, a.up(), p1, rightA, a.up(),
                cfg.end(), halfThickness, light, overlay);
        drawSegment(matrix, buffer, endFlipSprite, p3, rightB, b.up(), p2, rightB, b.up(),
                cfg.endFlip(), halfThickness, light, overlay);

        // Draw middle tiles, each one a full repeat of the "middle" face's UV rectangle.
        int fullTiles = (int) Math.floor(midLength / MIDDLE_LENGTH);
        double remainder = midLength - fullTiles * MIDDLE_LENGTH;
        Vec3 cursor = p1;
        for (int i = 0; i < fullTiles; i++) {
            Vec3 next = cursor.add(midDir.scale(MIDDLE_LENGTH));
            drawSegment(matrix, buffer, middleSprite, cursor, rightMid, a.up(), next, rightMid, a.up(),
                    cfg.middle(), halfThickness, light, overlay);
            cursor = next;
        }
        if (remainder > 1.0e-4) {
            Vec3 next = cursor.add(midDir.scale(remainder));
            float frac = (float) (remainder / MIDDLE_LENGTH);
            drawSegment(matrix, buffer, middleSprite, cursor, rightMid, a.up(), next, rightMid, a.up(),
                    partialFace(cfg.middle(), frac), halfThickness, light, overlay);
        }

        // Draw joint patches with z-fighting mitigation
        drawJointPatch(matrix, buffer, middleSprite, p1, rightA, a.up(), rightMid, a.up(), halfThickness, light, overlay);
        drawJointPatch(matrix, buffer, middleSprite, p2, rightMid, a.up(), rightB, b.up(), halfThickness, light, overlay);
    }

    /** Same face but with its V range cut short to {@code frac} of the way through - used for a partial middle tile. */
    private static CableRenderConfig.Face partialFace(CableRenderConfig.Face face, float frac) {
        float v = face.v0() + (face.v1() - face.v0()) * frac;
        return new CableRenderConfig.Face(face.texture(), face.u0(), face.v0(), face.u1(), v);
    }

    private static Vec3 perpendicular(Vec3 direction, Vec3 up) {
        Vec3 cross = direction.cross(up);
        if (cross.lengthSqr() < 1.0e-6) {
            return new Vec3(0, 0, 0);
        }
        return cross.normalize().scale(CABLE_WIDTH / 2f);
    }

    private static void drawSegment(Matrix4f matrix, VertexConsumer vc, TextureAtlasSprite sprite,
                                     Vec3 start, Vec3 rightStart, Vec3 upStart,
                                     Vec3 end, Vec3 rightEnd, Vec3 upEnd,
                                     CableRenderConfig.Face face, float halfThickness,
                                     int light, int overlay) {
        if (start.subtract(end).lengthSqr() < 1.0e-5)
            return;

        float u0 = face.u0(), u1 = face.u1(), vStart = face.v0(), vEnd = face.v1();

        Vec3 topStartL = start.subtract(rightStart).add(upStart.scale(halfThickness));
        Vec3 topStartR = start.add(rightStart).add(upStart.scale(halfThickness));
        Vec3 topEndL = end.subtract(rightEnd).add(upEnd.scale(halfThickness));
        Vec3 topEndR = end.add(rightEnd).add(upEnd.scale(halfThickness));

        Vec3 botStartL = start.subtract(rightStart).subtract(upStart.scale(halfThickness));
        Vec3 botStartR = start.add(rightStart).subtract(upStart.scale(halfThickness));
        Vec3 botEndL = end.subtract(rightEnd).subtract(upEnd.scale(halfThickness));
        Vec3 botEndR = end.add(rightEnd).subtract(upEnd.scale(halfThickness));

        Vec3 normalTop = upStart.add(upEnd).normalize();
        Vec3 normalBottom = normalTop.scale(-1);
        Vec3 leftNormal = rightStart.add(rightEnd).normalize().scale(-1);
        Vec3 rightNormal = rightStart.add(rightEnd).normalize();

        quad(matrix, vc, sprite, topStartL, topStartR, topEndR, topEndL, normalTop,
                u0, vStart, u1, vStart, u1, vEnd, u0, vEnd, light, overlay);
        quad(matrix, vc, sprite, botStartR, botStartL, botEndL, botEndR, normalBottom,
                u1, vStart, u0, vStart, u0, vEnd, u1, vEnd, light, overlay);
        quad(matrix, vc, sprite, botStartL, topStartL, topEndL, botEndL, leftNormal,
                u0, vStart, u0, vStart, u0, vEnd, u0, vEnd, light, overlay);
        quad(matrix, vc, sprite, topStartR, botStartR, botEndR, topEndR, rightNormal,
                u1, vStart, u1, vStart, u1, vEnd, u1, vEnd, light, overlay);
    }

    private static void drawJointPatch(Matrix4f matrix, VertexConsumer vc, TextureAtlasSprite sprite, Vec3 point,
                                        Vec3 right1, Vec3 up1, Vec3 right2, Vec3 up2,
                                        float halfThickness, int light, int overlay) {
        Vec3 up = up1.add(up2).normalize();
        Vec3 topOffset = up.scale(halfThickness + 0.001f);
        Vec3 botOffset = up.scale(-(halfThickness + 0.001f));

        Vec3 topA1 = point.subtract(right1).add(topOffset);
        Vec3 topA2 = point.add(right1).add(topOffset);
        Vec3 topB1 = point.subtract(right2).add(topOffset);
        Vec3 topB2 = point.add(right2).add(topOffset);
        quad(matrix, vc, sprite, topA1, topA2, topB2, topB1, up,
                0f, 0.5f, 1f, 0.5f, 1f, 0.5f, 0f, 0.5f, light, overlay);

        Vec3 botA1 = point.subtract(right1).add(botOffset);
        Vec3 botA2 = point.add(right1).add(botOffset);
        Vec3 botB1 = point.subtract(right2).add(botOffset);
        Vec3 botB2 = point.add(right2).add(botOffset);
        quad(matrix, vc, sprite, botA2, botA1, botB1, botB2, up.scale(-1),
                1f, 0.5f, 0f, 0.5f, 0f, 0.5f, 1f, 0.5f, light, overlay);
    }

    private static void quad(Matrix4f matrix, VertexConsumer vc, TextureAtlasSprite sprite,
                              Vec3 p1, Vec3 p2, Vec3 p3, Vec3 p4, Vec3 normal,
                              float u1, float v1, float u2, float v2, float u3, float v3, float u4, float v4,
                              int light, int overlay) {
        float nx = (float) normal.x, ny = (float) normal.y, nz = (float) normal.z;
        vertex(matrix, vc, p1, sprite.getU(u1), sprite.getV(v1), nx, ny, nz, light, overlay);
        vertex(matrix, vc, p2, sprite.getU(u2), sprite.getV(v2), nx, ny, nz, light, overlay);
        vertex(matrix, vc, p3, sprite.getU(u3), sprite.getV(v3), nx, ny, nz, light, overlay);
        vertex(matrix, vc, p4, sprite.getU(u4), sprite.getV(v4), nx, ny, nz, light, overlay);
    }

    private static void vertex(Matrix4f matrix, VertexConsumer vc, Vec3 p, float u, float v,
                                float nx, float ny, float nz, int light, int overlay) {
        vc.addVertex(matrix, (float) p.x, (float) p.y, (float) p.z)
                .setColor(255, 255, 255, 255).setUv(u, v).setOverlay(overlay).setLight(light).setNormal(nx, ny, nz);
    }
}
