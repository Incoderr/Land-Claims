package com.landclaims.client.journeymap;

import java.util.List;

import com.landclaims.ClaimService;
import com.landclaims.LandClaims;
import com.landclaims.client.ClientClaims;
import com.landclaims.network.ClaimActionPayload;
import com.landclaims.network.ClaimSyncPayload;

import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.client.display.DisplayType;
import journeymap.api.v2.client.display.PolygonOverlay;
import journeymap.api.v2.client.event.PopupMenuEvent;
import journeymap.api.v2.client.fullscreen.ModPopupMenu;
import journeymap.api.v2.client.model.MapPolygon;
import journeymap.api.v2.client.model.ShapeProperties;
import journeymap.api.v2.common.Context;
import journeymap.api.v2.common.JourneyMapPlugin;
import journeymap.api.v2.common.event.FullscreenEventRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Loaded by JourneyMap only when it is installed (found through the annotation), so nothing here runs without it.
 * Draws claims on the fullscreen map and minimap, and adds claim actions to the fullscreen map's right-click menu.
 */
@JourneyMapPlugin(apiVersion = IClientAPI.API_VERSION)
public final class JourneyMapIntegration implements IClientPlugin {
    private static final int POLYGON_Y = 64;
    private IClientAPI api;

    @Override
    public String getModId() {
        return LandClaims.MODID;
    }

    @Override
    public void initialize(IClientAPI api) {
        this.api = api;
        FullscreenEventRegistry.FULLSCREEN_POPUP_MENU_EVENT.subscribe(LandClaims.MODID, this::onPopupMenu);
        ClientClaims.addListener(this::redraw);
        redraw();
    }

    private void onPopupMenu(PopupMenuEvent.FullscreenPopupMenuEvent event) {
        ModPopupMenu menu = event.getPopupMenu().createSubItemList(I18n.get("landclaims.map.menu"));
        menu.addMenuItem(I18n.get("landclaims.map.claim"), pos -> send(true, pos, 0));
        menu.addMenuItem(I18n.get("landclaims.map.claim_area", 3, 3), pos -> send(true, pos, 1));
        menu.addMenuItem(I18n.get("landclaims.map.claim_area", 5, 5), pos -> send(true, pos, 2));
        menu.addMenuItem(I18n.get("landclaims.map.unclaim"), pos -> send(false, pos, 0));
        menu.addMenuItem(I18n.get("landclaims.map.unclaim_area", 3, 3), pos -> send(false, pos, 1));
        menu.addMenuItem(I18n.get("landclaims.map.unclaim_area", 5, 5), pos -> send(false, pos, 2));
    }

    private static void send(boolean claim, BlockPos pos, int radius) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        // The fullscreen map shows the dimension the player is in, which is also the one we have claims for.
        ResourceKey<Level> dimension = mc.level.dimension();
        List<ChunkPos> chunks = ClaimService.square(ChunkPos.containing(pos), radius);
        ClientPacketDistributor.sendToServer(new ClaimActionPayload(claim ? ClaimActionPayload.Action.CLAIM : ClaimActionPayload.Action.UNCLAIM, dimension, chunks));
    }

    private void redraw() {
        if (api == null) {
            return;
        }
        api.removeAll(LandClaims.MODID, DisplayType.Polygon);
        ResourceKey<Level> dimension = ClientClaims.dimension();
        if (dimension == null || !api.playerAccepts(LandClaims.MODID, DisplayType.Polygon)) {
            return;
        }
        for (ClaimSyncPayload.Entry entry : ClientClaims.claims().values()) {
            try {
                api.show(overlay(dimension, entry));
            } catch (Exception e) {
                LandClaims.LOGGER.warn("JourneyMap refused a claim overlay", e);
                return;
            }
        }
    }

    private static PolygonOverlay overlay(ResourceKey<Level> dimension, ClaimSyncPayload.Entry entry) {
        ChunkPos chunk = ChunkPos.unpack(entry.chunk());
        int x0 = chunk.getMinBlockX();
        int z0 = chunk.getMinBlockZ();
        int x1 = x0 + 16;
        int z1 = z0 + 16;
        MapPolygon square = new MapPolygon(
                new BlockPos(x0, POLYGON_Y, z0), new BlockPos(x0, POLYGON_Y, z1),
                new BlockPos(x1, POLYGON_Y, z1), new BlockPos(x1, POLYGON_Y, z0));
        ShapeProperties shape = new ShapeProperties()
                .setFillColor(entry.color()).setFillOpacity(0.3f)
                .setStrokeColor(entry.color()).setStrokeOpacity(0.6f).setStrokeWidth(1f);
        PolygonOverlay overlay = new PolygonOverlay(LandClaims.MODID, dimension, shape, square);
        overlay.setOverlayGroupName("Land Claims")
                .setTitle(entry.team())
                .setActiveUIs(Context.UI.Fullscreen, Context.UI.Minimap);
        return overlay;
    }
}
