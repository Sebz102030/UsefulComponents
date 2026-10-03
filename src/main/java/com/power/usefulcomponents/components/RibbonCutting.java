package com.power.usefulcomponents.components;

import com.power.usefulcomponents.UsefulComponents;
import com.power.usefulcomponents.render.RibbonCableGeometry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.jetbrains.annotations.Nullable;
import org.patryk3211.powergrid.collections.ModdedTags;

/**
 * Cuts a ribbon cable with wire cutters wherever along its length the player
 * right-clicks it, not only at the connector ends.
 *
 * It works on the link data directly (see {@link RibbonCables}): the player's
 * look ray is tested against the cable's centre line, and if the cable is hit
 * before any block would be, the click is consumed and both connectors are
 * unlinked. The same test runs on the client (to swallow the click so no
 * block interaction is predicted) and on the server (which actually cuts).
 */
@EventBusSubscriber(modid = UsefulComponents.MODID)
public final class RibbonCutting {

    private RibbonCutting() {
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        RibbonCables.Cable cable = cableUnderCrosshair(event.getEntity(), event.getHand(), event.getLevel());
        if (cable == null)
            return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        cut(event.getEntity(), event.getLevel(), cable);
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        RibbonCables.Cable cable = cableUnderCrosshair(event.getEntity(), event.getHand(), event.getLevel());
        if (cable == null)
            return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        cut(event.getEntity(), event.getLevel(), cable);
    }

    private static void cut(Player player, Level level, RibbonCables.Cable cable) {
        if (level.isClientSide)
            return;
        int items = RibbonConnectorComponent.unlink(cable.first());
        if (items < 0)
            return;
        RibbonConnectorComponent.giveBack(player, items);
        player.displayClientMessage(Component.literal("Ribbon cable cut."), true);
    }

    /** The cable the player is pointing at with wire cutters in hand (nearer than any block), or null. */
    @Nullable
    private static RibbonCables.Cable cableUnderCrosshair(Player player, InteractionHand hand, Level level) {
        if (!player.getItemInHand(hand).is(ModdedTags.Item.WIRE_CUTTERS.tag))
            return null;

        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        double reach = player.blockInteractionRange();

        var cables = level.isClientSide ? RibbonCables.clientCables(level) : RibbonCables.serverCables(level);
        RibbonCables.Cable best = null;
        double bestDistance = -1;
        for (RibbonCables.Cable cable : cables) {
            try {
                double t = RibbonCableGeometry.rayHit(eye, look, reach,
                        RibbonCableGeometry.polyline(cable.first(), cable.second()));
                if (t >= 0 && (bestDistance < 0 || t < bestDistance)) {
                    best = cable;
                    bestDistance = t;
                }
            } catch (RuntimeException ignored) {
                // Board mid-rebake - not clickable this tick.
            }
        }
        if (best == null)
            return null;

        // A block in front of the cable wins (so cutters still work on the
        // connector body itself through the connector's own use()).
        HitResult blockHit = player.pick(reach, 1.0F, false);
        if (blockHit.getType() == HitResult.Type.BLOCK
                && blockHit.getLocation().distanceTo(eye) < bestDistance - 0.02)
            return null;
        return best;
    }
}
