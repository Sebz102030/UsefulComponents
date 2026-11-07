package com.power.usefulcomponents.uv;

import com.power.usefulcomponents.UsefulComponents;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;

/**
 * Partial models must be created BEFORE the game registers/bakes models,
 * otherwise they render as the missing-model cube. Keeping them in their own
 * class and calling {@link #init()} early guarantees that.
 */
public final class UvPartialModels {

    /** The lamp head that moves up and down. models/block/uv_curing_head.json */
    public static final PartialModel HEAD = block("uv_curing_head");
    /** The spinning shaft input (built along the Z axis). models/block/uv_curing_shaft.json */
    public static final PartialModel SHAFT = block("uv_curing_shaft");

    private static PartialModel block(String name) {
        return PartialModel.of(ResourceLocation.fromNamespaceAndPath(UsefulComponents.MODID, "block/" + name));
    }

    /** Only forces this class to load, which runs the static initializer above. */
    public static void init() {
    }

    private UvPartialModels() {
    }
}
