package com.power.usefulcomponents.registry;

import com.power.usefulcomponents.UsefulComponents;
import com.power.usefulcomponents.uv.UvCuringBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, UsefulComponents.MODID);

    /*public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<UvCuringBlockEntity>> UV_CURING =
            BLOCK_ENTITIES.register("uv_curing_machine", () ->
                    BlockEntityType.Builder.of(UvCuringBlockEntity::new, ModBlocks.UV_CURING.get()).build(null));*/
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<UvCuringBlockEntity>> UV_CURING =
        BLOCK_ENTITIES.register("uv_curing_machine", () ->
                BlockEntityType.Builder.of(
                        (pos, state) -> new UvCuringBlockEntity(ModBlockEntities.UV_CURING.get(), pos, state),
                        ModBlocks.UV_CURING.get()).build(null));

    private ModBlockEntities() {
    }
}
