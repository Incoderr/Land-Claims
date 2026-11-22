package com.landclaims.mixin.thaumcraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import com.landclaims.ClaimProtection;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Thaumcraft 4 Reforged's hungry aura node eats blocks around it with a plain destroyBlock call, which fires no
 * break event, so nothing can stop it the normal way. On old servers protection plugins patched the node the same
 * way: inside a claim it still pulls in and consumes entities, but breaks no blocks.
 * {@code @Pseudo} skips this when Thaumcraft isn't installed; require = 0 skips it if Thaumcraft changes this code.
 */
@Pseudo
@Mixin(targets = "thaumcraft.node.NodeBlockEntity", remap = false)
public abstract class HungryNodeMixin {
    @WrapOperation(method = "handleHungryNodeSecond", require = 0, remap = false,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;destroyBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean landclaims$spareClaims(ServerLevel level, BlockPos pos, boolean drop, Operation<Boolean> original) {
        if (ClaimProtection.isClaimed(level, pos)) {
            return false;
        }
        return original.call(level, pos, drop);
    }
}
