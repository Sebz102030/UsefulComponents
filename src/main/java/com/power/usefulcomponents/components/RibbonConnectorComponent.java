package com.power.usefulcomponents.components;

import com.google.common.collect.ImmutableCollection;
import com.power.usefulcomponents.UsefulComponents;
import com.power.usefulcomponents.compat.SableCompat;
import com.power.usefulcomponents.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.circuits.circuitboard.CircuitBoardBlockEntity;
import org.patryk3211.powergrid.circuits.circuitboard.ComponentCircuitBuilder;
import org.patryk3211.powergrid.circuits.components.IComponentGoggleInformation;
import org.patryk3211.powergrid.circuits.components.IInteractableComponent;
import org.patryk3211.powergrid.circuits.components.OrientableComponent;
import org.patryk3211.powergrid.circuits.components.properties.BooleanProperty;
import org.patryk3211.powergrid.circuits.components.properties.ComponentProperty;
import org.patryk3211.powergrid.circuits.components.properties.StringProperty;
import org.patryk3211.powergrid.circuits.schematic.ComponentFootprint;
import org.patryk3211.powergrid.circuits.schematic.PlacedComponent;
import org.patryk3211.powergrid.circuits.thermal.ThermalBuilder;
import org.patryk3211.powergrid.collections.ModdedTags;
import org.patryk3211.powergrid.utility.Lang;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 4-pin or 10-pin ribbon connector (see {@link #getPinCount()}). A cable only
 * ever joins two connectors with the SAME pin count. Two flavors of each are
 * registered from this same class -
 * SIDE (mounted sticking out of the board's edge) and FRONT (mounted
 * sticking straight out of the board's face, like a card standing up in a
 * slot). Cable drawing lives in RibbonCableClient / RibbonCableRenderer
 * (com.power.usefulcomponents.render) - this class only handles component
 * behavior: properties, pins, linking, and now cutting/cost/cleanup.
 *
 * Linking: right-click one connector with a Ribbon Cable item, then
 * right-click a second connector (any board) to link them. A connector
 * already linked refuses a new cable. Linking now costs
 * {@code ceil(distance / 2)} Ribbon Cable items (1 per 2 blocks), skipped
 * entirely in creative mode, mirroring Power Grid's own wire-length cost
 * convention.
 *
 * Cutting: right-clicking a linked connector with any item tagged
 * {@code powergrid:wire_cutters} unlinks it (and its partner) instead of
 * trying to start a new link.
 *
 * Board removal cleanup: Power Grid's Component base class has no
 * "this component was removed" hook to override, so instead each tick()
 * checks whether its own partner still resolves (board still loaded and
 * placed, component UUID still present on it) and self-heals by clearing
 * its own link if not - this covers "board holding my partner was broken"
 * without needing an unverified removal callback.
 */
public class RibbonConnectorComponent extends OrientableComponent
        implements IComponentGoggleInformation, IInteractableComponent {

    public enum Orientation { SIDE, FRONT }

    public static final int PIN_A = 0;
    public static final int PIN_B = 1;
    public static final int PIN_C = 2;
    public static final int PIN_D = 3;

    /** Pin count of the standard connector. */
    public static final int PINS_STANDARD = 4;
    /** Pin count of the wide 10-pin connector. */
    public static final int PINS_WIDE = 10;

    /** 1 Ribbon Cable item per this many blocks of straight-line board-to-board distance. */
    private static final double BLOCKS_PER_ITEM = 2.0D;

    /** Ticks a partner may be missing before the cable is considered torn off (boards move for a moment when a sub-level is made or dissolved). */
    private static final int MISSING_GRACE_TICKS = 60;
    /** Ticks two boards must stay in different sub-levels before the cable snaps. */
    private static final int SUBLEVEL_MISMATCH_TICKS = 10;

    /**
     * Ribbon Cable items a cable between two boards costs (and gives back when
     * it is removed). Distance is the REAL distance (boards on a sub-level sit at
     * enormous plot coordinates), and the result never exceeds one stack - a
     * cable can't be placed with more than that in hand anyway, so a bigger
     * number could only come from bad coordinates.
     */
    public static int cableCost(@NotNull Level level, @NotNull BlockPos a, @NotNull BlockPos b) {
        double distance = SableCompat.globalDistance(level, a, b);
        int cost = (int) Math.max(1, Math.ceil(distance / BLOCKS_PER_ITEM));
        return Math.min(cost, ModItems.RIBBON_CABLE.get().getDefaultMaxStackSize());
    }

    /** Puts {@code items} Ribbon Cable into the player's inventory, dropping what doesn't fit. Not in creative. */
    public static void giveBack(@NotNull Player player, int items) {
        if (items <= 0 || player.isCreative())
            return;
        int max = ModItems.RIBBON_CABLE.get().getDefaultMaxStackSize();
        while (items > 0) {
            ItemStack stack = new ItemStack(ModItems.RIBBON_CABLE.get(), Math.min(items, max));
            items -= stack.getCount();
            if (!player.getInventory().add(stack))
                player.drop(stack, false);
        }
    }

    /** Drops {@code items} Ribbon Cable as item entities at {@code pos} (a board position, projected out of a sub-level if needed). */
    public static void dropItems(@NotNull Level level, @NotNull BlockPos pos, int items) {
        if (items <= 0 || !(level instanceof ServerLevel))
            return;
        int max = ModItems.RIBBON_CABLE.get().getDefaultMaxStackSize();
        items = Math.min(items, max * 4); // never flood the world, whatever the caller computed
        boolean onSubLevel = SableCompat.isInSubLevel(level, pos);
        Vec3 real = onSubLevel ? SableCompat.toGlobal(level, pos, Vec3.atCenterOf(pos)) : null;
        while (items > 0) {
            ItemStack stack = new ItemStack(ModItems.RIBBON_CABLE.get(), Math.min(items, max));
            items -= stack.getCount();
            if (real != null) {
                ItemEntity entity = new ItemEntity(level, real.x, real.y, real.z, stack);
                entity.setDefaultPickUpDelay();
                level.addFreshEntity(entity);
            } else {
                Block.popResource(level, pos, stack);
            }
        }
    }

    public static final BooleanProperty LINKED =
            new BooleanProperty(UsefulComponents.MODID, "ribbon_linked").hidden().unsafe().cast();
    public static final StringProperty PARTNER_POS =
            new StringProperty(UsefulComponents.MODID, "ribbon_partner_pos").hidden().unsafe().cast();
    public static final StringProperty PARTNER_UUID =
            new StringProperty(UsefulComponents.MODID, "ribbon_partner_uuid").hidden().unsafe().cast();

    private static final Map<UUID, PendingEnd> PENDING = new HashMap<>();

    private final Orientation orientation;
    private final int pinCount;

    public RibbonConnectorComponent(ComponentFootprint footprint, Orientation orientation) {
        this(footprint, orientation, PINS_STANDARD);
    }

    public RibbonConnectorComponent(ComponentFootprint footprint, Orientation orientation, int pinCount) {
        super(footprint);
        this.orientation = orientation;
        this.pinCount = pinCount;
    }

    /** How many pins (and so how many conductors in the cable) this connector has. */
    public int getPinCount() {
        return pinCount;
    }

    /** Used by the cable renderer to tell SIDE and FRONT connectors apart. */
    public Orientation getOrientation() {
        return orientation;
    }

    @Override
    protected void addProperties(ImmutableCollection.Builder<ComponentProperty<?>> properties) {
        super.addProperties(properties);
        properties.add(LINKED);
        properties.add(PARTNER_POS);
        properties.add(PARTNER_UUID);
        properties.add(LABEL);
    }

    @Override
    public void bake(@NotNull PlacedComponent placed, @NotNull ComponentCircuitBuilder builder,
                      ThermalBuilder.@NotNull IEmitter thermals) {
        for (int i = 0; i < pinCount; i++)
            builder.terminalNode(i);
    }

    @Override
    public boolean tick(@NotNull PlacedComponent placed) {
        if (placed.isClient()) {
            // Tell the client cable renderer / cutter that this cable exists.
            RibbonCables.touchClient(placed);
            return true;
        }
        Level level = placed.getWorld();
        UUID self = placed.getUUID();
        long now = level.getGameTime();

        if (placed.get(LINKED)) {
            // Tell the registry where this connector is NOW, so its partner can
            // follow it when the structure is moved into / out of a sub-level.
            RibbonLinks.noteLocation(placed);

            UUID partnerId = decodePartnerId(placed);
            BlockPos partnerBoard = decodePartnerPos(placed);

            // The partner's board moved (sub-level assembled or dissolved):
            // follow it instead of treating the cable as torn off.
            BlockPos moved = partnerId == null ? null : RibbonLinks.locationOf(level, partnerId);
            if (moved != null && !moved.equals(partnerBoard) && findConnector(level, moved, partnerId) != null) {
                placed.setString(PARTNER_POS, encodePos(moved));
                placed.notifyClients(PARTNER_POS);
                partnerBoard = moved;
            }

            // Self-healing cleanup: if we're supposedly linked but the partner
            // no longer resolves (its board was broken, or the component is
            // otherwise gone), drop our own link so we don't stay "connected"
            // to nothing forever. A partner whose chunk is merely unloaded is
            // NOT gone - that case is left alone (and never force-loaded).
            boolean partnerChunkLoaded = partnerBoard != null && level.hasChunkAt(partnerBoard);
            PlacedComponent partner = partnerChunkLoaded ? findPartner(placed) : null;
            if (partnerChunkLoaded && partner == null) {
                // Wait a moment before giving up: while a sub-level is being
                // made or dissolved the two boards don't move on the same tick.
                if (RibbonLinks.trackMissing(self, now) < MISSING_GRACE_TICKS)
                    return true;
                // The other end is gone (board destroyed some way that wasn't
                // a player breaking it): the cable falls off at this end.
                dropItems(level, placed.getPos(), cableCost(level, placed.getPos(), partnerBoard));
                RibbonLinks.drop(self);
                placed.set(LINKED, false);
                placed.setString(PARTNER_POS, "");
                placed.setString(PARTNER_UUID, "");
                placed.notifyClients(LINKED);
                placed.notifyClients(PARTNER_POS);
                placed.notifyClients(PARTNER_UUID);
                return true;
            }
            RibbonLinks.clearMissing(self);

            // A cable can't reach from one physics structure to another (or
            // from a structure to the normal world): it snaps and drops.
            if (partner != null && SableCompat.inDifferentSubLevels(level, placed.getPos(), partnerBoard)) {
                if (RibbonLinks.trackSubLevelMismatch(self, now) >= SUBLEVEL_MISMATCH_TICKS) {
                    int items = unlink(placed);
                    if (items > 0)
                        dropItems(level, placed.getPos(), items);
                }
                return true;
            }
            RibbonLinks.clearSubLevelMismatch(self);
        }
        // Keep the four pins electrically joined to the partner's pins.
        try {
            RibbonLinks.sync(placed);
        } catch (RuntimeException e) {
            // Safety net: a board caught mid-move must never take the server down.
            UsefulComponents.LOGGER.warn("Ribbon link update failed for connector {}, dropping its wires", self, e);
            RibbonLinks.drop(self);
        }
        return true;
    }

    @Override
    public VoxelShape getShape(@NotNull PlacedComponent placed) {
        return IInteractableComponent.extrudedFootprint(placed, 2 / 16f);
    }

    @Override
    public InteractionResult use(@NotNull CircuitBoardBlockEntity be, @NotNull PlacedComponent component,
                                  @NotNull Player player) {
        if (player.level().isClientSide)
            return InteractionResult.SUCCESS;

        // Cutters: unlink instead of trying to start/continue a new link.
        if (player.getMainHandItem().is(ModdedTags.Item.WIRE_CUTTERS.tag)) {
            return handleCut(component, player);
        }

        if (!player.getMainHandItem().is(ModItems.RIBBON_CABLE.get()))
            return InteractionResult.PASS;

        UUID playerId = player.getUUID();
        BlockPos thisBoardPos = be.getBlockPos();
        UUID thisComponentId = component.getUUID();

        PendingEnd pending = PENDING.get(playerId);

        if (pending == null) {
            if (component.get(LINKED)) {
                player.displayClientMessage(Component.literal("This connector already has a cable attached."), true);
                return InteractionResult.SUCCESS;
            }
            PENDING.put(playerId, new PendingEnd(thisBoardPos, thisComponentId, pinCount));
            player.displayClientMessage(Component.literal("First connector selected - right-click the second one."), true);
            return InteractionResult.SUCCESS;
        }

        PENDING.remove(playerId);

        if (pending.boardPos.equals(thisBoardPos) && pending.componentId.equals(thisComponentId)) {
            player.displayClientMessage(Component.literal("Selection cancelled."), true);
            return InteractionResult.SUCCESS;
        }

        if (component.get(LINKED)) {
            player.displayClientMessage(Component.literal("That connector already has a cable attached."), true);
            return InteractionResult.SUCCESS;
        }

        if (pending.pins != pinCount) {
            player.displayClientMessage(Component.literal("Pin count mismatch - a " + pending.pins
                    + "-pin connector can't be joined to a " + pinCount + "-pin one."), true);
            return InteractionResult.SUCCESS;
        }

        if (!(player.level().getBlockEntity(pending.boardPos) instanceof CircuitBoardBlockEntity otherBoard)) {
            player.displayClientMessage(Component.literal("The other board is no longer there."), true);
            return InteractionResult.SUCCESS;
        }

        PlacedComponent other = null;
        for (PlacedComponent candidate : otherBoard.getComponents(RibbonConnectorComponent.class)) {
            if (candidate.getUUID().equals(pending.componentId)) {
                other = candidate;
                break;
            }
        }
        if (other == null) {
            player.displayClientMessage(Component.literal("The other connector is no longer there."), true);
            return InteractionResult.SUCCESS;
        }
        if (other.get(LINKED)) {
            player.displayClientMessage(Component.literal("The other connector already has a cable attached."), true);
            return InteractionResult.SUCCESS;
        }

        // Distance-based cost: 1 Ribbon Cable item per 2 blocks, skipped in creative.
        if (!player.isCreative()) {
            int itemsNeeded = cableCost(player.level(), thisBoardPos, pending.boardPos);
            var heldStack = player.getMainHandItem();
            if (heldStack.getCount() < itemsNeeded) {
                player.displayClientMessage(Component.literal(
                        "Not enough Ribbon Cable - need " + itemsNeeded + " for this distance."), true);
                return InteractionResult.SUCCESS;
            }
            heldStack.shrink(itemsNeeded);
        }

        component.set(LINKED, true);
        component.setString(PARTNER_POS, encodePos(pending.boardPos));
        component.setString(PARTNER_UUID, encodeUuid(pending.componentId));
        component.notifyClients(LINKED);
        component.notifyClients(PARTNER_POS);
        component.notifyClients(PARTNER_UUID);

        other.set(LINKED, true);
        other.setString(PARTNER_POS, encodePos(thisBoardPos));
        other.setString(PARTNER_UUID, encodeUuid(thisComponentId));
        other.notifyClients(LINKED);
        other.notifyClients(PARTNER_POS);
        other.notifyClients(PARTNER_UUID);

        // Wire the pins together right away instead of waiting for the next tick.
        RibbonLinks.sync(component);

        player.displayClientMessage(Component.literal("Ribbon cable connected."), true);
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult handleCut(@NotNull PlacedComponent component, @NotNull Player player) {
        int items = unlink(component);
        if (items < 0) {
            player.displayClientMessage(Component.literal("Nothing to cut here."), true);
            return InteractionResult.SUCCESS;
        }
        giveBack(player, items);
        player.displayClientMessage(Component.literal("Ribbon cable cut."), true);
        return InteractionResult.SUCCESS;
    }

    /**
     * Removes the cable of this connector from BOTH ends (and the electrical
     * link between them). Server side. Returns how many Ribbon Cable items
     * that cable was worth (see {@link #cableCost}), or -1 if it wasn't linked.
     */
    public static int unlink(@NotNull PlacedComponent component) {
        if (!component.get(LINKED))
            return -1;

        PlacedComponent partner = findPartner(component);
        BlockPos partnerBoard = decodePartnerPos(component);
        int items = partnerBoard != null ? cableCost(component.getWorld(), component.getPos(), partnerBoard) : 1;

        // Remove the electrical connection first, then the saved link.
        RibbonLinks.drop(component.getUUID());

        component.set(LINKED, false);
        component.setString(PARTNER_POS, "");
        component.setString(PARTNER_UUID, "");
        component.notifyClients(LINKED);
        component.notifyClients(PARTNER_POS);
        component.notifyClients(PARTNER_UUID);

        if (partner != null) {
            partner.set(LINKED, false);
            partner.setString(PARTNER_POS, "");
            partner.setString(PARTNER_UUID, "");
            partner.notifyClients(LINKED);
            partner.notifyClients(PARTNER_POS);
            partner.notifyClients(PARTNER_UUID);
        }
        return items;
    }

    @Override
    public boolean addToGoggleTooltip(@NotNull PlacedComponent placed, @NotNull List<Component> tooltip,
                                       boolean isPlayerSneaking) {
            var connector_name = (pinCount == PINS_STANDARD ? "Ribbon connector" : "Ribbon connector " + pinCount + "-pin")
                + (orientation == Orientation.SIDE ? " (side)" : " (front)");
        
        if(placed.has(LABEL)) {
            var label = placed.get(LABEL);
            if(! label.isEmpty()) {
                Lang.text(label).forGoggles(tooltip);
                tooltip.add(Component.literal(connector_name));
            }else {
                Lang.text(connector_name).forGoggles(tooltip);
            }
        }

        
        tooltip.add(Component.literal("state: " + (placed.get(LINKED) ? "Linked" : "Not linked")));
        return true;
    }

    private static String encodePos(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String encodeUuid(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    @Nullable
    private static BlockPos decodePos(String encoded) {
        if (encoded == null || encoded.isEmpty())
            return null;
        String[] parts = encoded.split(",");
        if (parts.length != 3)
            return null;
        try {
            return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static UUID decodeUuid(String encoded) {
        if (encoded == null || encoded.length() != 32)
            return null;
        try {
            String dashed = encoded.substring(0, 8) + "-" + encoded.substring(8, 12) + "-"
                    + encoded.substring(12, 16) + "-" + encoded.substring(16, 20) + "-" + encoded.substring(20);
            return UUID.fromString(dashed);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Component UUID of this connector's partner, or null if not linked / unreadable. */
    @Nullable
    public static UUID decodePartnerId(@NotNull PlacedComponent placed) {
        return decodeUuid(placed.getString(PARTNER_UUID));
    }

    /** Board position of this connector's partner, or null if not linked / unreadable. */
    @Nullable
    public static BlockPos decodePartnerPos(@NotNull PlacedComponent placed) {
        return decodePos(placed.getString(PARTNER_POS));
    }

    /** Public so the cable renderer (a different package) can use it too. */
    @Nullable
    public static PlacedComponent findPartner(@NotNull PlacedComponent placed) {
        if (!placed.get(LINKED))
            return null;
        BlockPos partnerBoardPos = decodePos(placed.getString(PARTNER_POS));
        UUID partnerUuid = decodeUuid(placed.getString(PARTNER_UUID));
        if (partnerBoardPos == null || partnerUuid == null)
            return null;
        return findConnector(placed.getWorld(), partnerBoardPos, partnerUuid);
    }

    /** The connector with this UUID on the board at {@code boardPos}, or null. */
    @Nullable
    public static PlacedComponent findConnector(@NotNull Level level, @NotNull BlockPos boardPos, @NotNull UUID id) {
        if (!(level.getBlockEntity(boardPos) instanceof CircuitBoardBlockEntity board))
            return null;
        for (PlacedComponent candidate : board.getComponents(RibbonConnectorComponent.class)) {
            if (candidate.getUUID().equals(id))
                return candidate;
        }
        return null;
    }

    private record PendingEnd(BlockPos boardPos, UUID componentId, int pins) {
    }
}
