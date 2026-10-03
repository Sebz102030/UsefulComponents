package com.power.usefulcomponents.render;

import com.power.usefulcomponents.components.RibbonConnectorComponent;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.patryk3211.powergrid.circuits.circuitboard.CircuitBoardBlock;
import org.patryk3211.powergrid.circuits.schematic.PlacedComponent;

/**
 * Where a ribbon cable is in the world. Pure math with no client-only
 * classes, so the server can use it too (for cutting the cable anywhere
 * along its length) and the renderer shares the exact same numbers.
 */
public final class RibbonCableGeometry {

    /** Radius (blocks) around the cable's centre line that counts as "pointing at the cable". */
    public static final double PICK_RADIUS = 0.16;
    /** Server-side stand-in for the end cap length (the real one comes from the client-only json). */
    public static final float DEFAULT_END_RUN = 2 / 16f;

    private RibbonCableGeometry() {
    }

    /**
     * World-space anchor for a connector's cable.
     *
     * @param point     where the cable leaves the connector body
     * @param facing    unit direction the cable initially travels
     * @param width     unit vector along the ribbon's width (the connector's own width axis)
     * @param thickness unit vector along the ribbon's thickness (the wide face's normal)
     */
    public record Anchor(Vec3 point, Vec3 facing, Vec3 width, Vec3 thickness) {
    }

    /**
     * Connector body: 4 px wide (footprint x), 2 px deep (footprint y),
     * 2 px tall, with its pad row on the footprint's y = 0 side (RIGHT
     * orientation). {@code rotated footprint} handling below mirrors
     * {@code ComponentFootprint.rotated}, so everything stays correct for
     * every component orientation and for boards mounted at any angle.
     *
     * <ul>
     *   <li>SIDE: the ribbon leaves horizontally from the middle of the
     *   body's side that is OPPOSITE the pads, same width as the body
     *   (4 px), wide face flat along the board.</li>
     *   <li>FRONT: the ribbon leaves the middle of the body's top pointing
     *   straight off the board, and its wide face is parallel to the body's
     *   wide side (so viewed from the wide side you see the ribbon's face,
     *   not its edge).</li>
     * </ul>
     */
    public static Anchor computeAnchor(@NotNull PlacedComponent placed) {
        var footprint = placed.footprint();
        float w = footprint.getWidth();
        float h = footprint.getHeight();

        var state = placed.getWorld().getBlockState(placed.getPos());
        int angleX = CircuitBoardBlock.getAngleX(state);
        int angleY = CircuitBoardBlock.getAngleY(state);
        BlockPos boardPos = placed.getPos();

        RibbonConnectorComponent.Orientation type = placed.component instanceof RibbonConnectorComponent rcc
                ? rcc.getOrientation() : RibbonConnectorComponent.Orientation.SIDE;

        float bodyBottom = 2 / 16f;              // board surface
        float bodyHeight = 2 / 16f;              // connector model is 2 px tall
        float centerX = placed.x + w / 2f;
        float centerY = placed.y + h / 2f;

        Vec3 up = new Vec3(0, 1, 0);

        if (type == RibbonConnectorComponent.Orientation.FRONT) {
            // Long axis of the body (its width, 4 px) in board-local space:
            // footprint x when the footprint is wider than deep, else y.
            // The ribbon's width follows it, so the ribbon's wide face is
            // parallel to the body's wide side (normal = the short axis).
            Vec3 longAxis = w >= h ? new Vec3(1, 0, 0) : new Vec3(0, 0, 1);
            Vec3 shortAxis = w >= h ? new Vec3(0, 0, 1) : new Vec3(1, 0, 0);

            Vec3 base = transformLocal(centerX / 16f, bodyBottom + bodyHeight, centerY / 16f, angleX, angleY, boardPos);
            return new Anchor(base,
                    transformDir(up, angleX, angleY),
                    transformDir(longAxis, angleX, angleY),
                    transformDir(shortAxis, angleX, angleY));
        }

        // SIDE: direction pointing AWAY from the pad row, in footprint
        // coordinates (x right, y down). Pads sit on y = 0 for RIGHT; the
        // linear part of ComponentFootprint.rotated() maps that "away"
        // direction (0, +1) for each orientation to:
        float dx, dy;
        switch (placed.get(RibbonConnectorComponent.ORIENTATION)) {
            case RIGHT -> { dx = 0; dy = 1; }
            case DOWN -> { dx = -1; dy = 0; }
            case LEFT -> { dx = 0; dy = -1; }
            case UP -> { dx = 1; dy = 0; }
            default -> throw new IllegalStateException(
                    "Unknown orientation: " + placed.get(RibbonConnectorComponent.ORIENTATION));
        }

        float edgeX = centerX + dx * (w / 2f);
        float edgeY = centerY + dy * (h / 2f);
        float midHeight = bodyBottom + bodyHeight / 2f;

        // Width axis = in-plane axis perpendicular to the exit direction
        // (that edge is exactly the connector's 4 px width).
        Vec3 widthLocal = new Vec3(Math.abs(dy), 0, Math.abs(dx));

        Vec3 base = transformLocal(edgeX / 16f, midHeight, edgeY / 16f, angleX, angleY, boardPos);
        return new Anchor(base,
                transformDir(new Vec3(dx, 0, dy), angleX, angleY),
                transformDir(widthLocal, angleX, angleY),
                transformDir(up, angleX, angleY));
    }

