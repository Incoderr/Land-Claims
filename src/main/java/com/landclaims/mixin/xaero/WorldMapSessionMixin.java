package com.landclaims.mixin.xaero;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import com.landclaims.client.xaero.XaeroClaimsHighlighter;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import xaero.map.highlight.HighlighterRegistry;

/**
 * Xaero's World Map has no public way to add chunk highlighters: it builds the list in WorldMapSession's
 * constructor and locks it with end(). We add ours right before end().
 * {@code @Pseudo} skips this mixin when Xaero isn't installed; require = 0 skips it if Xaero changes this code.
 */
@Pseudo
@Mixin(targets = "xaero.map.WorldMapSession", remap = false)
public abstract class WorldMapSessionMixin {
    @WrapOperation(method = "<init>", require = 0, remap = false,
            at = @At(value = "INVOKE", target = "Lxaero/map/highlight/HighlighterRegistry;end()V", remap = false))
    private void landclaims$addClaimsHighlighter(HighlighterRegistry registry, Operation<Void> original) {
        registry.register(new XaeroClaimsHighlighter());
        original.call(registry);
    }
}
