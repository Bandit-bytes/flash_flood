package net.bandit.flash_flood.fabric.client;

import net.bandit.flash_flood.FlashFloodMod;
import net.fabricmc.api.ClientModInitializer;

public final class FlashFloodModFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        FlashFloodMod.initClient();
    }
}
