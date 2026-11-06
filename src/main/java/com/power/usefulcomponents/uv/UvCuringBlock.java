package com.power.usefulcomponents.uv;

import com.power.usefulcomponents.config.UsefulComponentsConfig;
import com.power.usefulcomponents.registry.ModBlockEntities;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.patryk3211.powergrid.electricity.deviceconnector.IAcceptConnector;
import org.patryk3211.powergrid.electricity.info.IHaveElectricProperties;
import org.patryk3211.powergrid.kinetics.base.ElectricKineticBlock;

import java.util.List;

/**
 * UV curing machine: a press-style processor that needs BOTH rotation (through
 * its shaft, axis = facing axis, like Create's press) and electrical power.
 * Power comes in the same way as for Power Grid's basin heater: a power cord
 * or a device connector placed on one of its free sides (not the bottom and
 * not the shaft ends).
 */
public class UvCuringBlock extends ElectricKineticBlock
        implements IBE<UvCuringBlockEntity>, IAcceptConnector, IHaveElectricProperties {

    public static final DirectionProperty HORIZONTAL_FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final VoxelShape SHAPE = box(0, 2, 0, 16, 16, 16);

    public UvCuringBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(HORIZONTAL_FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(HORIZONTAL_FACING);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Prefer lining the shaft up with a neighbouring shaft, like Create's press.
        Direction preferred = null;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos neighbourPos = context.getClickedPos().relative(side);
            BlockState neighbour = context.getLevel().getBlockState(neighbourPos);
            if (neighbour.getBlock() instanceof IRotate rotate
                    && rotate.hasShaftTowards(context.getLevel(), neighbourPos, neighbour, side.getOpposite())) {
                if (preferred != null && preferred.getAxis() != side.getAxis()) {
                    preferred = null;
                    break;
                }
                preferred = side;
            }
        }
        Direction facing = preferred != null ? preferred : context.getHorizontalDirection();
        return defaultBlockState().setValue(HORIZONTAL_FACING, facing);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(HORIZONTAL_FACING).getAxis();
    }

    @Override
    public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
        return face.getAxis() == state.getValue(HORIZONTAL_FACING).getAxis();
    }

    /** Power cords / device connectors: anywhere except the underside (the lamp) and the two shaft ends. */
    @Override
    public boolean canConnect(LevelReader level, BlockPos pos, BlockState state, Direction side) {
        return side != Direction.DOWN && side.getAxis() != state.getValue(HORIZONTAL_FACING).getAxis();
    }

    @Override
    public Class<UvCuringBlockEntity> getBlockEntityClass() {
        return UvCuringBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends UvCuringBlockEntity> getBlockEntityType() {
        return ModBlockEntities.UV_CURING.get();
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(HORIZONTAL_FACING, rotation.rotate(state.getValue(HORIZONTAL_FACING)));
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(HORIZONTAL_FACING)));
    }

    @Override
    public void appendProperties(ItemStack stack, Player player, List<Component> tooltip) {
        tooltip.add(Component.literal("Heat level 1: " + UvCuringBlockEntity.heatPower(1) + " W")
                .withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.literal("Heat level 2: " + UvCuringBlockEntity.heatPower(2) + " W")
                .withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.literal("Heat level 3: " + UvCuringBlockEntity.heatPower(3) + " W")
                .withStyle(ChatFormatting.RED));
        tooltip.add(Component.literal("Minimum speed: " + UsefulComponentsConfig.UV_MIN_SPEED.get() + " RPM")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Lamp resistance: " + UsefulComponentsConfig.UV_COIL_RESISTANCE.get() + " ohm")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Explodes at " + UsefulComponentsConfig.UV_BURNOUT_VOLTAGE.get() + " V")
                .withStyle(ChatFormatting.DARK_RED));
    }
}
