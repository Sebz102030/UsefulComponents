package com.power.usefulcomponents.components;

import com.google.common.collect.ImmutableCollection;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.power.usefulcomponents.UsefulComponents;
import com.power.usefulcomponents.registry.ModItems;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
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

import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class RibbonConnectorComponent extends OrientableComponent
        implements IComponentGoggleInformation, IInteractableComponent, IRenderedComponent {

    private record CableConfig(ResourceLocation endTexture, ResourceLocation middleTexture, float thickness) {
    }

    private static final ResourceLocation CABLE_CONFIG_LOCATION =
            ResourceLocation.fromNamespaceAndPath(UsefulComponents.MODID, "ribbon_cable.json");

    @Nullable
    private static CableConfig cableConfig;

    private static CableConfig cableConfig() {
        if (cableConfig == null)
            cableConfig = loadCableConfig();
        return cableConfig;
    }

    private static CableConfig loadCableConfig() {
        try {
            Resource resource = Minecraft.getInstance().getResourceManager().getResourceOrThrow(CABLE_CONFIG_LOCATION);
            try (Reader reader = resource.openAsReader()) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                ResourceLocation end = ResourceLocation.parse(GsonHelper.getAsString(json, "end_texture"));
                ResourceLocation middle = ResourceLocation.parse(GsonHelper.getAsString(json, "middle_texture"));
                float thickness = GsonHelper.getAsFloat(json, "thickness");
                return new CableConfig(end, middle, thickness);
            }
        } catch (Exception e) {
            UsefulComponents.LOGGER.warn("Could not load {} - falling back to default ribbon cable textures/thickness",
                    CABLE_CONFIG_LOCATION, e);
            return new CableConfig(
                    ResourceLocation.fromNamespaceAndPath(UsefulComponents.MODID, "block/component/ribbon_cable_end"),
                    ResourceLocation.fromNamespaceAndPath(UsefulComponents.MODID, "block/component/ribbon_cable_middle"),
                    0.25f);
        }
    }

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
        builder.terminalNode(PIN_A);
        builder.terminalNode(PIN_B);
        builder.terminalNode(PIN_C);
        builder.terminalNode(PIN_D);
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

    private record Anchor(Vec3 point, Vec3 facing, Vec3 up) {
    }

    private Anchor computeAnchor(@NotNull PlacedComponent placed) {
        var footprint = placed.footprint();
        float w = footprint.getWidth();
        float h = footprint.getHeight();

        var state = placed.getWorld().getBlockState(placed.getPos());
        int angleX = CircuitBoardBlock.getAngleX(state);
        int angleY = CircuitBoardBlock.getAngleY(state);
        BlockPos boardPos = placed.getPos();

        Orientation placedOrientation = placed.component instanceof RibbonConnectorComponent rcc
                ? rcc.orientation : Orientation.SIDE;

        float heightOffset = 3 / 16f;

        if (placedOrientation == Orientation.FRONT) {
            float centerX = placed.x + w / 2f;
            float centerY = placed.y + h / 2f;

            Vec3 base = transformLocal(centerX / 16f, heightOffset, centerY / 16f, angleX, angleY, boardPos);
            Vec3 facingProbe = transformLocal(centerX / 16f, heightOffset + 0.01f, centerY / 16f, angleX, angleY, boardPos);
            Vec3 upProbe = transformLocal(centerX / 16f + 0.01f, heightOffset, centerY / 16f, angleX, angleY, boardPos);

            return new Anchor(base, facingProbe.subtract(base).normalize(), upProbe.subtract(base).normalize());
        }

        float dx, dy;
        switch (placed.get(ORIENTATION)) {
            case RIGHT -> { dx = 1; dy = 0; }
            case DOWN ->  { dx = 0; dy = 1; }
            case LEFT ->  { dx = -1; dy = 0; }
            case UP ->    { dx = 0; dy = -1; }
            default -> throw new IllegalStateException("Unknown orientation: " + placed.get(ORIENTATION));
        }

        float edgeX = placed.x + w / 2f + dx * (w / 2f);
        float edgeY = placed.y + h / 2f + dy * (h / 2f);

        Vec3 base = transformLocal(edgeX / 16f, heightOffset, edgeY / 16f, angleX, angleY, boardPos);
        Vec3 facingProbe = transformLocal(edgeX / 16f + dx * 0.01f, heightOffset, edgeY / 16f + dy * 0.01f, angleX, angleY, boardPos);
        Vec3 upProbe = transformLocal(edgeX / 16f, heightOffset + 0.01f, edgeY / 16f, angleX, angleY, boardPos);

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

        if (placed.getUUID().compareTo(partner.getUUID()) > 0)
            return;

        Anchor a = computeAnchor(placed);
        Anchor b = computeAnchor(partner);
        CableConfig cfg = cableConfig();
        float halfThickness = cfg.thickness() / 16f / 2f;

        ms.popPose();
        ms.pushPose();
        Matrix4f matrix = ms.last().pose();

        Vec3 origin = Vec3.atLowerCornerOf(be.getBlockPos());
        Vec3 p0 = a.point().subtract(origin);
        Vec3 p3 = b.point().subtract(origin);
        Vec3 p1 = p0.add(a.facing().scale(END_LENGTH));
        Vec3 p2 = p3.add(b.facing().scale(END_LENGTH));

        Vec3 midDirVec = p2.subtract(p1);
        Vec3 midDir = midDirVec.lengthSqr() < 1.0e-8 ? a.facing() : midDirVec.normalize();
        double midLength = midDirVec.length();

        Vec3 rightA = perpendicular(a.facing(), a.up());
        Vec3 rightB = perpendicular(b.facing(), b.up());

        Vec3 rightMid = perpendicular(midDir, a.up());
        if (rightMid.lengthSqr() < 1.0e-4) {
            rightMid = rightA;
        }

        var atlas = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS);
        TextureAtlasSprite endSprite = atlas.apply(cfg.endTexture());
        TextureAtlasSprite middleSprite = atlas.apply(cfg.middleTexture());

        VertexConsumer buffer = bufferSource.getBuffer(RenderType.entityCutout(InventoryMenu.BLOCK_ATLAS));

        // Draw Cap Ends
        drawRibbonSegment(matrix, buffer, endSprite, p0, rightA, a.up(), p1, rightA, a.up(), 0f, 1f, halfThickness, light, overlay);
        drawRibbonSegment(matrix, buffer, endSprite, p3, rightB, b.up(), p2, rightB, b.up(), 0f, 1f, halfThickness, light, overlay);

        // Draw Middle Tiles
        int fullTiles = (int) Math.floor(midLength / MIDDLE_LENGTH);
        double remainder = midLength - fullTiles * MIDDLE_LENGTH;
        Vec3 cursor = p1;
        for (int i = 0; i < fullTiles; i++) {
            Vec3 next = cursor.add(midDir.scale(MIDDLE_LENGTH));
            drawRibbonSegment(matrix, buffer, middleSprite, cursor, rightMid, a.up(), next, rightMid, a.up(), 0f, 1f, halfThickness, light, overlay);
            cursor = next;
        }
        if (remainder > 1.0e-4) {
            Vec3 next = cursor.add(midDir.scale(remainder));
            float vMax = (float) (remainder / MIDDLE_LENGTH);
            drawRibbonSegment(matrix, buffer, middleSprite, cursor, rightMid, a.up(), next, rightMid, a.up(), 0f, vMax, halfThickness, light, overlay);
        }

        // Draw Joint Patches with z-fighting mitigation
        drawJointPatch(matrix, buffer, middleSprite, p1, rightA, a.up(), rightMid, a.up(), halfThickness, light, overlay);
        drawJointPatch(matrix, buffer, middleSprite, p2, rightMid, a.up(), rightB, b.up(), halfThickness, light, overlay);
    }

    private static Vec3 perpendicular(Vec3 direction, Vec3 up) {
        Vec3 cross = direction.cross(up);
        if (cross.lengthSqr() < 1.0e-6) {
            return new Vec3(0, 0, 0);
        }
        return cross.normalize().scale(CABLE_WIDTH / 2f);
    }

    private static void drawRibbonSegment(Matrix4f matrix, VertexConsumer vc, TextureAtlasSprite sprite,
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

        quad(matrix, vc, sprite, topStartL, topStartR, topEndR, topEndL, normalTop,
                0f, vStart, 1f, vStart, 1f, vEnd, 0f, vEnd, light, overlay);
        quad(matrix, vc, sprite, botStartR, botStartL, botEndL, botEndR, normalBottom,
                1f, vStart, 0f, vStart, 0f, vEnd, 1f, vEnd, light, overlay);
        quad(matrix, vc, sprite, botStartL, topStartL, topEndL, botEndL, leftNormal,
                0f, vStart, 0f, vStart, 0f, vEnd, 0f, vEnd, light, overlay);
        quad(matrix, vc, sprite, topStartR, botStartR, botEndR, topEndR, rightNormal,
                1f, vStart, 1f, vStart, 1f, vEnd, 1f, vEnd, light, overlay);
    }

    private static void drawJointPatch(Matrix4f matrix, VertexConsumer vc, TextureAtlasSprite sprite, Vec3 point,
                                        Vec3 right1, Vec3 up1, Vec3 right2, Vec3 up2,
                                        float halfThickness, int light, int overlay) {
        Vec3 up = up1.add(up2).normalize();
        Vec3 topOffset = up.scale(halfThickness + 0.001f);
        Vec3 botOffset = up.scale(-(halfThickness + 0.001f));

        Vec3 topA1 = point.subtract(right1).add(topOffset);
        Vec3 topA2 = point.add(right1).add(topOffset);
        Vec3 topB1 = point.subtract(right2).add(topOffset);
        Vec3 topB2 = point.add(right2).add(topOffset);
        quad(matrix, vc, sprite, topA1, topA2, topB2, topB1, up,
                0f, 0.5f, 1f, 0.5f, 1f, 0.5f, 0f, 0.5f, light, overlay);

        Vec3 botA1 = point.subtract(right1).add(botOffset);
        Vec3 botA2 = point.add(right1).add(botOffset);
        Vec3 botB1 = point.subtract(right2).add(botOffset);
        Vec3 botB2 = point.add(right2).add(botOffset);
        quad(matrix, vc, sprite, botA2, botA1, botB1, botB2, up.scale(-1),
                1f, 0.5f, 0f, 0.5f, 0f, 0.5f, 1f, 0.5f, light, overlay);
    }

    private static void quad(Matrix4f matrix, VertexConsumer vc, TextureAtlasSprite sprite,
                              Vec3 p1, Vec3 p2, Vec3 p3, Vec3 p4, Vec3 normal,
                              float u1, float v1, float u2, float v2, float u3, float v3, float u4, float v4,
                              int light, int overlay) {
        float nx = (float) normal.x, ny = (float) normal.y, nz = (float) normal.z;
        vertex(matrix, vc, p1, sprite.getU(u1), sprite.getV(v1), nx, ny, nz, light, overlay);
        vertex(matrix, vc, p2, sprite.getU(u2), sprite.getV(v2), nx, ny, nz, light, overlay);
        vertex(matrix, vc, p3, sprite.getU(u3), sprite.getV(v3), nx, ny, nz, light, overlay);
        vertex(matrix, vc, p4, sprite.getU(u4), sprite.getV(v4), nx, ny, nz, light, overlay);
    }

    private static void vertex(Matrix4f matrix, VertexConsumer vc, Vec3 p, float u, float v,
                                float nx, float ny, float nz, int light, int overlay) {
        vc.addVertex(matrix, (float) p.x, (float) p.y, (float) p.z)
                .setColor(255, 255, 255, 255).setUv(u, v).setOverlay(overlay).setLight(light).setNormal(nx, ny, nz);
    }

    private record PendingEnd(BlockPos boardPos, UUID componentId) {
    }
}