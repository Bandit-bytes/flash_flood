package net.bandit.flash_flood;

import dev.architectury.event.events.common.TickEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Random;

public final class FlashFloodMod {
    public static final String MOD_ID = "flash_flood";
    private static final Random RANDOM = new Random();

    private static SimpleConfig config;

    public static void init() {
        config = SimpleConfig.load();
        config.save();

        TickEvent.SERVER_POST.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                handlePlayerTick(player);
            }
        });
    }
    private static void handlePlayerTick(ServerPlayer player) {
        ServerLevel world = player.serverLevel();
        BlockPos playerPos = player.blockPosition();

        // Check if the current biome supports precipitation
        if (!world.getBiome(playerPos).value().hasPrecipitation()) {
            return;
        }
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
                    WaterBlockTracker.getInstance().addWaterBlock(world, waterPos);
                    spreadWater(world, waterPos);
                }
            }
        } else if (!world.isRaining() && config.removeWaterAfterRain) {
            WaterBlockTracker.getInstance().removeAllWaterBlocks(world);
        }
    }
    private static void spreadWater(ServerLevel world, BlockPos pos) {
        if (RANDOM.nextDouble() < config.waterSpreadChance) {
            for (BlockPos offset : BlockPos.betweenClosed(pos.offset(-config.maxWaterSpreadRadius, 0, -config.maxWaterSpreadRadius), pos.offset(config.maxWaterSpreadRadius, 0, config.maxWaterSpreadRadius))) {
                BlockPos spreadPos = pos.offset(offset);

                if (world.getBlockState(spreadPos).isAir() && world.getBlockState(spreadPos.below()).isSolidRender(world, spreadPos.below()) && !hasTileEntity(world, spreadPos)) {
                    world.setBlock(spreadPos, Blocks.WATER.defaultBlockState(), 3); // Place water on the ground
                    WaterBlockTracker.getInstance().addWaterBlock(world, spreadPos);
                }
            }
        }
        BlockPos abovePos = pos.above();
        if (RANDOM.nextDouble() < config.waterSpreadChance &&
                abovePos.getY() <= (pos.getY() + config.maxWaterHeight) &&
                world.getBlockState(abovePos).isAir() && !hasTileEntity(world, abovePos)) {
            world.scheduleTick(abovePos, Blocks.WATER, 20);
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
