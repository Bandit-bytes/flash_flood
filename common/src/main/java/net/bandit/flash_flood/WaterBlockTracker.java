package net.bandit.flash_flood;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;

public final class WaterBlockTracker {
    private static final WaterBlockTracker INSTANCE = new WaterBlockTracker();
    private final Set<BlockPos> waterBlocks = new HashSet<>();

    private WaterBlockTracker() {}

    public static WaterBlockTracker getInstance() {
        return INSTANCE;
    }

    public void addWaterBlock(ServerLevel world, BlockPos pos) {
        BlockState state = Blocks.WATER.defaultBlockState();
        world.setBlock(pos, state, 3);
        waterBlocks.add(pos);
    }

    public void removeAllWaterBlocks(ServerLevel world) {
        for (BlockPos pos : waterBlocks) {
            world.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
        waterBlocks.clear();
    }
}
