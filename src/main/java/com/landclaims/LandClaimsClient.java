package com.landclaims;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client-only entry point. Map integrations (JourneyMap, Xaero's) will hook in from here. */
@Mod(value = LandClaims.MODID, dist = Dist.CLIENT)
public class LandClaimsClient {
    public LandClaimsClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
