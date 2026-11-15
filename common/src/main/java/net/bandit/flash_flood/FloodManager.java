package net.bandit.flash_flood;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayDeque;

/**
 * Drives floods for each dimension.
 *
 * <p>While a flood is active, random columns around each player are checked. A spot can flood
 * if it is open to the rain and touches water that belongs to a real water body (a lake, river or
 * ocean, or flood water that grew out of one), and is no more than {@code rise} blocks above that
 * body's natural level. The allowed rise grows over the course of the flood, so water spreads
 * outward over low ground first and then climbs the banks.
 *
 * <p>When the flood ends, flood water drains a few blocks per tick, highest and outermost first,
 * and the original grass or flowers are put back.
 */
public final class FloodManager {
    private static final int NO_WATER = Integer.MIN_VALUE;
    /** How long to wait before re-checking flood water left in unloaded chunks. */
    private static final int DRAIN_RETRY_TICKS = 100;

    private FloodManager() {}

    public static void tickLevel(ServerLevel level) {
        if (!canFlood(level)) {
            return;
        }
        SimpleConfig config = FlashFloodMod.config();
        FloodData data = FloodData.get(level);

        // Use the weather *state*, not the visual rain level, which ramps up and down over several seconds.
        boolean raining = level.getLevelData().isRaining();
        if (raining && !data.wasRaining() && !data.isFloodActive()
                && level.getRandom().nextDouble() < config.floodChance) {
            startFlood(level, data);
        }
        data.setWasRaining(raining);

        if (data.isFloodActive() && !raining) {
            stopFlood(level, data);
        }

        if (data.isFloodActive()) {
            data.tickFlood();
            spread(level, data, config);
        } else if (!data.isEmpty()) {
            drain(level, data, config);
        }
    }

    /** Only dimensions with a sky and weather (the Overworld, and similar modded dimensions). */
    public static boolean canFlood(ServerLevel level) {
        return level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling();
    }

    public static void startFlood(ServerLevel level, FloodData data) {
        data.startFlood();
        announce(level, "The waters are rising...");
    }

    public static void stopFlood(ServerLevel level, FloodData data) {
        data.stopFlood();
        announce(level, "The flood waters begin to recede.");
    }

    /** How many blocks above natural level water may currently reach. */
    public static int currentRise(FloodData data) {
        SimpleConfig config = FlashFloodMod.config();
        int interval = config.riseIntervalSeconds * 20;
        return Math.min(config.maxRise, 1 + data.getFloodTicks() / interval);
    }

    // --- rising --------------------------------------------------------------------------------

