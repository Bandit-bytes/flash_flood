package net.bandit.flash_flood;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

public final class WaterBlockTracker {
    private static final WaterBlockTracker INSTANCE = new WaterBlockTracker();

    // Thread-safe map to track water blocks per world
    private final Map<ServerLevel, Set<BlockPos>> waterBlocksMap = new ConcurrentHashMap<>();

    private WaterBlockTracker() {}

    public static WaterBlockTracker getInstance() {
        return INSTANCE;
    }

    // Adds a water block to the world and tracks its position
    public void addWaterBlock(ServerLevel world, BlockPos pos) {
        BlockState state = Blocks.WATER.defaultBlockState();
        world.setBlock(pos, state, 3);
        waterBlocksMap.computeIfAbsent(world, k -> new ConcurrentSkipListSet<>()).add(pos);
    }

    // Removes all tracked water blocks from the specified world
    public void removeAllWaterBlocks(ServerLevel world) {
        Set<BlockPos> waterBlocks = waterBlocksMap.get(world);
        if (waterBlocks != null) {
            for (BlockPos pos : waterBlocks) {
                world.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
            waterBlocks.clear(); // Clear the set after removing water blocks
        }
    }

    public void removeWaterBlock(ServerLevel world, BlockPos pos) {
        Set<BlockPos> waterBlocks = waterBlocksMap.get(world);
        if (waterBlocks != null && waterBlocks.remove(pos)) {
            world.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
    }
}
