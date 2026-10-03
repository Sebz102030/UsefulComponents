package com.power.usefulcomponents.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix4f;
import org.patryk3211.powergrid.circuits.schematic.PlacedComponent;

/**
 * Draws the flat ribbon cable between two linked
 * ribbon connectors. Split out from the component itself so
 * the component's own file stays focused on its actual behavior (properties,
 * bake, linking) - this class only knows how to turn two world-space anchor
 * points into quads.
 *
 * Texture binding uses {@code RenderType.entityCutoutNoCull(ResourceLocation)}
 * with a DIRECT texture file reference and raw 0-1 UV coordinates - the same
 * technique Power Grid's own {@code HangingWireRenderer} uses for its wires.
 * This deliberately does NOT go through the block atlas
 * ({@code Minecraft.getTextureAtlas(...)} + {@code TextureAtlasSprite}) - an
 * earlier version of this switched to that atlas-based approach, which
 * requires a texture to already be stitched into the atlas by some real
 * block/item model referencing it. Since these cable textures aren't
 * referenced by any model, the atlas lookup was silently returning the
 * missing-texture sprite. Binding the file directly sidesteps needing any
 * atlas stitching at all.
 */
public final class RibbonCableRenderer {

    private static final float CABLE_WIDTH = 4 / 16f;
    // Segment lengths (end caps / middle tiles) come from the json's uv
    // rects at 1:1 pixel density - see RibbonCableConfig.Face#length().

    private RibbonCableRenderer() {
    }

    /**
     * Draws one cable. {@code origin} is the world position that the pose
     * stack's (0,0,0) stands for (the camera, when drawing from the level
     * render stage), so every vertex is built in camera-relative doubles and
     * keeps its precision far from the world origin.
     */
    public static void renderCable(@NotNull PoseStack ms, @NotNull MultiBufferSource bufferSource, @NotNull Vec3 origin,
                                   @NotNull PlacedComponent placed, @NotNull PlacedComponent partner,
                                   int light, int overlay) {
        RibbonCableGeometry.Anchor a = RibbonCableGeometry.computeAnchor(placed);
        RibbonCableGeometry.Anchor b = RibbonCableGeometry.computeAnchor(partner);
        RibbonCableConfig cfg = RibbonCableConfig.get();
        // thickness in the json is in PIXELS (1 px = 1/16 block), so
        // 0.25 = a quarter of a pixel.
        float halfThickness = cfg.thickness / 16f / 2f;

        Matrix4f matrix = ms.last().pose();

        Vec3 p0 = a.point().subtract(origin);
        Vec3 p3 = b.point().subtract(origin);

        RibbonCableConfig.Face endFace = cfg.end;
        RibbonCableConfig.Face endFlipFace = cfg.endFlip;
        RibbonCableConfig.Face middleFace = cfg.middle;
        final double middleLength = middleFace.length();

        // The end caps are exactly as long as their texture (4x2 px texture
        // -> 2 px long cap), so the pixels stay square.
        float endRunA = endFace.length();
        float endRunB = endFlipFace.length();

        Vec3 p1 = p0.add(a.facing().scale(endRunA));
        Vec3 p2 = p3.add(b.facing().scale(endRunB));

        Vec3 midDirVec = p2.subtract(p1);
        Vec3 midDir = midDirVec.lengthSqr() < 1.0e-8 ? a.facing() : midDirVec.normalize();
        double midLength = midDirVec.length();

        // --- ribbon frames ---
        // Each connector end owns its frame: `right` (half-width, along the
        // connector's own width axis) and `up` (thickness axis, i.e. the
        // ribbon's wide-face normal). Both come straight from the
        // connector's orientation on the board (see computeAnchor), so the
        // ribbon always lines up with the connector body no matter how the
        // component or the board is rotated.
        Vec3 rightA = a.width().scale(CABLE_WIDTH / 2f);
        Vec3 upA = a.thickness();
        Vec3 rightB = b.width().scale(CABLE_WIDTH / 2f);
        Vec3 upB = b.thickness();

        // The middle run's frame is the end frame carried around the bend
        // (minimal rotation facing -> midDir), so the wide face keeps its
        // orientation through the corner instead of snapping to a fixed
        // world direction. Seen from the B side the cable arrives travelling
        // along -midDir relative to B's outward facing.
        Vec3 rightM0 = transport(rightA, a.facing(), midDir);
        Vec3 upM0 = transport(upA, a.facing(), midDir);
        Vec3 rightM1 = transport(rightB, b.facing(), midDir.scale(-1));

        // Remaining twist between the two ends, spread evenly along the
        // run. A ribbon is symmetric under a 180 degree turn, so the twist
        // is folded into [-90, 90] degrees.
        double twist = signedAngle(rightM0, rightM1, midDir);
        if (twist > Math.PI / 2)
            twist -= Math.PI;
        else if (twist < -Math.PI / 2)
            twist += Math.PI;
        double twistDeg = Math.toDegrees(twist);

        VertexConsumer endBuffer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(textureFile(endFace.texture())));
        // "end" for the first connector: v=0 side touches the connector,
        // v=1 sits at the joint with the middle run.
        drawRibbonSegment(matrix, endBuffer, endFace, p0, rightA, upA, p1, rightA, upA, 0f, 1f, halfThickness, light, overlay);