    private static void spread(ServerLevel level, FloodData data, SimpleConfig config) {
        if (data.size() >= config.maxFloodBlocks) {
            return;
        }
        int rise = currentRise(data);
        int radius = config.floodRadius;
        int budget = config.maxBlocksPerTick;
        RandomSource random = level.getRandom();

        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            for (int i = 0; i < config.spreadAttemptsPerPlayer; i++) {
                int x = player.getBlockX() + random.nextInt(radius * 2 + 1) - radius;
                int z = player.getBlockZ() + random.nextInt(radius * 2 + 1) - radius;
                if (tryFloodColumn(level, data, config, x, z, rise)) {
                    if (--budget <= 0 || data.size() >= config.maxFloodBlocks) {
                        return;
                    }
                }
            }
        }
    }

    private static boolean tryFloodColumn(ServerLevel level, FloodData data, SimpleConfig config, int x, int z, int rise) {
        // Never touch (or load) chunks that aren't already loaded.
        if (!level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))) {
            return false;
        }

        // First free block above the ground or water surface. Leaves are ignored so floods reach under trees,
        // but roofs are not, so water never appears inside buildings or caves.
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos top = new BlockPos(x, surfaceY - 1, z);
        // Flowing water counts as part of the surface; let the flood turn it into still water.
        BlockPos target = isFlowingWater(level.getBlockState(top)) ? top : top.above();
        return tryFlood(level, data, config, target, rise);
    }

    private static boolean tryFlood(ServerLevel level, FloodData data, SimpleConfig config, BlockPos pos, int rise) {
        BlockState original = replaceableOriginal(level.getBlockState(pos), config);
        if (original == null) {
            return false;
        }
        if (level.getBiome(pos).value().getPrecipitationAt(pos) != Biome.Precipitation.RAIN) {
            return false; // deserts, snowy biomes, etc.
        }

        int bestBase = NO_WATER;

        // Rising on top of existing water.
        BlockPos below = pos.below();
        BlockState belowState = level.getBlockState(below);
        int belowBase = waterBase(level, data, config, below, belowState);
        if (belowBase != NO_WATER) {
            // Only raise natural water along its shoreline; capping a whole ocean surface would add
            // thousands of invisible blocks. Flood water can always deepen.
            if (data.getEntry(below) != null || isShore(level, pos)) {
                bestBase = belowBase;
            }
        } else if (!belowState.isFaceSturdy(level, below, Direction.UP)) {
            return false; // no solid ground: would pour off a ledge or float
        }

        // Spreading sideways from neighbouring water.
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = pos.relative(dir);
            if (!level.isLoaded(neighbor)) {
                continue;
            }
            int base = waterBase(level, data, config, neighbor, level.getBlockState(neighbor));
            if (base > bestBase) {
                bestBase = base;
            }
        }

        if (bestBase == NO_WATER || pos.getY() > bestBase + rise) {
            return false;
        }

        if (!level.setBlock(pos, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL)) {
            return false; // e.g. at the build height limit
        }
        data.add(pos, original, bestBase);
        return true;
    }

    /**
     * The natural water level this block belongs to, or {@link #NO_WATER} if it isn't still water
     * that a flood can grow from.
     */
    private static int waterBase(ServerLevel level, FloodData data, SimpleConfig config, BlockPos pos, BlockState state) {
        FluidState fluid = state.getFluidState();
        if (!fluid.is(FluidTags.WATER) || !fluid.isSource()) {
            return NO_WATER;
        }
        FloodData.Entry entry = data.getEntry(pos);
        if (entry != null) {
            return entry.baseY();
        }
        // Natural water. Waterlogged blocks (stairs, slabs, etc.) are usually builds, not water bodies.
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            return NO_WATER;
        }
        if (!config.floodOceans && level.getBiome(pos).is(BiomeTags.IS_OCEAN)) {
            return NO_WATER;
        }
        return pos.getY();
    }

    /** True if the spot borders land, i.e. it's at the edge of a water body rather than over open water. */
    private static boolean isShore(ServerLevel level, BlockPos pos) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = pos.relative(dir);
            if (!level.isLoaded(neighbor)) {
                continue;
            }
            BlockState state = level.getBlockState(neighbor);
            if (state.getFluidState().isEmpty() && !state.getCollisionShape(level, neighbor).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * What to restore once the flood drains, or {@code null} if water must not replace this block.
     */
    private static BlockState replaceableOriginal(BlockState state, SimpleConfig config) {
        if (state.isAir() || isFlowingWater(state) || state.is(BlockTags.FIRE)) {
            return Blocks.AIR.defaultBlockState(); // flowing water and fire don't come back
        }
        if (!config.replacePlants || state.hasBlockEntity() || !state.getFluidState().isEmpty()) {
            return null;
        }
        // Two-block plants would lose their top half and can't be restored cleanly.
        if (state.getBlock() instanceof DoublePlantBlock) {
            return null;
        }
        // Grass, ferns, dead bushes, vines, snow layers and small flowers. Never crops or saplings.
        if (state.is(BlockTags.REPLACEABLE) || state.is(BlockTags.SMALL_FLOWERS)) {
            return state;
        }
        return null;
    }

    private static boolean isFlowingWater(BlockState state) {
        return state.is(Blocks.WATER) && !state.getFluidState().isSource();
    }

    // --- draining ------------------------------------------------------------------------------

    private static void drain(ServerLevel level, FloodData data, SimpleConfig config) {
        ArrayDeque<BlockPos> queue = data.getDrainQueue();
        if (queue == null || queue.isEmpty()) {
            if (data.getDrainCooldown() > 0) {
                data.setDrainCooldown(data.getDrainCooldown() - 1);
                return;
            }
            queue = new ArrayDeque<>(data.drainOrder());
            data.setDrainQueue(queue);
        }

        int budget = config.drainBlocksPerTick;
        while (budget > 0 && !queue.isEmpty()) {
            BlockPos pos = queue.poll();
            FloodData.Entry entry = data.getEntry(pos);
            if (entry == null || !level.isLoaded(pos)) {
                continue; // unloaded water stays tracked and is retried later
            }

            BlockState current = level.getBlockState(pos);
            // Only undo our own water. If a player has since built here, leave their block alone.
            if (current.is(Blocks.WATER) && current.getFluidState().isSource()) {
                BlockState restore = entry.original().canSurvive(level, pos)
                        ? entry.original()
                        : Blocks.AIR.defaultBlockState();
                level.setBlock(pos, restore, Block.UPDATE_ALL);
            }
            data.remove(pos);
            budget--;
        }

        if (queue.isEmpty() && !data.isEmpty()) {
            // Everything left is in unloaded chunks; don't rescan every tick.
            data.setDrainCooldown(DRAIN_RETRY_TICKS);
        }
    }

    // --- messages ------------------------------------------------------------------------------

    private static void announce(ServerLevel level, String message) {
        if (!FlashFloodMod.config().announceFloods) {
            return;
        }
        Component text = Component.literal(message).withStyle(ChatFormatting.AQUA);
        for (ServerPlayer player : level.players()) {
            player.displayClientMessage(text, true);
        }
    }
}
