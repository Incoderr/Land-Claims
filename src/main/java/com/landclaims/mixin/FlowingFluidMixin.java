package com.landclaims.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.landclaims.ClaimProtection;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

/**
 * Stops water and lava from flowing into land owned by someone else. NeoForge has no event for fluid spread,
 * and every spread step (water, lava, modded fluids built on FlowingFluid) goes through spreadTo.
 */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
    @Inject(method = "spreadTo", at = @At("HEAD"), cancellable = true, require = 1)
    private void landclaims$stopAtClaimBorder(LevelAccessor level, BlockPos pos, BlockState state, Direction direction,
            FluidState target, CallbackInfo ci) {
        if (level instanceof ServerLevel serverLevel
                && !ClaimProtection.fluidMayFlow(serverLevel, pos.relative(direction.getOpposite()), pos)) {
            ci.cancel();
        }
    }
}
