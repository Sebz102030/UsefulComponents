package com.power.usefulcomponents.components;

import com.google.common.collect.ImmutableCollection;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.power.usefulcomponents.UsefulComponents;
import com.power.usefulcomponents.registry.ModItems;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.patryk3211.powergrid.circuits.circuitboard.CircuitBoardBlock;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 4-pin ribbon connector. Two flavors are registered from this same class -
 * a SIDE variant (mounted sticking out of the board's edge) and a FRONT
 * variant (mounted sticking straight out of the board's face) - see
 * {@link com.power.usefulcomponents.registry.PowerchipRegistries}. Both
 * share identical footprint/pins/interaction logic; only their intended
 * mounting direction differs (currently a documentation/model distinction -
 * see the "NOT YET WIRED" note below).
 *
 * Footprint: 4 (w) x 2 (l) board-grid cells; 2px model height, matching the
 * requested "4x2x2" size.
 *
 * Pins: A, B, C, D (0-3) - a generic 4-wire pass-through bus. What each pin
 * carries is up to how you wire your board; this component doesn't assign
 * VIN/VOUT/GND/SIGNAL roles itself.
 *
 * Linking: right-click one connector with a Ribbon Cable item, then
 * right-click a second connector (on any board, including the same one) to
 * link them. A connector that's already linked refuses a new cable until
 * unlinked (not yet implemented - see below). The pending "first end"
 * selection is tracked in memory per player (a simple two-click wand
 * pattern), not persisted to disk.
 *
 * NOT YET WIRED: this delivers the full selection/linking UX and a synced
 * LINKED state plus a stored reference to the partner connector (board
 * position + component UUID, via {@link StringProperty}), but does NOT yet
 * make the two boards' electrical networks actually share current. Power
 * Grid has a real primitive for that
 * ({@code GlobalElectricNetworks.makeSimpleConnection}, taking two
 * {@code IWireEndpoint}s), but wiring into it correctly requires verifying
 * that interface's exact contract first - guessing at it risks repeating
 * the ThermalBuilder crash. Once verified, {@code bake()} is where that
 * connection would be established using the stored partner reference.
 *
 * Cable rendering: when {@link #LINKED}, {@link #render} draws a flat 4px
 * ribbon between this connector and its partner - see the field/method
 * docs below for how the geometry is built.
 */
public class RibbonConnectorComponent extends OrientableComponent
        implements IComponentGoggleInformation, IInteractableComponent, IRenderedComponent {

    /** Texture for the short segment right next to a connector: 4px wide x 2px tall. Its v=0 edge touches the connector. */
    private static final ResourceLocation CABLE_END_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(UsefulComponents.MODID, "block/component/ribbon_cable_end");
    /** Texture tiled along the straight run between the two connectors' end segments: 4px x 4px. */
    private static final ResourceLocation CABLE_MIDDLE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(UsefulComponents.MODID, "block/component/ribbon_cable_middle");

    private static final float CABLE_WIDTH = 4 / 16f;
    private static final float END_LENGTH = 2 / 16f;
    private static final float MIDDLE_LENGTH = 4 / 16f;

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

    /** Per-player "first end selected, waiting for second click" state. In-memory only. */
    private static final Map<UUID, PendingEnd> PENDING = new HashMap<>();

    private final Orientation orientation;

    public RibbonConnectorComponent(ComponentFootprint footprint, Orientation orientation) {
        super(footprint);
        this.orientation = orientation;
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
        // Pins exist and are wireable within this board like any other
        // component's pins. Cross-board sharing isn't implemented yet (see
        // class javadoc), so for now each connector's 4 pins only do
        // anything electrically within their own board.
        builder.terminalNode(PIN_A);
        builder.terminalNode(PIN_B);
        builder.terminalNode(PIN_C);
        builder.terminalNode(PIN_D);
    }

    @Override
    public VoxelShape getShape(@NotNull PlacedComponent placed) {
        // Both orientations use the same extruded-footprint collision box
        // for now; only the block model/texture should visually differ
        // between SIDE and FRONT until real distinct geometry is added.
        return IInteractableComponent.extrudedFootprint(placed, 2 / 16f);
    }

    @Override
    public InteractionResult use(@NotNull CircuitBoardBlockEntity be, @NotNull PlacedComponent component,
                                  @NotNull Player player) {
        if (player.level().isClientSide)
            return InteractionResult.SUCCESS;
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

        player.displayClientMessage(Component.literal("Ribbon cable connected."), true);
        return InteractionResult.SUCCESS;
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
        // 32 hex chars, no dashes - fits StringProperty's 32-char limit
        // exactly (a plain UUID.toString() at 36 chars would be truncated).
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

    /**
     * Looks up the partner {@link PlacedComponent} for a linked connector,
     * using the exact same board-lookup + UUID-scan pattern as
     * {@link #use}. Returns null if anything about the stored partner
     * reference no longer resolves (board unloaded/removed, connector
     * removed, etc).
     */
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

    /** World-space anchor for a connector: where its cable leaves the board, and which way it faces. */
    private record Anchor(Vec3 point, Vec3 facing, Vec3 up) {
    }

    /**
     * Computes the world-space point at the middle of the connector's
     * outward-facing edge (so a cable end touching this point touches the
     * connector), plus the outward facing direction and the board's "up"
     * (surface normal), all in world space. Mirrors the exact rotate/translate
     * pipeline {@link PlacedComponent#getExactPos()} uses, so it stays correct
     * for boards mounted at any angle, and uses point-differencing (rather
     * than rotating a bare direction vector) so it doesn't need to know
     * whether the rotation helper is a "centered" rotation or not.
     */
    private static Anchor computeAnchor(@NotNull PlacedComponent placed) {
        var footprint = placed.footprint(); // already rotated for this placement's ORIENTATION
        float w = footprint.getWidth();
        float h = footprint.getHeight();

        float dx, dy;
        switch (placed.get(ORIENTATION)) {
            case RIGHT -> { dx = 1; dy = 0; }
            case DOWN -> { dx = 0; dy = 1; }
            case LEFT -> { dx = -1; dy = 0; }
            case UP -> { dx = 0; dy = -1; }
            default -> throw new IllegalStateException("Unknown orientation: " + placed.get(ORIENTATION));
        }

        float edgeX = placed.x + w / 2f + dx * (w / 2f);
        float edgeY = placed.y + h / 2f + dy * (h / 2f);

        var state = placed.getWorld().getBlockState(placed.getPos());
        int angleX = CircuitBoardBlock.getAngleX(state);
        int angleY = CircuitBoardBlock.getAngleY(state);
        BlockPos boardPos = placed.getPos();

        Vec3 base = transformLocal(edgeX / 16f, 2 / 16f, edgeY / 16f, angleX, angleY, boardPos);
        Vec3 facingProbe = transformLocal(edgeX / 16f + dx * 0.01f, 2 / 16f, edgeY / 16f + dy * 0.01f, angleX, angleY, boardPos);
        Vec3 upProbe = transformLocal(edgeX / 16f, 2 / 16f + 0.01f, edgeY / 16f, angleX, angleY, boardPos);

        return new Anchor(base, facingProbe.subtract(base).normalize(), upProbe.subtract(base).normalize());
    }

    private static Vec3 transformLocal(float x, float y, float z, int angleX, int angleY, BlockPos boardPos) {
        Vec3 pos = new Vec3(x, y, z);
        pos = VecHelper.rotateCentered(pos, angleX, Direction.Axis.X);
        pos = VecHelper.rotateCentered(pos, angleY, Direction.Axis.Y);
        return pos.add(boardPos.getX(), boardPos.getY(), boardPos.getZ());
    }

    @Override
    public void render(@NotNull CircuitBoardBlockEntity be, @NotNull PlacedComponent placed, float partialTicks,
                        @NotNull PoseStack ms, @NotNull MultiBufferSource bufferSource, int light, int overlay) {
        if (!placed.get(LINKED))
            return;

        PlacedComponent partner = findPartner(placed);
        if (partner == null)
            return;

        // Both connectors are linked to each other and would each try to draw
        // the same cable; only the lexicographically-lower UUID side draws it,
        // once, so it isn't drawn twice (and doesn't flicker/z-fight).
        if (placed.getUUID().compareTo(partner.getUUID()) > 0)
            return;

        Anchor a = computeAnchor(placed);
        Anchor b = computeAnchor(partner);

        // `ms` currently carries CircuitBoardRenderer's per-component transform
        // (center + board rotation + translate to THIS component's local pad
        // origin). The partner may be on a different board entirely, so the
        // cable needs to be drawn in absolute world space instead. Popping
        // once removes exactly that per-component push, leaving `ms` at the
        // same "world, relative to be.getBlockPos()" frame renderSafe() itself
        // received. We then push straight back so the pop CircuitBoardRenderer
        // still performs afterwards for its own loop stays balanced.
        ms.popPose();
        ms.pushPose();
        Matrix4f matrix = ms.last().pose();

        Vec3 origin = Vec3.atLowerCornerOf(be.getBlockPos());
        Vec3 aPoint = a.point().subtract(origin);
        Vec3 bPoint = b.point().subtract(origin);
        Vec3 aDeparture = aPoint.add(a.facing().scale(END_LENGTH));
        Vec3 bDeparture = bPoint.add(b.facing().scale(END_LENGTH));

        VertexConsumer endBuffer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(CABLE_END_TEXTURE));
        drawSegment(matrix, endBuffer, aPoint, aDeparture, a.up(), CABLE_WIDTH, 0f, 1f, light, overlay);
        drawSegment(matrix, endBuffer, bPoint, bDeparture, b.up(), CABLE_WIDTH, 0f, 1f, light, overlay);

        VertexConsumer middleBuffer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(CABLE_MIDDLE_TEXTURE));
        drawMiddleRun(matrix, middleBuffer, aDeparture, bDeparture, a.up(), light, overlay);
    }

    /** Tiles {@link #MIDDLE_LENGTH}-long quads of the repeating middle texture between the two departure points. */
    private static void drawMiddleRun(Matrix4f matrix, VertexConsumer vc, Vec3 start, Vec3 end, Vec3 up, int light, int overlay) {
        Vec3 delta = end.subtract(start);
        double totalLength = delta.length();
        if (totalLength < 1.0e-4)
            return;
        Vec3 dir = delta.scale(1.0 / totalLength);

        int fullSegments = (int) Math.floor(totalLength / MIDDLE_LENGTH);
        double remainder = totalLength - fullSegments * MIDDLE_LENGTH;

        Vec3 cursor = start;
        for (int i = 0; i < fullSegments; i++) {
            Vec3 next = cursor.add(dir.scale(MIDDLE_LENGTH));
            drawSegment(matrix, vc, cursor, next, up, CABLE_WIDTH, 0f, 1f, light, overlay);
            cursor = next;
        }
        if (remainder > 1.0e-4) {
            // Partial leftover tile: clip its v range instead of stretching
            // the texture, so the repeat spacing along the run stays uniform.
            float vMax = (float) (remainder / MIDDLE_LENGTH);
            Vec3 next = cursor.add(dir.scale(remainder));
            drawSegment(matrix, vc, cursor, next, up, CABLE_WIDTH, 0f, vMax, light, overlay);
        }
    }

    /** Emits one flat, double-sided quad from {@code start} to {@code end}, {@code width} wide, facing {@code up}. */
    private static void drawSegment(Matrix4f matrix, VertexConsumer vc, Vec3 start, Vec3 end, Vec3 up,
                                     float width, float vMin, float vMax, int light, int overlay) {
        Vec3 length = end.subtract(start);
        if (length.lengthSqr() < 1.0e-8)
            return;
        Vec3 right = length.cross(up).normalize().scale(width / 2f);

        Vec3 p1 = start.subtract(right);
        Vec3 p2 = start.add(right);
        Vec3 p3 = end.add(right);
        Vec3 p4 = end.subtract(right);

        float nx = (float) up.x, ny = (float) up.y, nz = (float) up.z;

        vc.addVertex(matrix, (float) p1.x, (float) p1.y, (float) p1.z)
                .setColor(255, 255, 255, 255).setUv(0f, vMin).setOverlay(overlay).setLight(light).setNormal(nx, ny, nz);
        vc.addVertex(matrix, (float) p2.x, (float) p2.y, (float) p2.z)
                .setColor(255, 255, 255, 255).setUv(1f, vMin).setOverlay(overlay).setLight(light).setNormal(nx, ny, nz);
        vc.addVertex(matrix, (float) p3.x, (float) p3.y, (float) p3.z)
                .setColor(255, 255, 255, 255).setUv(1f, vMax).setOverlay(overlay).setLight(light).setNormal(nx, ny, nz);
        vc.addVertex(matrix, (float) p4.x, (float) p4.y, (float) p4.z)
                .setColor(255, 255, 255, 255).setUv(0f, vMax).setOverlay(overlay).setLight(light).setNormal(nx, ny, nz);
    }

    private record PendingEnd(BlockPos boardPos, UUID componentId) {
    }
}
