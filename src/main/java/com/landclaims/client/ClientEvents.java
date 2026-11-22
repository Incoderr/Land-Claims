package com.landclaims.client;

import com.landclaims.LandClaims;
import com.landclaims.client.map.MapTiles;
import com.landclaims.client.screen.ClaimScreen;
import com.landclaims.network.ClaimSyncPayload;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

@EventBusSubscriber(modid = LandClaims.MODID, value = Dist.CLIENT)
public final class ClientEvents {
    public static final KeyMapping.Category CATEGORY =
            new KeyMapping.Category(Identifier.fromNamespaceAndPath(LandClaims.MODID, "main"));

    /** Opens the claim screen. G is free in vanilla and not used by JourneyMap (J) or Xaero's (M). */
    public static final KeyMapping OPEN_SCREEN = new KeyMapping("key.landclaims.open",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYBOARD, InputConstants.KEY_G, CATEGORY);

    private ClientEvents() {
    }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(OPEN_SCREEN);
    }

    @SubscribeEvent
    public static void registerPayloadHandlers(RegisterClientPayloadHandlersEvent event) {
        event.register(ClaimSyncPayload.TYPE, ClientClaims::handleSync);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (OPEN_SCREEN.consumeClick()) {
            if (mc.player != null && mc.gui.screen() == null) {
                mc.gui.setScreen(new ClaimScreen());
            }
        }
        MapTiles.tick();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientClaims.clear();
        MapTiles.clear();
    }
}
