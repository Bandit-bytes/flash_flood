package net.bandit.flash_flood.neoforge;

import dev.architectury.utils.EnvExecutor;
import net.bandit.flash_flood.FlashFloodMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

@Mod(net.bandit.flash_flood.FlashFloodMod.MOD_ID)
public final class FlashFloodModNF {
    public FlashFloodModNF() {
        // Run our common setup.
        net.bandit.flash_flood.FlashFloodMod.init();
        EnvExecutor.runInEnv(Dist.CLIENT, () -> FlashFloodMod::initClient);
    }
}
