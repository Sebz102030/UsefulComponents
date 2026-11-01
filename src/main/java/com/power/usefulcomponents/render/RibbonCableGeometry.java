package com.power.usefulcomponents.render;

import com.power.usefulcomponents.compat.SableCompat;
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
    /** Server-side stand-in for the VISIBLE end piece length (3 px texture - 1 px inside the connector); the real one comes from the client-only json. */
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
        Anchor local = computeLocalAnchor(placed);
        var level = placed.getWorld();
        BlockPos board = placed.getPos();
        // On a Sable sub-level the board lives at far away "plot" coordinates;
        // turn the anchor into where it really is (and which way it really
        // points) so the cable follows the moving structure.
        if (!SableCompat.isInSubLevel(level, board))
            return local;
        Vec3 at = local.point();
        return new Anchor(SableCompat.toGlobal(level, board, at),
                SableCompat.toGlobalDirection(level, board, at, local.facing()),
                SableCompat.toGlobalDirection(level, board, at, local.width()),
                SableCompat.toGlobalDirection(level, board, at, local.thickness()));
    }

    /** Anchor in the board's own coordinates (plot coordinates on a sub-level). */
    private static Anchor computeLocalAnchor(@NotNull PlacedComponent placed) {
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


    /** Longest "handle" (blocks) the bend is allowed to have; bigger = wider, slower turn. */
    private static final double BEND_MAX_HANDLE = 1.5;
    /** Handle length as a fraction of the distance between the two straight end pieces. */
    private static final double BEND_HANDLE_FACTOR = 0.4;
    /** Points the pick / cull polyline samples the bend with. */
    private static final int POLYLINE_BEND_SAMPLES = 24;

    /**
     * The bend between the two straight end pieces, as the 4 control points
     * of a cubic Bezier curve. It leaves {@code p1} exactly along
     * {@code dirA} and arrives at {@code p2} exactly against {@code dirB}
     * (both are the connectors' outward directions), so the cable keeps
     * running straight out of each end piece and only starts turning once
     * the end piece is over.
     */
    public static Vec3[] bendControlPoints(Vec3 p1, Vec3 dirA, Vec3 p2, Vec3 dirB, double yLimit) {
        double handle = Math.min(p1.distanceTo(p2) * BEND_HANDLE_FACTOR, BEND_MAX_HANDLE);
        double handleA = limitHandle(p1, dirA, handle, yLimit);
        double handleB = limitHandle(p2, dirB, handle, yLimit);
        return new Vec3[]{p1, p1.add(dirA.scale(handleA)), p2.add(dirB.scale(handleB)), p2};
    }

    /**
     * A Bezier curve never leaves the box spanned by its control points, so
     * keeping each handle's tip at or below {@code yLimit} keeps the whole
     * cable at or below it. The handle is shortened (its direction kept)
     * only when it would reach higher than that.
     */
    private static double limitHandle(Vec3 from, Vec3 dir, double handle, double yLimit) {
        if (dir.y > 1.0e-6 && from.y + dir.y * handle > yLimit)
            return Math.max(0, (yLimit - from.y) / dir.y);
        return handle;
    }

    /**
     * The highest a cable between these two connectors may reach: the top of
     * the full block of whichever connector's board is higher. (On a Sable
     * sub-level this is that block's top in the real world.)
     */
    public static double heightLimit(@NotNull PlacedComponent first, @NotNull PlacedComponent second) {
        return Math.max(blockTop(first), blockTop(second));
    }

    private static double blockTop(PlacedComponent connector) {
        BlockPos pos = connector.getPos();
        Vec3 top = new Vec3(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5);
        return SableCompat.toGlobal(connector.getWorld(), pos, top).y;
    }

    public static Vec3 bendPoint(Vec3[] c, double t) {
        double u = 1 - t;
        return c[0].scale(u * u * u)
                .add(c[1].scale(3 * u * u * t))
                .add(c[2].scale(3 * u * t * t))
                .add(c[3].scale(t * t * t));
    }

    /** Unit direction of travel along the bend at {@code t} (0 = start, 1 = end). */
    public static Vec3 bendTangent(Vec3[] c, double t, Vec3 fallback) {
        double u = 1 - t;
        Vec3 d = c[1].subtract(c[0]).scale(3 * u * u)
                .add(c[2].subtract(c[1]).scale(6 * u * t))
                .add(c[3].subtract(c[2]).scale(3 * t * t));
        return d.lengthSqr() < 1.0e-12 ? fallback : d.normalize();
    }

    /**
     * The cable's centre line as a polyline: p0 (connector A), the straight
     * end piece to p1, the sampled bend from p1 to p2, and the straight end
     * piece to p3 (connector B). So the first point is the connector, the
     * second is where the end piece stops, and the last two are the same for
     * the other connector.
     */
    public static Vec3[] polyline(@NotNull PlacedComponent first, @NotNull PlacedComponent second,
                                  float endRunFirst, float endRunSecond) {
        Anchor a = computeAnchor(first);
        Anchor b = computeAnchor(second);
        Vec3 p0 = a.point();
        Vec3 p3 = b.point();
        Vec3 p1 = p0.add(a.facing().scale(endRunFirst));
        Vec3 p2 = p3.add(b.facing().scale(endRunSecond));
        Vec3[] bend = bendControlPoints(p1, a.facing(), p2, b.facing(), heightLimit(first, second));

        Vec3[] line = new Vec3[POLYLINE_BEND_SAMPLES + 3];
        line[0] = p0;
        for (int i = 0; i <= POLYLINE_BEND_SAMPLES; i++)
            line[1 + i] = bendPoint(bend, i / (double) POLYLINE_BEND_SAMPLES);
        line[line.length - 1] = p3;
        return line;
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
