package com.landclaims;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

import com.landclaims.command.ClaimCommands;
import com.landclaims.command.TeamCommands;

@Mod(LandClaims.MODID)
public class LandClaims {
    public static final String MODID = "landclaims";
    public static final Logger LOGGER = LogUtils.getLogger();

    public LandClaims(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SYNCED, LandClaimsConfig.SPEC);
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        TeamCommands.register(event.getDispatcher());
        ClaimCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Land Claims loaded: max {} claims and {} force-loaded chunks per team",
                LandClaimsConfig.MAX_CLAIMS_PER_TEAM.getAsInt(),
                LandClaimsConfig.MAX_FORCE_LOADED_PER_TEAM.getAsInt());
    }
}
