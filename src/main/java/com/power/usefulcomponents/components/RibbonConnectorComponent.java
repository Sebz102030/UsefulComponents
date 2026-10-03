package com.power.usefulcomponents.components;

import com.google.common.collect.ImmutableCollection;
import com.power.usefulcomponents.UsefulComponents;
import com.power.usefulcomponents.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 4-pin ribbon connector. Two flavors are registered from this same class -
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

    /** 1 Ribbon Cable item per this many blocks of straight-line board-to-board distance. */
    private static final double BLOCKS_PER_ITEM = 2.0D;

    /** Ribbon Cable items a cable between two boards costs (and gives back when it is removed). */
    public static int cableCost(@NotNull BlockPos a, @NotNull BlockPos b) {
        double distance = Math.sqrt(a.distSqr(b));
        return (int) Math.max(1, Math.ceil(distance / BLOCKS_PER_ITEM));
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

    /** Drops {@code items} Ribbon Cable as item entities at {@code pos}. */
    public static void dropItems(@NotNull Level level, @NotNull BlockPos pos, int items) {
        if (items <= 0 || !(level instanceof ServerLevel))
            return;
        int max = ModItems.RIBBON_CABLE.get().getDefaultMaxStackSize();
        while (items > 0) {
            ItemStack stack = new ItemStack(ModItems.RIBBON_CABLE.get(), Math.min(items, max));
            items -= stack.getCount();
            Block.popResource(level, pos, stack);
        }
    }

    public static final BooleanProperty LINKED =
            new BooleanProperty(UsefulComponents.MODID, "ribbon_linked").hidden().cast();
    public static final StringProperty PARTNER_POS =
            new StringProperty(UsefulComponents.MODID, "ribbon_partner_pos").hidden().cast();
    public static final StringProperty PARTNER_UUID =
            new StringProperty(UsefulComponents.MODID, "ribbon_partner_uuid").hidden().cast();

    private static final Map<UUID, PendingEnd> PENDING = new HashMap<>();

    private final Orientation orientation;

    public RibbonConnectorComponent(ComponentFootprint footprint, Orientation orientation) {
        super(footprint);
        this.orientation = orientation;
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
    }

    @Override
    public void bake(@NotNull PlacedComponent placed, @NotNull ComponentCircuitBuilder builder,
                      ThermalBuilder.@NotNull IEmitter thermals) {
        builder.terminalNode(PIN_A);
        builder.terminalNode(PIN_B);
        builder.terminalNode(PIN_C);
        builder.terminalNode(PIN_D);
    }

    @Override
    public boolean tick(@NotNull PlacedComponent placed) {
        if (placed.isClient()) {
            // Tell the client cable renderer / cutter that this cable exists.
            RibbonCables.touchClient(placed);
            return true;
        }
        // Self-healing cleanup: if we're supposedly linked but the partner
        // no longer resolves (its board was broken, or the component is
        // otherwise gone), drop our own link so we don't stay "connected"
        // to nothing forever. A partner whose chunk is merely unloaded is
        // NOT gone - that case is left alone (and never force-loaded).
        if (placed.get(LINKED)) {
            BlockPos partnerBoard = decodePartnerPos(placed);
            boolean partnerChunkLoaded = partnerBoard != null && placed.getWorld().hasChunkAt(partnerBoard);
            if (partnerChunkLoaded && findPartner(placed) == null) {
                // The other end is gone (board destroyed some way that wasn't
                // a player breaking it): the cable falls off at this end.
                dropItems(placed.getWorld(), placed.getPos(), cableCost(placed.getPos(), partnerBoard));
                RibbonLinks.drop(placed.getUUID());
                placed.set(LINKED, false);
                placed.setString(PARTNER_POS, "");
                placed.setString(PARTNER_UUID, "");
                placed.notifyClients(LINKED);
                placed.notifyClients(PARTNER_POS);
                placed.notifyClients(PARTNER_UUID);
                return true;
            }
        }
        // Keep the four pins electrically joined to the partner's pins.
        RibbonLinks.sync(placed);
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
            PENDING.put(playerId, new PendingEnd(thisBoardPos, thisComponentId));
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
            int itemsNeeded = cableCost(thisBoardPos, pending.boardPos);
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
        int items = partnerBoard != null ? cableCost(component.getPos(), partnerBoard) : 1;

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
        tooltip.add(Component.literal(orientation == Orientation.SIDE
                ? "Ribbon connector (side)" : "Ribbon connector (front)"));
        tooltip.add(Component.literal(placed.get(LINKED) ? "Linked" : "Not linked"));
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
        if (!(placed.getWorld().getBlockEntity(partnerBoardPos) instanceof CircuitBoardBlockEntity partnerBoard))
            return null;
        for (PlacedComponent candidate : partnerBoard.getComponents(RibbonConnectorComponent.class)) {
            if (candidate.getUUID().equals(partnerUuid))
                return candidate;
        }
        return null;
    }

    private record PendingEnd(BlockPos boardPos, UUID componentId) {
    }
}
