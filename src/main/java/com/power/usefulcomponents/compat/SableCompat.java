package com.power.usefulcomponents.compat;

import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Everything the mod needs to know about Sable (physics "sub-levels").
 *
 * Built on Sable Companion, which is bundled with the mod and does nothing
 * (every position is "not in a sub-level") when Sable itself isn't
 * installed - so nothing here needs a Sable-present check.
 *
 * Blocks of a sub-level are stored at huge "plot" coordinates, far away from
 * where the structure really is. Anything that needs the REAL position of a
 * board (drawing the cable, measuring its length, spawning items) has to go
 * through this class.
 */
public final class SableCompat {

    private SableCompat() {
    }

    @Nullable
    public static SubLevelAccess containing(@NotNull Level level, @NotNull BlockPos pos) {
        return SableCompanion.INSTANCE.getContaining(level, pos);
    }

    public static boolean isInSubLevel(@NotNull Level level, @NotNull BlockPos pos) {
        return containing(level, pos) != null;
    }

    /**
     * True when the two board positions are not part of the same structure:
     * in two different sub-levels, or one in a sub-level and the other in the
     * normal world. Two boards that are both in the normal world never count.
     */
    public static boolean inDifferentSubLevels(@NotNull Level level, @NotNull BlockPos a, @NotNull BlockPos b) {
        SubLevelAccess subA = containing(level, a);
        SubLevelAccess subB = containing(level, b);
        if (subA == null && subB == null)
            return false;
        if (subA == null || subB == null)
            return true;
        return !subA.getUniqueId().equals(subB.getUniqueId());
    }

    /** The pose used to draw (client: smoothly interpolated) or to simulate (server: current tick). */
    private static Pose3dc poseOf(@NotNull Level level, @NotNull SubLevelAccess subLevel) {
        if (level.isClientSide() && subLevel instanceof ClientSubLevelAccess client)
            return client.renderPose();
        return subLevel.logicalPose();
    }

    /** A point given in the board's own space -> where it really is (unchanged outside sub-levels). */
    @NotNull
    public static Vec3 toGlobal(@NotNull Level level, @NotNull BlockPos board, @NotNull Vec3 point) {
        SubLevelAccess subLevel = containing(level, board);
        if (subLevel == null)
            return point;
        return poseOf(level, subLevel).transformPosition(point);
    }

    /** A unit direction at {@code at} (board space) -> the same direction as it really points. */
    @NotNull
    public static Vec3 toGlobalDirection(@NotNull Level level, @NotNull BlockPos board, @NotNull Vec3 at, @NotNull Vec3 dir) {
        SubLevelAccess subLevel = containing(level, board);
        if (subLevel == null)
            return dir;
        Pose3dc pose = poseOf(level, subLevel);
        Vec3 turned = pose.transformPosition(at.add(dir)).subtract(pose.transformPosition(at));
        return turned.lengthSqr() < 1.0e-12 ? dir : turned.normalize();
    }

    /** Real distance between two boards, no matter which sub-levels they are in. */
    public static double globalDistance(@NotNull Level level, @NotNull BlockPos a, @NotNull BlockPos b) {
        Vec3 globalA = SableCompanion.INSTANCE.projectOutOfSubLevel(level, Vec3.atCenterOf(a));
        Vec3 globalB = SableCompanion.INSTANCE.projectOutOfSubLevel(level, Vec3.atCenterOf(b));
        return globalA.distanceTo(globalB);
    }
}
