package net.bandit.flash_flood.mixin;

import net.bandit.flash_flood.FloodManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.WaterFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flowing water runs ahead of a flood and destroys grass and flowers before the flood itself
 * arrives. Record those plants so they can be put back when the flood drains.
 */
@Mixin(WaterFluid.class)
public abstract class WaterFluidMixin {
    @Inject(method = "beforeDestroyingBlock", at = @At("HEAD"))
    private void flash_flood$recordWashedPlant(LevelAccessor level, BlockPos pos, BlockState state, CallbackInfo ci) {
        if (level instanceof ServerLevel serverLevel) {
            FloodManager.onWaterDestroysBlock(serverLevel, pos, state);
        }
    }
}
