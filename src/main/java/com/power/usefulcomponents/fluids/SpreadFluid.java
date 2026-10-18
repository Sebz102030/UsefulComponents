package com.power.usefulcomponents.fluids;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;

/**
 * Source / flowing fluids that stop spreading after a chosen number of blocks.
 *
 * A source has level 8 and every block away from it loses 1 level, so water-like fluid reaches 7 blocks
 * (not counting the source). Spread length L (1-7) is done by not placing flowing blocks of level
 * 7 - L or lower; a falling stream keeps working as usual.
 */
public final class SpreadFluid {
    /** Longest possible reach: what water does (8 levels, 1 lost per block). */
    public static final int MAX_LENGTH = 7;

    private SpreadFluid() {
    }

    /** True if this flowing block lies beyond the spread length and must not be placed. */
    static boolean beyondReach(int lowestLevelCut, FluidState state) {
        return !state.isSource() && !state.getValue(FlowingFluid.FALLING) && state.getAmount() <= lowestLevelCut;
    }

    public static class Source extends BaseFlowingFluid.Source {
        private final int lowestLevelCut;

        public Source(BaseFlowingFluid.Properties properties, int lengthFromSource) {
            super(properties);
            this.lowestLevelCut = MAX_LENGTH - lengthFromSource;
        }

        @Override
        protected void spreadTo(LevelAccessor level, BlockPos pos, BlockState blockState, Direction direction,
                                FluidState fluidState) {
            if (!beyondReach(lowestLevelCut, fluidState))
                super.spreadTo(level, pos, blockState, direction, fluidState);
        }
    }

    public static class Flowing extends BaseFlowingFluid.Flowing {
        private final int lowestLevelCut;

        public Flowing(BaseFlowingFluid.Properties properties, int lengthFromSource) {
            super(properties);
            this.lowestLevelCut = MAX_LENGTH - lengthFromSource;
        }

        @Override
        protected void spreadTo(LevelAccessor level, BlockPos pos, BlockState blockState, Direction direction,
                                FluidState fluidState) {
            if (!beyondReach(lowestLevelCut, fluidState))
                super.spreadTo(level, pos, blockState, direction, fluidState);
        }
    }
}
