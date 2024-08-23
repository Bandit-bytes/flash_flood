package net.bandit.flash_flood;

import dev.architectury.event.events.common.TickEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Random;

public final class FlashFloodMod {
    public static final String MOD_ID = "flash_flood";
    private static final Random RANDOM = new Random();

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
        ServerLevel world = player.serverLevel(); // Get the ServerLevel from the player
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
                    WaterBlockTracker.getInstance().addWaterBlock(world, waterPos); // Use the tracker to add water blocks
                }
            }
        } else if (!world.isRaining() && config.removeWaterAfterRain) {
            WaterBlockTracker.getInstance().removeAllWaterBlocks(world); // Remove water blocks when rain stops
        }
    }

    private static boolean hasTileEntity(Level world, BlockPos pos) {
        BlockEntity blockEntity = world.getBlockEntity(pos);
        return blockEntity != null;
    }

    public static void initClient() {
        // Write common init code here.
    }
}
