package net.bandit.flash_flood;

import dev.architectury.event.events.common.TickEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public final class FlashFloodMod {
    public static final String MOD_ID = "flash_flood";
    private static final Random RANDOM = new Random();
    private static final Set<BlockPos> waterBlocks = new HashSet<>();

    private static SimpleConfig config;

    public static void init() {
        config = SimpleConfig.load();
        config.save();

        // Register the event listener
        TickEvent.SERVER_POST.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                handlePlayerTick(player);
            }
        });
    }

    private static void handlePlayerTick(ServerPlayer player) {
        Level world = player.level();
        BlockPos playerPos = player.blockPosition();

        double waterCreationChance = config.waterCreationChance;
        int waterCreationRadius = config.waterCreationRadius;

        if (world.isRaining() && world.canSeeSky(playerPos)) {
            if (RANDOM.nextDouble() < waterCreationChance) {
                int radius = waterCreationRadius;
                BlockPos waterPos = playerPos.offset(
                        RANDOM.nextInt(radius * 2) - radius,
                        RANDOM.nextInt(5) - 2,
                        RANDOM.nextInt(radius * 2) - radius
                );

                if (world.getBlockState(waterPos).isAir() && world.getBlockState(waterPos.below()).isSolidRender(world, waterPos.below()) && !hasTileEntity(world, waterPos)) {
                    world.setBlockAndUpdate(waterPos, Blocks.WATER.defaultBlockState());
                    waterBlocks.add(waterPos); // Track the water block position
                }
            }
        } else if (!world.isRaining() && !waterBlocks.isEmpty() && config.removeWaterAfterRain) {
            // Remove the tracked water blocks when the rain stops if the option is enabled
            removeWaterBlocks(world);
        }
    }

    private static boolean hasTileEntity(Level world, BlockPos pos) {
        BlockEntity blockEntity = world.getBlockEntity(pos);
        return blockEntity != null;
    }

    private static void removeWaterBlocks(Level world) {
        for (BlockPos waterPos : waterBlocks) {
            if (world.getBlockState(waterPos).getBlock() == Blocks.WATER) {
                world.setBlockAndUpdate(waterPos, Blocks.AIR.defaultBlockState());
            }
        }
        waterBlocks.clear(); // Clear the set after removal
    }

    public static void initClient() {
        // Write common init code here.
    }
}
