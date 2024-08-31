package net.bandit.flash_flood.forge;

import dev.architectury.platform.forge.EventBuses;
import dev.architectury.utils.EnvExecutor;
import net.bandit.flash_flood.FlashFloodMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;


@Mod(FlashFloodMod.MOD_ID)
public final class FlashFloodModForge {
    public FlashFloodModForge() {
        // Submit our event bus to let Architectury API register our content on the right time.
        EventBuses.registerModEventBus(FlashFloodMod.MOD_ID, FMLJavaModLoadingContext.get().getModEventBus());

        // Run our common setup.
        FlashFloodMod.init();
        EnvExecutor.runInEnv(Dist.CLIENT, () -> FlashFloodMod::initClient);
    }
}
