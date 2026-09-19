package com.power.usefulcomponents.components;

import com.google.common.collect.ImmutableCollection;
import com.mojang.blaze3d.vertex.PoseStack;
import com.power.usefulcomponents.UsefulComponents;
import com.power.usefulcomponents.config.UsefulComponentsConfig;
import com.power.usefulcomponents.registry.ModDataComponents;
import com.power.usefulcomponents.registry.ModItems;
import com.power.usefulcomponents.render.RibbonCableRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.circuits.circuitboard.CircuitBoardBlockEntity;
import org.patryk3211.powergrid.circuits.circuitboard.ComponentCircuitBuilder;
import org.patryk3211.powergrid.circuits.components.IComponentGoggleInformation;
import org.patryk3211.powergrid.circuits.components.IInteractableComponent;
import org.patryk3211.powergrid.circuits.components.IRenderedComponent;
import org.patryk3211.powergrid.circuits.components.OrientableComponent;
import org.patryk3211.powergrid.circuits.components.properties.BooleanProperty;
import org.patryk3211.powergrid.circuits.components.properties.ComponentProperty;
import org.patryk3211.powergrid.circuits.components.properties.StringProperty;
import org.patryk3211.powergrid.circuits.schematic.ComponentFootprint;
import org.patryk3211.powergrid.circuits.schematic.PlacedComponent;
import org.patryk3211.powergrid.circuits.thermal.ThermalBuilder;
import org.patryk3211.powergrid.collections.ModdedTags;
import org.patryk3211.powergrid.utility.PlayerUtilities;

import java.util.List;
import java.util.UUID;

/**
 * A ribbon cable connector pad on a circuit board. Two connectors are
 * linked by right-clicking one, then the other, while holding a
 * {@code ribbon_cable} item (mirroring how PowerGrid's own wires work) -
 * each connector can only hold one cable at a time. Right-clicking a
 * linked connector with wire cutters (the {@code powergrid:wire_cutters}
 * item tag) removes the cable and refunds cable items based on its length.
 * <p>
 * This class owns the component's state/networking/business logic only.
 * The pending-connection "which item is glowing right now" state lives on
 * the held ItemStack itself (see {@link RibbonCableConnectionRequest} /
 * {@link RibbonCableItem}), and the actual mesh-building for the cable
 * lives in {@link RibbonCableRenderer}.
 */
