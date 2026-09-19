package com.power.usefulcomponents.components;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

/**
 * Stored as a data component on a held {@code ribbon_cable} item while the
 * player has selected the first connector and is waiting to right-click a
 * second one.
 * <p>
 * Putting this on the ItemStack itself (instead of a server-side
 * {@code Map<UUID, ...>} keyed by player, which is what this mod used to
 * do) is what lets {@link RibbonCableItem#isFoil} make the item glow while
 * a connection is pending - the same trick PowerGrid's own {@code WireItem}
 * uses via its {@code ModdedDataComponents.CONNECTION_DATA}. It also means
 * the pending state survives the player dropping/picking the item back up,
 * and syncs to the client for free like any other item data.
 */
public record RibbonCableConnectionRequest(BlockPos boardPos, UUID componentId) {
    public static final Codec<RibbonCableConnectionRequest> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            BlockPos.CODEC.fieldOf("board_pos").forGetter(RibbonCableConnectionRequest::boardPos),
            UUIDUtil.CODEC.fieldOf("component_id").forGetter(RibbonCableConnectionRequest::componentId)
    ).apply(inst, RibbonCableConnectionRequest::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, RibbonCableConnectionRequest> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RibbonCableConnectionRequest::boardPos,
                    UUIDUtil.STREAM_CODEC, RibbonCableConnectionRequest::componentId,
                    RibbonCableConnectionRequest::new
            );
}
