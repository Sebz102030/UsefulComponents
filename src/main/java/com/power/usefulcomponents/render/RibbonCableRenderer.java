package com.power.usefulcomponents.render;

import com.power.usefulcomponents.components.RibbonConnectorComponent;
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

    /** Cable width in blocks: 1 px per conductor, so the standard 4-pin cable is 4 px wide and a 10-pin cable 10 px. */
    private static float cableWidth(@NotNull PlacedComponent end) {
        return pinsOf(end) / 16f;
    }

    private static int pinsOf(@NotNull PlacedComponent end) {
        return end.component instanceof RibbonConnectorComponent rcc
                ? rcc.getPinCount() : RibbonCableConfig.STANDARD_PINS;
    }
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
        RibbonCableConfig cfg = RibbonCableConfig.get(pinsOf(placed));
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

        // The end pieces are exactly as long as their texture (e.g. 4x3 px
        // -> 3 px long), so the pixels stay square. The first `endInset`
        // pixels are tucked inside the connector body, so only the rest
        // sticks out - and the bend starts where that visible part ends.
        float inset = cfg.endInset;
        float endRunA = endFace.length() - inset;
        float endRunB = endFlipFace.length() - inset;

        Vec3 p1 = p0.add(a.facing().scale(endRunA));
        Vec3 p2 = p3.add(b.facing().scale(endRunB));

        // --- the bend ---
        // The two end pieces are perfectly straight (their length is their
        // texture's length). Between them the cable follows a smooth curve
        // that leaves p1 along A's facing and arrives at p2 against B's
        // facing, so the turn only starts once the end piece is over and
        // is spread out instead of being a sharp corner.
        // The cable may not rise above the top of the highest connector's full
        // block (that limit is in world space, the points here are relative to `origin`).
        double yLimit = RibbonCableGeometry.heightLimit(placed, partner) - origin.y;
        Vec3[] bend = RibbonCableGeometry.bendControlPoints(p1, a.facing(), p2, b.facing(), yLimit);

        // Arc length table of the curve (to lay texture tiles along it evenly).
        final int table = 128;
        double[] cum = new double[table + 1];
        Vec3 prevPoint = bend[0];
        for (int i = 1; i <= table; i++) {
            Vec3 point = RibbonCableGeometry.bendPoint(bend, i / (double) table);
            cum[i] = cum[i - 1] + point.distanceTo(prevPoint);
            prevPoint = point;
        }
        double midLength = cum[table];

        // --- ribbon frames ---
        // Each connector end owns its frame: `right` (half-width, along the
        // connector's own width axis) and `up` (thickness axis, i.e. the
        // ribbon's wide-face normal). Both come straight from the
        // connector's orientation on the board (see computeAnchor), so the
        // ribbon always lines up with the connector body no matter how the
        // component or the board is rotated.
        Vec3 rightA = a.width().scale(cableWidth(placed) / 2f);
        Vec3 upA = a.thickness();
        Vec3 rightB = b.width().scale(cableWidth(partner) / 2f);
        Vec3 upB = b.thickness();

        VertexConsumer endBuffer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(textureFile(endFace.texture())));
        // "end" for the first connector: v=0 side is inside the connector,
        // v=1 sits at the joint with the bend.
        // The pixel(s) inside the connector are not drawn at all: that part is
        // hidden anyway, and its side walls would sit exactly on the connector
        // body's own side faces (same 4 px width) and z-fight with them.
        float hiddenA = inset / endFace.length();
        drawRibbonSegment(matrix, endBuffer, endFace, p0, rightA, upA, p1, rightA, upA, hiddenA, 1f, halfThickness, light, overlay);

        VertexConsumer endFlipBuffer = endFlipFace.texture().equals(endFace.texture())
                ? endBuffer
                : bufferSource.getBuffer(RenderType.entityCutoutNoCull(textureFile(endFlipFace.texture())));
        // "endflip" for the second connector - same convention, with the
        // uv sub-rect from the json (mirrored/rotated) so the two ends
        // don't look identical when they face opposite ways.
        float hiddenB = inset / endFlipFace.length();
        drawRibbonSegment(matrix, endFlipBuffer, endFlipFace, p2, rightB, upB, p3, rightB, upB, 0f, 1f - hiddenB, halfThickness, light, overlay);

        if (midLength < 1.0e-4)
            return;

        VertexConsumer middleBuffer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(textureFile(middleFace.texture())));

        // Sample the bend every `middleLength` of arc (one texture tile per
        // step) plus the end of the curve for the partial last tile.
        int fullTiles = (int) Math.floor(midLength / middleLength);
        double remainder = midLength - fullTiles * middleLength;
        int samples = fullTiles + 1 + (remainder > 1.0e-4 ? 1 : 0);

        Vec3[] pos = new Vec3[samples];
        Vec3[] tan = new Vec3[samples];
        double[] dist = new double[samples];
        int cursor = 0;
        for (int i = 0; i < samples; i++) {
            double s = Math.min(i * middleLength, midLength);
            while (cursor < table - 1 && cum[cursor + 1] < s)
                cursor++;
            double span = cum[cursor + 1] - cum[cursor];
            double frac = span < 1.0e-12 ? 0 : (s - cum[cursor]) / span;
            double t = (cursor + Math.max(0, Math.min(1, frac))) / table;
            pos[i] = RibbonCableGeometry.bendPoint(bend, t);
            tan[i] = RibbonCableGeometry.bendTangent(bend, t, i == 0 ? a.facing() : tan[i - 1]);
            dist[i] = s;
        }

        // Carry the start frame along the curve (minimal rotation between
        // neighbouring tangents), so the wide face keeps its orientation
        // through the bend instead of snapping to a fixed world direction.
        Vec3[] rights = new Vec3[samples];
        Vec3[] ups = new Vec3[samples];
        rights[0] = rightA;
        ups[0] = upA;
        for (int i = 1; i < samples; i++) {
            rights[i] = transport(rights[i - 1], tan[i - 1], tan[i]);
            ups[i] = transport(ups[i - 1], tan[i - 1], tan[i]);
        }

        // Remaining twist between the carried frame and B's own frame,
        // spread evenly along the curve. A ribbon is symmetric under a 180
        // degree turn, so the twist is folded into [-90, 90] degrees.
        Vec3 endTangent = tan[samples - 1];
        // The bend arrives against B's facing, so B's own width vector is
        // already perpendicular to the arrival direction and is the target.
        double twist = signedAngle(rights[samples - 1], rightB, endTangent);
        if (twist > Math.PI / 2)
            twist -= Math.PI;
        else if (twist < -Math.PI / 2)
            twist += Math.PI;
        double twistDeg = Math.toDegrees(twist);

        for (int i = 0; i + 1 < samples; i++) {
            double f0 = dist[i] / midLength;
            double f1 = dist[i + 1] / midLength;
            Vec3 r0 = rotateAroundAxis(rights[i], tan[i], twistDeg * f0);
            Vec3 u0 = rotateAroundAxis(ups[i], tan[i], twistDeg * f0);
            Vec3 r1 = rotateAroundAxis(rights[i + 1], tan[i + 1], twistDeg * f1);
            Vec3 u1 = rotateAroundAxis(ups[i + 1], tan[i + 1], twistDeg * f1);
            // A partial last tile samples only the fraction of the middle
            // face's own uv rect it actually needs.
            double len = dist[i + 1] - dist[i];
            float vMax = (float) Math.min(1.0, len / middleLength);
            drawRibbonSegment(matrix, middleBuffer, middleFace,
                    pos[i], r0, u0, pos[i + 1], r1, u1, 0f, vMax, halfThickness, light, overlay);
        }

        // Close the gap between the end of the bend and B's end piece, but
        // only when their frames really differ - a patch between identical
        // frames is zero-area and just adds flickering edges.
        Vec3 endRight = rotateAroundAxis(rights[samples - 1], endTangent, twistDeg);
        Vec3 endUp = rotateAroundAxis(ups[samples - 1], endTangent, twistDeg);
        boolean sameRight = Math.abs(endRight.normalize().dot(rightB.normalize())) > 0.9995;
        boolean sameUp = Math.abs(endUp.normalize().dot(upB.normalize())) > 0.9995;
        if (!sameRight || !sameUp)
            drawJointPatch(matrix, middleBuffer, middleFace, p2, endRight, endUp, rightB, upB, halfThickness, light, overlay);
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
            // The walls are only a fraction of a pixel thick, so they show
            // the texture's outermost pixel columns. Sample those columns at
            // their centre (and stay a hair inside the row range) instead of
            // on the exact texture edge, where rounding picks neighbouring
            // pixels and gives wrongly coloured speckles.
            float uL = face.uLeftInner(), uR = face.uRightInner();
            float sv0 = v0 + (v1 - v0) * 0.02f, sv1 = v0 + (v1 - v0) * 0.98f;
            quad(matrix, vc, botStartL, topStartL, topEndL, botEndL, leftNormal,
                    uL, sv0, uL, sv0, uL, sv1, uL, sv1, light, overlay);
            quad(matrix, vc, topStartR, botStartR, botEndR, topEndR, rightNormal,
                    uR, sv0, uR, sv0, uR, sv1, uR, sv1, light, overlay);
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
            float uL = face.uLeftInner(), uR = face.uRightInner();
            quad(matrix, vc, bl1, tl1, tl2, bl2, nRight.scale(-1), uL, v, uL, v, uL, v, uL, v, light, overlay);
            quad(matrix, vc, tr1, br1, br2, tr2, nRight, uR, v, uR, v, uR, v, uR, v, light, overlay);
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
