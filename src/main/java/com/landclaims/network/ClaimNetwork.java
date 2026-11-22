package com.landclaims.network;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.landclaims.ClaimService;
import com.landclaims.ForceLoadManager;
import com.landclaims.LandClaims;
import com.landclaims.LandClaimsConfig;
import com.landclaims.data.ClaimData;
import com.landclaims.data.Team;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Keeps clients' copy of the claims up to date and receives claim requests from the map.
 * The channels are optional, so players without the mod can still join a server that has it.
 */
@EventBusSubscriber(modid = LandClaims.MODID)
public final class ClaimNetwork {
    /** Own team is always drawn in this green, other teams get a color derived from their id. */
    public static final int OWN_COLOR = 0x3CC864;

    private ClaimNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        // The client handler for the sync payload is registered in LandClaimsClient.
        registrar.playToClient(ClaimSyncPayload.TYPE, ClaimSyncPayload.STREAM_CODEC);
        registrar.playToServer(ClaimActionPayload.TYPE, ClaimActionPayload.STREAM_CODEC, ClaimNetwork::handleAction);
    }

    private static void handleAction(ClaimActionPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        if (player.level().getServer().getLevel(payload.dimension()) == null || payload.chunks().isEmpty()) {
            return;
        }
        switch (payload.action()) {
            case CLAIM -> ClaimService.claim(player, payload.dimension(), payload.chunks());
            case UNCLAIM -> ClaimService.unclaim(player, payload.dimension(), payload.chunks());
            case FORCE_LOAD -> ClaimService.setForceLoad(player, payload.dimension(), payload.chunks(), true);
            case STOP_FORCE_LOAD -> ClaimService.setForceLoad(player, payload.dimension(), payload.chunks(), false);
        }
    }

    public static int teamColor(UUID teamId) {
        float hue = (teamId.hashCode() & 0xFFFF) / 65536f;
        return Color.HSBtoRGB(hue, 0.65f, 0.95f) & 0xFFFFFF;
    }

    public static void sendTo(ServerPlayer player) {
        if (!player.connection.hasChannel(ClaimSyncPayload.TYPE)) {
            return;
        }
        ClaimData data = ClaimData.get(player.level().getServer());
        Team ownTeam = data.teamOf(player.getUUID());
        var dimension = player.level().dimension();
        Long2ObjectMap<UUID> claims = data.claimsInDimension(dimension);
        List<ClaimSyncPayload.Entry> entries = new ArrayList<>(claims.size());
        for (Long2ObjectMap.Entry<UUID> e : claims.long2ObjectEntrySet()) {
            Team team = data.team(e.getValue());
            if (team == null) {
                continue;
            }
            boolean own = team == ownTeam;
            entries.add(new ClaimSyncPayload.Entry(e.getLongKey(), team.name(),
                    own ? OWN_COLOR : teamColor(team.id()), own, data.isForced(dimension, e.getLongKey())));
        }
        PacketDistributor.sendToPlayer(player, new ClaimSyncPayload(dimension, entries,
                ownTeam == null ? "" : ownTeam.name(),
                ownTeam == null ? 0 : data.claimCount(ownTeam),
                LandClaimsConfig.MAX_CLAIMS_PER_TEAM.getAsInt(),
                ownTeam == null ? 0 : data.forcedCount(ownTeam),
                LandClaimsConfig.MAX_FORCE_LOADED_PER_TEAM.getAsInt()));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (ClaimData.get(server).consumeSyncPending()) {
            ForceLoadManager.markDirty();
            server.getPlayerList().getPlayers().forEach(ClaimNetwork::sendTo);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendTo(player);
        }
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendTo(player);
        }
    }
}