public class RibbonConnectorComponent extends OrientableComponent
        implements IComponentGoggleInformation, IInteractableComponent, IRenderedComponent {

    public enum Orientation { SIDE, FRONT }

    public static final int PIN_A = 0;
    public static final int PIN_B = 1;
    public static final int PIN_C = 2;
    public static final int PIN_D = 3;

    public static final BooleanProperty LINKED =
            new BooleanProperty(UsefulComponents.MODID, "ribbon_linked");
    public static final StringProperty PARTNER_POS =
            new StringProperty(UsefulComponents.MODID, "ribbon_partner_pos");
    public static final StringProperty PARTNER_UUID =
            new StringProperty(UsefulComponents.MODID, "ribbon_partner_uuid");

    private final Orientation orientation;

    public RibbonConnectorComponent(ComponentFootprint footprint, Orientation orientation) {
        super(footprint);
        this.orientation = orientation;
    }

    /** Used by {@link RibbonCableRenderer} to pick the right anchor side (front face vs. edge). */
    public Orientation orientation() {
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
    public net.minecraft.world.phys.shapes.VoxelShape getShape(@NotNull PlacedComponent placed) {
        return IInteractableComponent.extrudedFootprint(placed, 2 / 16f);
    }

    @Override
    public InteractionResult use(@NotNull CircuitBoardBlockEntity be, @NotNull PlacedComponent component,
                                  @NotNull Player player) {
        ItemStack held = player.getMainHandItem();

        if (held.is(ModdedTags.Item.WIRE_CUTTERS.tag) || held.is(ModdedTags.Item.BAD_WIRE_CUTTERS.tag))
            return tryCut(component, player);

        if (!held.is(ModItems.RIBBON_CABLE.get()))
            return InteractionResult.PASS;
        if (player.level().isClientSide)
            return InteractionResult.SUCCESS;

        var pendingKey = ModDataComponents.PENDING_RIBBON_CONNECTION.get();
        RibbonCableConnectionRequest pending = held.get(pendingKey);

        if (player.isShiftKeyDown() && pending != null) {
            held.remove(pendingKey);
            player.displayClientMessage(Component.literal("Selection cancelled."), true);
            return InteractionResult.SUCCESS;
        }

        BlockPos thisBoardPos = be.getBlockPos();
        UUID thisComponentId = component.getUUID();

        if (pending == null) {
            if (component.get(LINKED)) {
                player.displayClientMessage(Component.literal("This connector already has a cable attached."), true);
                return InteractionResult.SUCCESS;
            }
            held.set(pendingKey, new RibbonCableConnectionRequest(thisBoardPos, thisComponentId));
            player.displayClientMessage(
                    Component.literal("First connector selected - right-click the second one (sneak-click to cancel)."), true);
            return InteractionResult.SUCCESS;
        }

        if (pending.boardPos().equals(thisBoardPos) && pending.componentId().equals(thisComponentId)) {
            held.remove(pendingKey);
            player.displayClientMessage(Component.literal("Selection cancelled."), true);
            return InteractionResult.SUCCESS;
        }

        if (component.get(LINKED)) {
            player.displayClientMessage(Component.literal("That connector already has a cable attached."), true);
            return InteractionResult.SUCCESS;
        }

        if (!(player.level().getBlockEntity(pending.boardPos()) instanceof CircuitBoardBlockEntity otherBoard)) {
            player.displayClientMessage(Component.literal("The other board is no longer there."), true);
            held.remove(pendingKey);
            return InteractionResult.SUCCESS;
        }

        PlacedComponent other = null;
        for (PlacedComponent candidate : otherBoard.getComponents(RibbonConnectorComponent.class)) {
            if (candidate.getUUID().equals(pending.componentId())) {
                other = candidate;
                break;
            }
        }
        if (other == null) {
            player.displayClientMessage(Component.literal("The other connector is no longer there."), true);
            held.remove(pendingKey);
            return InteractionResult.SUCCESS;
        }
        if (other.get(LINKED)) {
            player.displayClientMessage(Component.literal("The other connector already has a cable attached."), true);
            return InteractionResult.SUCCESS;
        }

        int cost = itemCost(RibbonCableRenderer.cableLength(component, other));
        if (!PlayerUtilities.hasEnoughItems(player, held, cost)) {
            player.displayClientMessage(
                    Component.literal("Not enough ribbon cable - need " + cost + "."), true);
            return InteractionResult.SUCCESS;
        }
        PlayerUtilities.removeItems(player, held, cost);
        held.remove(pendingKey);

        link(component, pending.boardPos(), pending.componentId());
        link(other, thisBoardPos, thisComponentId);

        player.displayClientMessage(Component.literal("Ribbon cable connected (" + cost + " used)."), true);
        return InteractionResult.SUCCESS;
    }

    private InteractionResult tryCut(@NotNull PlacedComponent component, @NotNull Player player) {
        if (!component.get(LINKED))
            return InteractionResult.PASS;
        if (player.level().isClientSide)
            return InteractionResult.SUCCESS;

        PlacedComponent partner = findPartner(component);
        int refund = partner != null ? itemCost(RibbonCableRenderer.cableLength(component, partner)) : 1;

        unlink(component);
        if (partner != null)
            unlink(partner);

        ItemStack refundStack = new ItemStack(ModItems.RIBBON_CABLE.get(), refund);
        if (!player.getInventory().add(refundStack))
            player.drop(refundStack, false);

        player.displayClientMessage(Component.literal("Ribbon cable cut - " + refund + " returned."), true);
        return InteractionResult.SUCCESS;
    }

    /** items = ceil(length_in_blocks * ribbonItemsPerBlock), minimum 1. Governs both connecting cost and cutting refund. */
    private static int itemCost(double lengthBlocks) {
        return Math.max(1, (int) Math.ceil(lengthBlocks * UsefulComponentsConfig.RIBBON_ITEMS_PER_BLOCK.get()));
    }

    private static void link(PlacedComponent component, BlockPos partnerPos, UUID partnerId) {
        component.set(LINKED, true);
        component.setString(PARTNER_POS, encodePos(partnerPos));
        component.setString(PARTNER_UUID, encodeUuid(partnerId));
        component.notifyClients(LINKED);
        component.notifyClients(PARTNER_POS);
        component.notifyClients(PARTNER_UUID);
    }

    private static void unlink(PlacedComponent component) {
        component.set(LINKED, false);
        component.setString(PARTNER_POS, "");
        component.setString(PARTNER_UUID, "");
        component.notifyClients(LINKED);
        component.notifyClients(PARTNER_POS);
        component.notifyClients(PARTNER_UUID);
    }

    @Override
    public boolean addToGoggleTooltip(@NotNull PlacedComponent placed, @NotNull List<Component> tooltip,
                                       boolean isPlayerSneaking) {
        tooltip.add(Component.literal(orientation == Orientation.SIDE
                ? "Ribbon connector (side)" : "Ribbon connector (front)"));
        if (!placed.get(LINKED)) {
            tooltip.add(Component.literal("Not linked"));
            return true;
        }
        PlacedComponent partner = findPartner(placed);
        if (partner == null) {
            tooltip.add(Component.literal("Linked - partner missing"));
            return true;
        }
        double length = RibbonCableRenderer.cableLength(placed, partner);
        tooltip.add(Component.literal(String.format("Linked - %.1f blocks (%d cable used)", length, itemCost(length))));
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

    @Nullable
    private static PlacedComponent findPartner(@NotNull PlacedComponent placed) {
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

    @Override
    public void render(@NotNull CircuitBoardBlockEntity be, @NotNull PlacedComponent placed, float partialTicks,
                        @NotNull PoseStack ms, @NotNull MultiBufferSource bufferSource, int light, int overlay) {
        if (!placed.get(LINKED))
            return;
        PlacedComponent partner = findPartner(placed);
        if (partner == null)
            return;
        // Only draw once per pair - let the "lower" UUID side own the draw call.
        if (placed.getUUID().compareTo(partner.getUUID()) > 0)
            return;
        RibbonCableRenderer.render(be, placed, partner, ms, bufferSource, light, overlay);
    }
}
