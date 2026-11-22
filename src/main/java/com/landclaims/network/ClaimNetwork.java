package com.landclaims.network;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.landclaims.ClaimService;
import com.landclaims.ForceLoadManager;
import com.landclaims.LandClaims;
import com.landclaims.LandClaimsConfig;
import com.landclaims.TeamService;
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
    /** Teams that haven't chosen a color: own team is drawn green, others get a color derived from their id. */
    public static final int OWN_COLOR = 0x3CC864;

    private ClaimNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        // Client handlers for the sync payloads are registered in ClientEvents.
        registrar.playToClient(ClaimSyncPayload.TYPE, ClaimSyncPayload.STREAM_CODEC);
        registrar.playToClient(TeamSyncPayload.TYPE, TeamSyncPayload.STREAM_CODEC);
        registrar.playToServer(ClaimActionPayload.TYPE, ClaimActionPayload.STREAM_CODEC, ClaimNetwork::handleAction);
        registrar.playToServer(TeamActionPayload.TYPE, TeamActionPayload.STREAM_CODEC, ClaimNetwork::handleTeamAction);
    }

    private static void handleTeamAction(TeamActionPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        switch (payload.action()) {
            case CREATE -> TeamService.create(player, payload.text());
            case JOIN -> TeamService.join(player, payload.text());
            case RENAME -> TeamService.rename(player, payload.text());
            case LEAVE -> TeamService.leave(player);
            case DISBAND -> TeamService.disband(player);
            case KICK -> TeamService.kick(player, payload.target());
            case TRANSFER -> TeamService.transfer(player, payload.target());
            case COLOR -> TeamService.setColor(player, payload.value());
            case INVITE -> {
                ServerPlayer target = player.level().getServer().getPlayerList().getPlayer(payload.target());
                if (target != null) {
                    TeamService.invite(player, target);
                }
            }
        }
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

    /** The color a team is drawn in for a given viewer. */
    public static int displayColor(Team team, boolean own) {
        if (team.color() != Team.NO_COLOR) {
            return team.color();
        }
        return own ? OWN_COLOR : teamColor(team.id());
    }

    public static void sendTeamTo(ServerPlayer player) {
        if (!player.connection.hasChannel(TeamSyncPayload.TYPE)) {
            return;
        }
        MinecraftServer server = player.level().getServer();
        ClaimData data = ClaimData.get(server);
        Team team = data.teamOf(player.getUUID());
        List<TeamSyncPayload.Player> members = new ArrayList<>();
        List<TeamSyncPayload.Player> candidates = new ArrayList<>();
        List<String> invites = new ArrayList<>();
        if (team != null) {
            members.add(player(server, data, team.owner()));
            for (UUID member : team.members()) {
                if (!team.isOwner(member)) {
                    members.add(player(server, data, member));
                }
            }
            for (ServerPlayer online : server.getPlayerList().getPlayers()) {
                if (data.teamOf(online.getUUID()) == null) {
                    candidates.add(new TeamSyncPayload.Player(online.getUUID(), online.getGameProfile().name(), true));
                }
            }
        } else {
            data.invitesFor(player.getUUID()).forEach(t -> invites.add(t.name()));
        }
        PacketDistributor.sendToPlayer(player, new TeamSyncPayload(
                team == null ? "" : team.name(),
                team == null ? Team.NO_COLOR : team.color(),
                team == null ? player.getUUID() : team.owner(),
                members, invites, candidates));
    }

    private static TeamSyncPayload.Player player(MinecraftServer server, ClaimData data, UUID id) {
        return new TeamSyncPayload.Player(id, data.nameOf(id), server.getPlayerList().getPlayer(id) != null);
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
                    displayColor(team, own), own, data.isForced(dimension, e.getLongKey())));
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
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                sendTo(player);
                sendTeamTo(player);
            }
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Everyone's team screen shows who is online, so resync all on the next tick.
            ClaimData.get(player.level().getServer()).requestSync();
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ClaimData.get(player.level().getServer()).requestSync();
        }
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendTo(player);
        }
    }
}
