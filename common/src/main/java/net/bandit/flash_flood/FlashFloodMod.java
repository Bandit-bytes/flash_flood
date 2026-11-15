package net.bandit.flash_flood;

import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.TickEvent;

public final class FlashFloodMod {
    public static final String MOD_ID = "flash_flood";

    private static SimpleConfig config;

    public static void init() {
        config = SimpleConfig.load();
        config.save(); // writes any new options with their defaults

        TickEvent.SERVER_LEVEL_POST.register(FloodManager::tickLevel);
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> FloodCommand.register(dispatcher));
    }

    public static SimpleConfig config() {
        return config;
    }

    public static void initClient() {
        // No client-side code yet.
    }
}