    private static Vec3 transformLocal(float x, float y, float z, int angleX, int angleY, BlockPos boardPos) {
        Vec3 pos = new Vec3(x, y, z);
        pos = VecHelper.rotateCentered(pos, angleX, Direction.Axis.X);
        pos = VecHelper.rotateCentered(pos, angleY, Direction.Axis.Y);
        return pos.add(boardPos.getX(), boardPos.getY(), boardPos.getZ());
    }

    /** Same rotation pipeline as {@link #transformLocal}, applied to a direction (no translation). */
    public static Vec3 transformDir(Vec3 dir, int angleX, int angleY) {
        Vec3 center = new Vec3(0.5, 0.5, 0.5);
        Vec3 v = VecHelper.rotateCentered(center.add(dir), angleX, Direction.Axis.X);
        v = VecHelper.rotateCentered(v, angleY, Direction.Axis.Y);
        Vec3 c = VecHelper.rotateCentered(center, angleX, Direction.Axis.X);
        c = VecHelper.rotateCentered(c, angleY, Direction.Axis.Y);
        return v.subtract(c).normalize();
    }


    /**
     * The cable's centre line as a polyline p0 (connector A) - p1 - p2 -
     * p3 (connector B): the two end caps and the straight run between them.
     */
    public static Vec3[] polyline(@NotNull PlacedComponent first, @NotNull PlacedComponent second,
                                  float endRunFirst, float endRunSecond) {
        Anchor a = computeAnchor(first);
        Anchor b = computeAnchor(second);
        Vec3 p0 = a.point();
        Vec3 p3 = b.point();
        return new Vec3[]{p0, p0.add(a.facing().scale(endRunFirst)), p3.add(b.facing().scale(endRunSecond)), p3};
    }

    public static Vec3[] polyline(@NotNull PlacedComponent first, @NotNull PlacedComponent second) {
        return polyline(first, second, DEFAULT_END_RUN, DEFAULT_END_RUN);
    }

    /**
     * Distance along the ray {@code eye + t * dir} (0 <= t <= maxDist, {@code dir}
     * a unit vector) at which it first comes within {@link #PICK_RADIUS} of
     * the polyline, or -1 when it never does.
     */
    public static double rayHit(Vec3 eye, Vec3 dir, double maxDist, Vec3[] line) {
        double best = -1;
        for (int i = 0; i + 1 < line.length; i++) {
            double t = raySegment(eye, dir, maxDist, line[i], line[i + 1]);
            if (t >= 0 && (best < 0 || t < best))
                best = t;
        }
        return best;
    }

    private static double raySegment(Vec3 eye, Vec3 dir, double maxDist, Vec3 s0, Vec3 s1) {
        Vec3 e = s1.subtract(s0);
        double c = e.dot(e);
        Vec3 w0 = eye.subtract(s0);
        double b = dir.dot(e);
        double dw = dir.dot(w0);
        double ew = e.dot(w0);

        double u;
        double denom = c - b * b;
        if (c < 1.0e-9) {
            u = 0;
        } else if (denom < 1.0e-9) {
            u = 0.5; // ray parallel to the segment
        } else {
            u = Math.max(0, Math.min(1, (ew - dw * b) / denom));
        }
        double t = Math.max(0, Math.min(maxDist, u * b - dw));
        if (c >= 1.0e-9)
            u = Math.max(0, Math.min(1, e.dot(w0.add(dir.scale(t))) / c));
        t = Math.max(0, Math.min(maxDist, u * b - dw));

        Vec3 onRay = eye.add(dir.scale(t));
        Vec3 onSeg = s0.add(e.scale(u));
        return onRay.distanceTo(onSeg) <= PICK_RADIUS ? t : -1;
    }
}
