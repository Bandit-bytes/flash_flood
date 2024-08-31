package net.bandit.flash_flood.fabric;

import net.bandit.flash_flood.FlashFloodMod;
import net.fabricmc.api.ModInitializer;


public final class FlashFloodModFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // Proceed with mild caution.

        // Run our common setup.
        FlashFloodMod.init();
    }
}
