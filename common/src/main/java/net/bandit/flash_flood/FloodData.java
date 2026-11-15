package net.bandit.flash_flood;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-dimension flood state, saved with the world (each dimension has its own data folder).
 * Tracks whether a flood is active and every water block the flood placed, along with
 * the block it replaced so the land can be restored when the water drains.
 */
public final class FloodData extends SavedData {
    private static final String NAME = FlashFloodMod.MOD_ID;

    // DataFixTypes must be non-null on Fabric for 1.21 (vanilla calls update() on it unconditionally).
    // Our data is unaffected by the LEVEL fixer when the data version is unchanged.
    private static final SavedData.Factory<FloodData> FACTORY =
            new SavedData.Factory<>(FloodData::new, FloodData::load, DataFixTypes.LEVEL);

    /** A flood-placed water block: what was there before, and the natural water level it rose from. */
    public record Entry(BlockState original, int baseY) {}

    /** True while the flood is rising/holding; false while draining or idle. */
    private boolean floodActive;
    /** Ticks since the current flood started; drives how high the water may rise. */
    private int floodTicks;
    /** Rain state last tick, used to detect the moment rain starts. */
    private boolean wasRaining;

    /** Insertion-ordered so draining can remove the most recently flooded (outermost) blocks first. */
    private final LinkedHashMap<BlockPos, Entry> entries = new LinkedHashMap<>();

    // Runtime-only drain bookkeeping (not saved; rebuilt from entries when needed).
    private ArrayDeque<BlockPos> drainQueue;
    private int drainCooldown;

    public static FloodData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    // --- flood state ---------------------------------------------------------------------------

    public boolean isFloodActive() {
        return floodActive;
    }

    public void startFlood() {
        floodActive = true;
        floodTicks = 0;
        setDirty();
    }

    public void stopFlood() {
        floodActive = false;
        setDirty();
    }

    public int getFloodTicks() {
        return floodTicks;
    }

    public void tickFlood() {
        floodTicks++;
        // Only persist occasionally; losing a few seconds of rise progress on a crash is harmless.
        if (floodTicks % 200 == 0) {
            setDirty();
        }
    }

    public boolean wasRaining() {
        return wasRaining;
    }

    public void setWasRaining(boolean raining) {
        if (wasRaining != raining) {
            wasRaining = raining;
            setDirty();
        }
    }

    // --- tracked water -------------------------------------------------------------------------

    public Entry getEntry(BlockPos pos) {
        return entries.get(pos);
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public void add(BlockPos pos, BlockState original, int baseY) {
        entries.put(pos.immutable(), new Entry(original, baseY));
        drainQueue = null; // drain order is stale once new water is added
        drainCooldown = 0;
        setDirty();
    }

    public ArrayDeque<BlockPos> getDrainQueue() {
        return drainQueue;
    }

    public void setDrainQueue(ArrayDeque<BlockPos> queue) {
        drainQueue = queue;
    }

    public int getDrainCooldown() {
        return drainCooldown;
    }

    public void setDrainCooldown(int ticks) {
        drainCooldown = ticks;
    }

    public void remove(BlockPos pos) {
        if (entries.remove(pos) != null) {
            setDirty();
        }
    }

    /**
     * Positions in drain order: highest first, and within a layer the most recently placed
     * first, so the water visibly retreats back towards the original shoreline.
     */
    public List<BlockPos> drainOrder() {
        List<BlockPos> order = new ArrayList<>(entries.keySet());
        Collections.reverse(order);
        order.sort(Comparator.comparingInt((BlockPos p) -> p.getY()).reversed()); // stable sort keeps recency within a layer
        return order;
    }

    // --- persistence ---------------------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("FloodActive", floodActive);
        tag.putInt("FloodTicks", floodTicks);
        tag.putBoolean("WasRaining", wasRaining);

        // Store replaced block states as a palette; nearly every entry is plain air.
        Map<BlockState, Integer> paletteIndex = new HashMap<>();
        ListTag palette = new ListTag();
        long[] positions = new long[entries.size()];
        int[] states = new int[entries.size()];
        int[] bases = new int[entries.size()];

        int i = 0;
        for (Map.Entry<BlockPos, Entry> e : entries.entrySet()) {
            BlockState original = e.getValue().original();
            Integer index = paletteIndex.get(original);
            if (index == null) {
                index = palette.size();
                paletteIndex.put(original, index);
                palette.add(NbtUtils.writeBlockState(original));
            }
            positions[i] = e.getKey().asLong();
            states[i] = index;
            bases[i] = e.getValue().baseY();
            i++;
        }

        tag.put("Palette", palette);
        tag.putLongArray("Positions", positions);
        tag.putIntArray("States", states);
        tag.putIntArray("BaseY", bases);
        return tag;
    }

    private static FloodData load(CompoundTag tag, HolderLookup.Provider registries) {
        FloodData data = new FloodData();
        data.floodActive = tag.getBoolean("FloodActive");
        data.floodTicks = tag.getInt("FloodTicks");
        data.wasRaining = tag.getBoolean("WasRaining");

        HolderGetter<Block> blocks = registries.lookupOrThrow(Registries.BLOCK);
        ListTag paletteTag = tag.getList("Palette", Tag.TAG_COMPOUND);
        List<BlockState> palette = new ArrayList<>(paletteTag.size());
        for (int i = 0; i < paletteTag.size(); i++) {
            palette.add(NbtUtils.readBlockState(blocks, paletteTag.getCompound(i)));
        }

        long[] positions = tag.getLongArray("Positions");
        int[] states = tag.getIntArray("States");
        int[] bases = tag.getIntArray("BaseY");
        int count = Math.min(positions.length, Math.min(states.length, bases.length));
        for (int i = 0; i < count; i++) {
            int index = states[i];
            if (index < 0 || index >= palette.size()) {
                continue;
            }
            data.entries.put(BlockPos.of(positions[i]), new Entry(palette.get(index), bases[i]));
        }
        return data;
    }
}
