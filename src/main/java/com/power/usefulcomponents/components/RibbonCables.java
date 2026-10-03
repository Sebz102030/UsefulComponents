package com.power.usefulcomponents.components;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.circuits.circuitboard.CircuitBoardBlockEntity;
import org.patryk3211.powergrid.circuits.schematic.PlacedComponent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Finds the cables that currently exist, on either side.
 *
 * The cable is NOT an entity and not part of any single board's rendering:
 * its only saved state is the LINKED / PARTNER_* properties of the two
 * connectors. That is the single source of truth, so this class just turns
 * those properties into "here are the two connector objects of each cable"
 * - for the level-stage renderer (client) and for cutting (both sides).
 *
 * Client: every loaded connector reports itself from its client tick
 * ({@link #touchClient}); an entry that stops being reported (board
 * unloaded / removed / cable cut) expires after {@link #EXPIRE_TICKS}.
 * Server: {@link RibbonLinks} already tracks every active pair.
 *
 * No client-only classes are used here, so it is safe to reference from
 * common code.
 */
public final class RibbonCables {

    private static final long EXPIRE_TICKS = 40;

    /** Both ends of one cable, lower connector UUID first (the renderer's "end" / "endflip" order). */
    public record Cable(PlacedComponent first, PlacedComponent second) {
    }

    /** Identifies one cable without holding the (replaceable) PlacedComponent objects. */
    public record CableRef(UUID idFirst, BlockPos boardFirst, UUID idSecond, BlockPos boardSecond) {
    }

    private record PairKey(UUID lo, UUID hi) {
    }

    private record Seen(CableRef ref, long tick) {
    }

    private static final Map<PairKey, Seen> CLIENT_SEEN = new HashMap<>();

    private RibbonCables() {
    }

    /** Client tick of a linked connector: (re)registers its cable. */
    public static void touchClient(@NotNull PlacedComponent placed) {
        if (!placed.get(RibbonConnectorComponent.LINKED))
            return;
        UUID self = placed.getUUID();
        UUID other = RibbonConnectorComponent.decodePartnerId(placed);
        BlockPos otherBoard = RibbonConnectorComponent.decodePartnerPos(placed);
        if (other == null || otherBoard == null)
            return;
        boolean selfFirst = self.compareTo(other) <= 0;
        CableRef ref = selfFirst
                ? new CableRef(self, placed.getPos(), other, otherBoard)
                : new CableRef(other, otherBoard, self, placed.getPos());
        CLIENT_SEEN.put(new PairKey(ref.idFirst(), ref.idSecond()),
                new Seen(ref, placed.getWorld().getGameTime()));
    }

    public static void clearClient() {
        CLIENT_SEEN.clear();
    }

    /** All cables the client currently knows about whose two connectors are both loaded. */
    public static List<Cable> clientCables(@NotNull Level level) {
        List<Cable> result = new ArrayList<>();
        long now = level.getGameTime();
        Iterator<Seen> iter = CLIENT_SEEN.values().iterator();
        while (iter.hasNext()) {
            Seen seen = iter.next();
            if (seen.tick() > now || now - seen.tick() > EXPIRE_TICKS) {
                iter.remove();
                continue;
            }
            Cable cable = resolve(level, seen.ref());
            if (cable != null)
                result.add(cable);
        }
        return result;
    }

    /** All cables the server currently tracks in {@code level}. */
    public static List<Cable> serverCables(@NotNull Level level) {
        List<Cable> result = new ArrayList<>();
        for (CableRef ref : RibbonLinks.refs(level)) {
            Cable cable = resolve(level, ref);
            if (cable != null)
                result.add(cable);
        }
        return result;
    }

    @Nullable
    public static Cable resolve(@NotNull Level level, @NotNull CableRef ref) {
        if (!level.hasChunkAt(ref.boardFirst()) || !level.hasChunkAt(ref.boardSecond()))
            return null;
        if (!(level.getBlockEntity(ref.boardFirst()) instanceof CircuitBoardBlockEntity beFirst)
                || !(level.getBlockEntity(ref.boardSecond()) instanceof CircuitBoardBlockEntity beSecond))
            return null;
        PlacedComponent first = find(beFirst, ref.idFirst());
        PlacedComponent second = find(beSecond, ref.idSecond());
        if (first == null || second == null
                || !first.get(RibbonConnectorComponent.LINKED) || !second.get(RibbonConnectorComponent.LINKED))
            return null;
        return new Cable(first, second);
    }

    @Nullable
    private static PlacedComponent find(CircuitBoardBlockEntity board, UUID id) {
        for (PlacedComponent candidate : board.getComponents(RibbonConnectorComponent.class)) {
            if (candidate.getUUID().equals(id))
                return candidate;
        }
        return null;
    }
}
