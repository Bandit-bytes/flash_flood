package net.bandit.flash_flood;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class SimpleConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File("config/flash_flood_config.json");

    public double waterCreationChance = 0.2;
    public int waterCreationRadius = 32;
    public boolean removeWaterAfterRain = true;

    // Configurations for water spreading and rising
    public double waterSpreadChance = 0.5;
    public int maxWaterSpreadRadius = 3; // Controls the horizontal spread radius
    public int maxWaterHeight = 3; // Controls the maximum height water can rise

    public static SimpleConfig load() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                return GSON.fromJson(reader, SimpleConfig.class);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return new SimpleConfig();
    }

    public void save() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