        VertexConsumer endFlipBuffer = endFlipFace.texture().equals(endFace.texture())
                ? endBuffer
                : bufferSource.getBuffer(RenderType.entityCutoutNoCull(textureFile(endFlipFace.texture())));
        // "endflip" for the second connector - same convention, with the
        // uv sub-rect from the json (mirrored/rotated) so the two ends
        // don't look identical when they face opposite ways.
        drawRibbonSegment(matrix, endFlipBuffer, endFlipFace, p2, rightB, upB, p3, rightB, upB, 0f, 1f, halfThickness, light, overlay);

        VertexConsumer middleBuffer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(textureFile(middleFace.texture())));
        int fullTiles = (int) Math.floor(midLength / middleLength);
        double remainder = midLength - fullTiles * middleLength;
        Vec3 cursor = p1;
        double travelled = 0;
        for (int i = 0; i < fullTiles; i++) {
            Vec3 next = cursor.add(midDir.scale(middleLength));
            double t0 = travelled / midLength;
            double t1 = (travelled + middleLength) / midLength;
            drawRibbonSegment(matrix, middleBuffer, middleFace,
                    cursor, rotateAroundAxis(rightM0, midDir, twistDeg * t0), rotateAroundAxis(upM0, midDir, twistDeg * t0),
                    next, rotateAroundAxis(rightM0, midDir, twistDeg * t1), rotateAroundAxis(upM0, midDir, twistDeg * t1),
                    0f, 1f, halfThickness, light, overlay);
            cursor = next;
            travelled += middleLength;
        }
        if (remainder > 1.0e-4) {
            Vec3 next = cursor.add(midDir.scale(remainder));
            double t0 = travelled / midLength;
            // Short cable: the trailing partial tile samples only the
            // fraction of the middle face's own uv rect it actually needs.
            float vMax = (float) (remainder / middleLength);
            drawRibbonSegment(matrix, middleBuffer, middleFace,
                    cursor, rotateAroundAxis(rightM0, midDir, twistDeg * t0), rotateAroundAxis(upM0, midDir, twistDeg * t0),
                    next, rotateAroundAxis(rightM0, midDir, twistDeg), rotateAroundAxis(upM0, midDir, twistDeg),
                    0f, vMax, halfThickness, light, overlay);
        }

        drawJointPatch(matrix, middleBuffer, middleFace, p1, rightA, upA, rightM0, upM0, halfThickness, light, overlay);
        drawJointPatch(matrix, middleBuffer, middleFace, p2,
                rotateAroundAxis(rightM0, midDir, twistDeg), rotateAroundAxis(upM0, midDir, twistDeg),
                rightB, upB, halfThickness, light, overlay);
    }

    /**
     * {@link RenderType#entityCutoutNoCull} binds a texture FILE directly, so
     * the json's model-style id ({@code ns:block/component/x}) has to become
     * {@code ns:textures/block/component/x.png}. Ids already in file form are
     * left alone.
     */
    private static ResourceLocation textureFile(ResourceLocation id) {
        String path = id.getPath();
        if (!path.startsWith("textures/"))
            path = "textures/" + path;
        if (!path.endsWith(".png"))
            path = path + ".png";
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), path);
    }

    /** Rotates {@code v} the same way the minimal rotation taking unit vector {@code from} to unit vector {@code to} would. */
    private static Vec3 transport(Vec3 v, Vec3 from, Vec3 to) {
        Vec3 axis = from.cross(to);
        double s = axis.length();
        double c = from.dot(to);
        if (s < 1.0e-6) {
            if (c > 0)
                return v;
            // Exactly reversed: any axis perpendicular to `from` works.
            Vec3 perp = Math.abs(from.y) < 0.9 ? from.cross(new Vec3(0, 1, 0)) : from.cross(new Vec3(1, 0, 0));
            return rotateAroundAxis(v, perp.normalize(), 180f);
        }
        return rotateAroundAxis(v, axis.scale(1 / s), (float) Math.toDegrees(Math.atan2(s, c)));
    }

    /** Signed angle (radians) from {@code a} to {@code b} around unit {@code axis}. */
    private static double signedAngle(Vec3 a, Vec3 b, Vec3 axis) {
        double len = a.length() * b.length();
        if (len < 1.0e-9)
            return 0;
        return Math.atan2(axis.dot(a.cross(b)) / len, a.dot(b) / len);
    }

    /**
     * Rotates {@code v} by {@code degrees} around {@code axis} (Rodrigues'
     * rotation formula - {@code axis} must be a unit vector). Used to apply
     * the ribbon twist / bend transport.
     */
    private static Vec3 rotateAroundAxis(Vec3 v, Vec3 axis, double degrees) {
        if (degrees == 0.0)
            return v;
        double rad = Math.toRadians(degrees);
        double cos = Math.cos(rad), sin = Math.sin(rad);
        Vec3 term1 = v.scale(cos);
        Vec3 term2 = axis.cross(v).scale(sin);
        Vec3 term3 = axis.scale(axis.dot(v) * (1 - cos));
        return term1.add(term2).add(term3);
    }

    private static void drawRibbonSegment(Matrix4f matrix, VertexConsumer vc, RibbonCableConfig.Face face,
                                           Vec3 start, Vec3 rightStart, Vec3 upStart,
                                           Vec3 end, Vec3 rightEnd, Vec3 upEnd,
                                           float vStart, float vEnd, float halfThickness,
                                           int light, int overlay) {
        if (start.subtract(end).lengthSqr() < 1.0e-5)
            return;

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

        float u0 = face.u(0f), u1 = face.u(1f);
        float v0 = face.v(vStart), v1 = face.v(vEnd);

        quad(matrix, vc, topStartL, topStartR, topEndR, topEndL, normalTop,
                u0, v0, u1, v0, u1, v1, u0, v1, light, overlay);
        quad(matrix, vc, botStartR, botStartL, botEndL, botEndR, normalBottom,
                u1, v0, u0, v0, u0, v1, u1, v1, light, overlay);

        // "thickness 0" is flat: skip the edge walls entirely (top and
        // bottom already coincide, forming a double-sided flat card) so
        // there's no degenerate zero-area geometry drawn for them.
        if (halfThickness > 1.0e-5) {
            quad(matrix, vc, botStartL, topStartL, topEndL, botEndL, leftNormal,
                    u0, v0, u0, v0, u0, v1, u0, v1, light, overlay);
            quad(matrix, vc, topStartR, botStartR, botEndR, topEndR, rightNormal,
                    u1, v0, u1, v0, u1, v1, u1, v1, light, overlay);
        }
    }

    /**
     * Closes the wedge that opens on the outside of a bend. The ribbon's
     * cross-section (a thin rectangle) at {@code point} is known in two
     * frames - the incoming segment's and the outgoing segment's - and this
     * joins the matching corners of the two rectangles with four quads
     * (top, bottom, left, right), so no gap shows the inside of the cable.
     */
    private static void drawJointPatch(Matrix4f matrix, VertexConsumer vc, RibbonCableConfig.Face face, Vec3 point,
                                        Vec3 right1, Vec3 up1, Vec3 right2, Vec3 up2,
                                        float halfThickness, int light, int overlay) {
        // Keep both frames on the same side (a ribbon is symmetric under a
        // 180 degree turn), otherwise the loft would twist into a bow-tie.
        if (right1.dot(right2) < 0)
            right2 = right2.scale(-1);
        if (up1.dot(up2) < 0)
            up2 = up2.scale(-1);

        Vec3 tl1 = point.subtract(right1).add(up1.scale(halfThickness));
        Vec3 tr1 = point.add(right1).add(up1.scale(halfThickness));
        Vec3 bl1 = point.subtract(right1).subtract(up1.scale(halfThickness));
        Vec3 br1 = point.add(right1).subtract(up1.scale(halfThickness));

        Vec3 tl2 = point.subtract(right2).add(up2.scale(halfThickness));
        Vec3 tr2 = point.add(right2).add(up2.scale(halfThickness));
        Vec3 bl2 = point.subtract(right2).subtract(up2.scale(halfThickness));
        Vec3 br2 = point.add(right2).subtract(up2.scale(halfThickness));

        float u0 = face.u(0f), u1 = face.u(1f);
        float v = face.v(0.5f);

        Vec3 nTop = up1.add(up2).normalize();
        Vec3 nRight = right1.add(right2).normalize();

        // top / bottom faces
        quad(matrix, vc, tl1, tr1, tr2, tl2, nTop, u0, v, u1, v, u1, v, u0, v, light, overlay);
        quad(matrix, vc, br1, bl1, bl2, br2, nTop.scale(-1), u1, v, u0, v, u0, v, u1, v, light, overlay);
        // side walls (only when the ribbon has real thickness)
        if (halfThickness > 1.0e-5) {
            quad(matrix, vc, bl1, tl1, tl2, bl2, nRight.scale(-1), u0, v, u0, v, u0, v, u0, v, light, overlay);
            quad(matrix, vc, tr1, br1, br2, tr2, nRight, u1, v, u1, v, u1, v, u1, v, light, overlay);
        }
    }

    private static void quad(Matrix4f matrix, VertexConsumer vc,
                              Vec3 p1, Vec3 p2, Vec3 p3, Vec3 p4, Vec3 normal,
                              float u1, float v1, float u2, float v2, float u3, float v3, float u4, float v4,
                              int light, int overlay) {
        float nx = (float) normal.x, ny = (float) normal.y, nz = (float) normal.z;
        vertex(matrix, vc, p1, u1, v1, nx, ny, nz, light, overlay);
        vertex(matrix, vc, p2, u2, v2, nx, ny, nz, light, overlay);
        vertex(matrix, vc, p3, u3, v3, nx, ny, nz, light, overlay);
        vertex(matrix, vc, p4, u4, v4, nx, ny, nz, light, overlay);
    }

    private static void vertex(Matrix4f matrix, VertexConsumer vc, Vec3 p, float u, float v,
                                float nx, float ny, float nz, int light, int overlay) {
        vc.addVertex(matrix, (float) p.x, (float) p.y, (float) p.z)
                .setColor(255, 255, 255, 255).setUv(u, v).setOverlay(overlay).setLight(light).setNormal(nx, ny, nz);
    }
}
