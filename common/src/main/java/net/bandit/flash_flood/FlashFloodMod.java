package net.bandit.flash_flood;

import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.TickEvent;

public final class FlashFloodMod {
    public static final String MOD_ID = "flash_flood";

    /** Dev-only trailer recording mode, enabled by the runTrailer Gradle task. */
    public static final boolean TRAILER_MODE = Boolean.getBoolean("flash_flood.trailer");

    private static SimpleConfig config;

    public static void init() {
        config = SimpleConfig.load();
        config.save(); // writes any new options with their defaults

        TickEvent.SERVER_LEVEL_POST.register(FloodManager::tickLevel);
        TickEvent.SERVER_PRE.register(FloodManager::tickServer);
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> FloodCommand.register(dispatcher));
    }

    public static SimpleConfig config() {
        return config;
    }

    public static void initClient() {
        if (TRAILER_MODE) {
            // Loaded reflectively: the trailer package is stripped from release jars.
            try {
                Class.forName("net.bandit.flash_flood.client.trailer.TrailerDirector").getMethod("init").invoke(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Trailer mode requested but the trailer director is missing", e);
            }
        }
    }
}
