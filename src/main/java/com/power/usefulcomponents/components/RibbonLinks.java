package com.power.usefulcomponents.components;

import com.power.usefulcomponents.UsefulComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.NotNull;
import org.patryk3211.powergrid.circuits.circuitboard.CircuitBoardBlockEntity;
import org.patryk3211.powergrid.circuits.schematic.CircuitSchematic;
import org.patryk3211.powergrid.circuits.schematic.PlacedComponent;
import org.patryk3211.powergrid.electricity.GlobalElectricNetworks;
import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.electricity.sim.ElectricWire;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Electrically joins two linked {@link RibbonConnectorComponent}s.
 *
 * Why this exists: a connector only defines its four pin nodes inside its
 * OWN board's circuit. Nothing in Power Grid ties a pin of one board to a
 * pin of another (it does that for vias/headers inside CircuitBoardBlockEntity
 * only), so without this class the cable was purely visual - voltage put on
 * one connector never reached the other.
 *
 * What it does: for every linked pair it keeps four {@link ElectricWire}s,
 * pin A - pin A, B - B, C - C, D - D (a ribbon cable is a straight-through
 * cable), and puts both boards' nodes into one {@link ElectricalNetwork}.
 *
 * Lifetime: the wires hold references to the boards' current node objects,
 * and those are replaced whenever a board is re-baked (schematic edited,
 * repaired, loaded from disk) and removed when a board is paused (chunk
 * unload). So {@link #sync} - called every server tick from both connectors
 * - checks that the wires still point at live nodes of unpaused boards and
 * rebuilds them otherwise. Chunk unloads drop the wires eagerly, and the
 * whole table is cleared when the server stops (the link itself lives in
 * the connectors' saved properties, so it is rebuilt after a restart).
 *
 * Server thread only.
 */
@EventBusSubscriber(modid = UsefulComponents.MODID)
public final class RibbonLinks {

    /** Same value Power Grid uses for its own board-to-board via wires. */
    private static final double WIRE_RESISTANCE = 0.002;
    private static final int PIN_COUNT = 4;

    private static final class Link {
        final Level level;
        final UUID idA;
        final UUID idB;
        final BlockPos boardA;
        final BlockPos boardB;
        final List<ElectricWire> wires = new ArrayList<>();
        ElectricBehaviour behaviourA;
        ElectricBehaviour behaviourB;
        final IElectricNode[] nodesA = new IElectricNode[PIN_COUNT];
        final IElectricNode[] nodesB = new IElectricNode[PIN_COUNT];

        Link(Level level, UUID idA, BlockPos boardA, UUID idB, BlockPos boardB) {
            this.level = level;
            this.idA = idA;
            this.boardA = boardA;
            this.idB = idB;
            this.boardB = boardB;
        }

        boolean hasWires() {
            return !wires.isEmpty();
        }

        void removeWires() {
            for (ElectricWire wire : wires)
                wire.remove();
            wires.clear();
            behaviourA = null;
            behaviourB = null;
        }
    }

    /** Each link is registered under both of its connector UUIDs. */
    private static final Map<UUID, Link> LINKS = new HashMap<>();

    private RibbonLinks() {
    }

    /**
     * Brings the electrical link of {@code placed} in line with its saved
     * state. Called every server tick by the connector; cheap when nothing
     * changed.
     */
    public static void sync(@NotNull PlacedComponent placed) {
        Level level = placed.getWorld();
        if (!(level instanceof ServerLevel))
            return;

        UUID id = placed.getUUID();
        if (!placed.get(RibbonConnectorComponent.LINKED)) {
            drop(id);
            return;
        }

        BlockPos partnerBoardPos = RibbonConnectorComponent.decodePartnerPos(placed);
        // Never force-load a far away chunk just to look at the partner. The
        // wires are pulled while it is unloaded and rebuilt once it is back.
        if (partnerBoardPos == null || !level.hasChunkAt(partnerBoardPos)) {
            Link existing = LINKS.get(id);
            if (existing != null)
                existing.removeWires();
            return;
        }

        PlacedComponent partner = RibbonConnectorComponent.findPartner(placed);
        if (partner == null) {
            drop(id);
            return;
        }

        if (!(level.getBlockEntity(placed.getPos()) instanceof CircuitBoardBlockEntity boardHere)
                || !(level.getBlockEntity(partnerBoardPos) instanceof CircuitBoardBlockEntity boardThere)) {
            dropWires(id);
            return;
        }

        // Use one canonical orientation of the pair so both connectors
        // share the exact same Link object and node arrays.
        boolean hereIsA = id.compareTo(partner.getUUID()) <= 0;
        PlacedComponent compA = hereIsA ? placed : partner;
        PlacedComponent compB = hereIsA ? partner : placed;
        CircuitBoardBlockEntity beA = hereIsA ? boardHere : boardThere;
        CircuitBoardBlockEntity beB = hereIsA ? boardThere : boardHere;

        Link link = LINKS.get(compA.getUUID());
        if (link == null || !link.idA.equals(compA.getUUID()) || !link.idB.equals(compB.getUUID())) {
            if (link != null)
                dropLink(link);
            link = new Link(level, compA.getUUID(), beA.getBlockPos(), compB.getUUID(), beB.getBlockPos());
            LINKS.put(link.idA, link);
            LINKS.put(link.idB, link);
        }

        ElectricBehaviour ebA = beA.getElectricBehaviour();
        ElectricBehaviour ebB = beB.getElectricBehaviour();
        if (ebA == null || ebB == null || ebA.isPaused() || ebB.isPaused()
                || beA.getBaked() == null || beB.getBaked() == null) {
            link.removeWires();
            return;
        }

        IElectricNode[] currentA = new IElectricNode[PIN_COUNT];
        IElectricNode[] currentB = new IElectricNode[PIN_COUNT];
        try {
            for (int i = 0; i < PIN_COUNT; i++) {
                currentA[i] = beA.getBaked().getNode(new CircuitSchematic.Node(compA, i));
                currentB[i] = beB.getBaked().getNode(new CircuitSchematic.Node(compB, i));
            }
        } catch (RuntimeException e) {
            // A board is mid-rebake; try again next tick.
            link.removeWires();
            return;
        }

        if (isIntact(link, ebA, ebB, currentA, currentB))
            return;

        link.removeWires();
        connect(link, level, ebA, ebB, currentA, currentB);
    }

    private static boolean isIntact(Link link, ElectricBehaviour ebA, ElectricBehaviour ebB,
                                    IElectricNode[] currentA, IElectricNode[] currentB) {
        if (!link.hasWires() || link.behaviourA != ebA || link.behaviourB != ebB)
            return false;
        for (int i = 0; i < PIN_COUNT; i++) {
            if (link.nodesA[i] != currentA[i] || link.nodesB[i] != currentB[i])
                return false;
            ElectricWire wire = link.wires.get(i);
            if (wire.getNetwork() == null
                    || currentA[i].getNetwork() != wire.getNetwork()
                    || currentB[i].getNetwork() != wire.getNetwork())
                return false;
        }
        return true;
    }

    private static void connect(Link link, Level level, ElectricBehaviour ebA, ElectricBehaviour ebB,
                                IElectricNode[] nodesA, IElectricNode[] nodesB) {
        link.behaviourA = ebA;
        link.behaviourB = ebB;
        for (int i = 0; i < PIN_COUNT; i++) {
            link.nodesA[i] = nodesA[i];
            link.nodesB[i] = nodesB[i];

            ElectricWire wire = new ElectricWire(WIRE_RESISTANCE, nodesA[i], nodesB[i]);
            ElectricalNetwork network = unifyNetwork(level, ebA, nodesA[i], ebB, nodesB[i]);
            network.addWire(wire);
            link.wires.add(wire);
        }
    }

    /** Same logic as CircuitBoardBlockEntity#unifyNetwork (private there): put both nodes in one network. */
    private static ElectricalNetwork unifyNetwork(Level level, ElectricBehaviour eb1, IElectricNode node1,
                                                  ElectricBehaviour eb2, IElectricNode node2) {
        ElectricalNetwork net1 = node1.getNetwork();
        ElectricalNetwork net2 = node2.getNetwork();
        ElectricalNetwork network;
        if (net1 == null && net2 == null) {
            network = GlobalElectricNetworks.getWorldNetworks(level).newNetwork();
            eb1.tracedAdd(network, node1);
            eb2.tracedAdd(network, node2);
        } else if (net1 == null) {
            network = net2;
            eb1.tracedAdd(net2, node1);
        } else if (net2 == null) {
            network = net1;
            eb2.tracedAdd(net1, node2);
        } else if (net1 != net2) {
            if (net1.size() >= net2.size()) {
                network = net1;
                network.merge(net2);
            } else {
                network = net2;
                network.merge(net1);
            }
        } else {
            network = net1;
        }
        return network;
    }

    /** One entry per active cable in {@code level} (used for cutting). */
    public static List<RibbonCables.CableRef> refs(@NotNull Level level) {
        List<RibbonCables.CableRef> result = new ArrayList<>();
        for (Map.Entry<UUID, Link> entry : LINKS.entrySet()) {
            Link link = entry.getValue();
            if (link.level == level && entry.getKey().equals(link.idA))
                result.add(new RibbonCables.CableRef(link.idA, link.boardA, link.idB, link.boardB));
        }
        return result;
    }

    /** Forgets the link of this connector and removes its wires (cut, partner gone, no longer linked). */
    public static void drop(@NotNull UUID connectorId) {
        Link link = LINKS.get(connectorId);
        if (link != null)
            dropLink(link);
    }

    private static void dropWires(UUID connectorId) {
        Link link = LINKS.get(connectorId);
        if (link != null)
            link.removeWires();
    }

    private static void dropLink(Link link) {
        link.removeWires();
        LINKS.remove(link.idA, link);
        LINKS.remove(link.idB, link);
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel().isClientSide())
            return;
        ChunkPos chunk = event.getChunk().getPos();
        List<Link> affected = new ArrayList<>();
        for (Link link : LINKS.values()) {
            if (affected.contains(link))
                continue;
            if (isIn(chunk, link.boardA) || isIn(chunk, link.boardB))
                affected.add(link);
        }
        // Remove the shared wires before the board pauses and drops its nodes.
        // sync() rebuilds them once both boards are loaded and running again.
        for (Link link : affected)
            link.removeWires();
    }

    private static boolean isIn(ChunkPos chunk, BlockPos pos) {
        return (pos.getX() >> 4) == chunk.x && (pos.getZ() >> 4) == chunk.z;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LINKS.clear();
    }
}
