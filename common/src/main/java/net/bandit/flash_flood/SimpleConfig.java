package net.bandit.flash_flood;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.architectury.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class SimpleConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(FlashFloodMod.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "flash_flood_config.json";

    /** Chance (0-1) that a new spell of rain turns into a flood. */
    public double floodChance = 0.25;

    /** How many blocks above their normal level water bodies can rise during a flood. */
    public int maxRise = 2;

    /** Seconds between each additional block of rise (the first block of rise is immediate). */
    public int riseIntervalSeconds = 90;

    /** Floods only spread within this many blocks of a player. */
    public int floodRadius = 48;

    /** Random columns checked around each player every tick while flooding. Higher = faster spread, more CPU. */
    public int spreadAttemptsPerPlayer = 48;

    /** Hard cap on flood water blocks placed per dimension per tick. */
    public int maxBlocksPerTick = 64;

    /** Flood water blocks drained per dimension per tick once the flood ends. */
    public int drainBlocksPerTick = 24;

    /** Safety cap on how many flood water blocks a dimension can hold at once. */
    public int maxFloodBlocks = 60000;

    /** Allow oceans to overflow. Rivers, lakes and ponds always can. */
    public boolean floodOceans = true;

    /** Let flood water replace grass, ferns and small flowers (restored when the water drains). */
    public boolean replacePlants = true;

    /** Show an action-bar message to players when a flood starts and ends. */
    public boolean announceFloods = true;

    public static SimpleConfig load() {
        Path file = configFile();
        SimpleConfig config = null;
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                config = GSON.fromJson(reader, SimpleConfig.class);
            } catch (IOException | JsonParseException e) {
                LOGGER.error("Failed to read {}, using defaults", file, e);
            }
        }
        if (config == null) {
            config = new SimpleConfig();
        }
        config.validate();
        return config;
    }

    public void save() {
        Path file = configFile();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to write {}", file, e);
        }
    }

    private void validate() {
        floodChance = clamp(floodChance, 0.0, 1.0);
        maxRise = clamp(maxRise, 1, 8);
        riseIntervalSeconds = clamp(riseIntervalSeconds, 1, 3600);
        floodRadius = clamp(floodRadius, 8, 128);
        spreadAttemptsPerPlayer = clamp(spreadAttemptsPerPlayer, 1, 1024);
        maxBlocksPerTick = clamp(maxBlocksPerTick, 1, 4096);
        drainBlocksPerTick = clamp(drainBlocksPerTick, 1, 4096);
        maxFloodBlocks = clamp(maxFloodBlocks, 100, 1_000_000);
    }

    private static Path configFile() {
        return Platform.getConfigFolder().resolve(FILE_NAME);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Double.isNaN(value) ? min : Math.max(min, Math.min(max, value));
    }
}
