package com.power.usefulcomponents.fluids;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;

/**
 * World block of a placeable fluid. Temperature 0 is water-like (no damage). Above 0 it hurts entities
 * inside it, scaled from lava: LAVA_TEMPERATURE gives lava's damage (4 per hit) and fire (15 s).
 * Fire resistance protects, as with lava.
 */
public class SpreadFluidBlock extends LiquidBlock {
    /** Temperature value that equals lava. (NeoForge: lava 1300 K minus water 300 K.) */
    public static final int LAVA_TEMPERATURE = 1000;
    private static final float LAVA_DAMAGE = 4.0F;
    private static final float LAVA_FIRE_SECONDS = 15.0F;

    private final float damage;
    private final float fireSeconds;

    public SpreadFluidBlock(FlowingFluid fluid, BlockBehaviour.Properties properties, int temperature) {
        super(fluid, properties);
        this.damage = LAVA_DAMAGE * temperature / LAVA_TEMPERATURE;
        this.fireSeconds = LAVA_FIRE_SECONDS * temperature / LAVA_TEMPERATURE;
    }

    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (damage <= 0 || level.isClientSide || entity.fireImmune())
            return;
        // Only when the entity is really in the fluid, not just in the empty part of the block above it.
        if (entity.getBoundingBox().minY >= pos.getY() + state.getFluidState().getHeight(level, pos))
            return;
        entity.igniteForSeconds(fireSeconds);
        entity.hurt(level.damageSources().lava(), damage);
    }
}
