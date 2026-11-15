package net.bandit.flash_flood.fabric;

import net.bandit.flash_flood.FlashFloodMod;
import net.fabricmc.api.ModInitializer;

public final class FlashFloodModFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // Run our common setup.
        FlashFloodMod.init();
    }
}
