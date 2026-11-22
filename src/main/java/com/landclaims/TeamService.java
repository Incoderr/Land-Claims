package com.landclaims;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.landclaims.command.Msg;
import com.landclaims.data.ClaimData;
import com.landclaims.data.Team;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Team actions on behalf of a player, shared by /clan and the team screen.
 * Every method checks permissions itself and tells the player what happened. Returns true on success.
 */
public final class TeamService {
    private TeamService() {
    }

    private static ClaimData data(ServerPlayer player) {
        return ClaimData.get(player.level().getServer());
    }

    private static String name(ServerPlayer player) {
        return player.getGameProfile().name();
    }

    /** The player's team if they own it; otherwise tells them why not and returns null. */
    private static @Nullable Team ownedTeam(ServerPlayer player) {
        Team team = data(player).teamOf(player.getUUID());
        if (team == null) {
            player.sendSystemMessage(Msg.error("no_team"));
            return null;
        }
        if (!team.isOwner(player.getUUID())) {
            player.sendSystemMessage(Msg.error("team.not_owner"));
            return null;
        }
        return team;
    }

    /** Sends a message to every online member, and to the actor if they are not one. */
    private static void notifyTeam(ServerPlayer actor, Team team, Component message) {
        MinecraftServer server = actor.level().getServer();
        for (UUID member : team.members()) {
            ServerPlayer online = server.getPlayerList().getPlayer(member);
            if (online != null) {
                online.sendSystemMessage(message);
            }
        }
        if (!team.isMember(actor.getUUID())) {
            actor.sendSystemMessage(message);
        }
    }

    private static boolean checkNewName(ServerPlayer player, ClaimData data, String name) {
        if (!ClaimData.isValidTeamName(name)) {
            player.sendSystemMessage(Msg.error("team.name_invalid"));
            return false;
        }
        if (data.teamByName(name) != null) {
            player.sendSystemMessage(Msg.error("team.name_taken", name));
            return false;
        }
        return true;
    }

    public static boolean create(ServerPlayer player, String name) {
        ClaimData data = data(player);
        Team current = data.teamOf(player.getUUID());
        if (current != null) {
            player.sendSystemMessage(Msg.error("team.already_in", current.name()));
            return false;
        }
        if (!checkNewName(player, data, name)) {
            return false;
        }
        data.rememberName(player.getUUID(), name(player));
        Team team = data.createTeam(name, player.getUUID());
        player.sendSystemMessage(Msg.ok("team.created", team.name()));
        return true;
    }

    public static boolean invite(ServerPlayer player, ServerPlayer target) {
        ClaimData data = data(player);
        Team team = ownedTeam(player);
        if (team == null) {
            return false;
        }
        if (data.teamOf(target.getUUID()) != null) {
            player.sendSystemMessage(Msg.error("team.target_in_team", name(target)));
            return false;
        }
        data.invite(team, target.getUUID());
        target.sendSystemMessage(Msg.ok("team.invite_received", name(player), team.name(), team.name()));
        player.sendSystemMessage(Msg.ok("team.invited", name(target), team.name()));
        return true;
    }

    public static boolean join(ServerPlayer player, String teamName) {
        ClaimData data = data(player);
        Team current = data.teamOf(player.getUUID());
        if (current != null) {
            player.sendSystemMessage(Msg.error("team.already_in", current.name()));
            return false;
        }
        Team team = data.teamByName(teamName);
        if (team == null) {
            player.sendSystemMessage(Msg.error("team.not_found", teamName));
            return false;
        }
        if (!data.hasInvite(team, player.getUUID())) {
            player.sendSystemMessage(Msg.error("team.no_invite", team.name()));
            return false;
        }
        data.rememberName(player.getUUID(), name(player));
        data.addMember(team, player.getUUID());
        notifyTeam(player, team, Msg.ok("team.joined", name(player), team.name()));
        return true;
    }

    public static boolean leave(ServerPlayer player) {
        ClaimData data = data(player);
        Team team = data.teamOf(player.getUUID());
        if (team == null) {
            player.sendSystemMessage(Msg.error("no_team"));
            return false;
        }
        if (team.members().size() == 1) {
            return disbandTeam(player, data, team);
        }
        UUID newOwner = data.removeMember(team, player.getUUID());
        player.sendSystemMessage(Msg.ok("team.left", team.name()));
        notifyTeam(player, team, Msg.info("team.member_left", name(player), team.name()));
        if (newOwner != null) {
            notifyTeam(player, team, Msg.ok("team.new_owner", data.nameOf(newOwner), team.name()));
        }
        return true;
    }

    public static boolean kick(ServerPlayer player, UUID target) {
        ClaimData data = data(player);
        Team team = ownedTeam(player);
        if (team == null) {
            return false;
        }
        if (!team.isMember(target)) {
            player.sendSystemMessage(Msg.error("team.not_member", data.nameOf(target)));
            return false;
        }
        if (target.equals(player.getUUID())) {
            player.sendSystemMessage(Msg.error("team.cannot_kick_self"));
            return false;
        }
        data.removeMember(team, target);
        notifyTeam(player, team, Msg.info("team.kicked", data.nameOf(target), team.name()));
        ServerPlayer online = player.level().getServer().getPlayerList().getPlayer(target);
        if (online != null) {
            online.sendSystemMessage(Msg.error("team.you_were_kicked", team.name()));
        }
        return true;
    }

    public static boolean transfer(ServerPlayer player, UUID target) {
        ClaimData data = data(player);
        Team team = ownedTeam(player);
        if (team == null) {
            return false;
        }
        if (!team.isMember(target) || target.equals(player.getUUID())) {
            player.sendSystemMessage(Msg.error("team.not_member", data.nameOf(target)));
            return false;
        }
        data.transferOwnership(team, target);
        notifyTeam(player, team, Msg.ok("team.new_owner", data.nameOf(target), team.name()));
        return true;
    }

    public static boolean rename(ServerPlayer player, String name) {
        ClaimData data = data(player);
        Team team = ownedTeam(player);
        if (team == null || !checkNewName(player, data, name)) {
            return false;
        }
        String old = team.name();
        data.rename(team, name);
        notifyTeam(player, team, Msg.ok("team.renamed", old, name));
        return true;
    }

    public static boolean setColor(ServerPlayer player, int rgb) {
        Team team = ownedTeam(player);
        if (team == null) {
            return false;
        }
        data(player).setColor(team, rgb == Team.NO_COLOR ? Team.NO_COLOR : rgb & 0xFFFFFF);
        player.sendSystemMessage(Msg.ok("team.color_set"));
        return true;
    }

    public static boolean disband(ServerPlayer player) {
        Team team = ownedTeam(player);
        return team != null && disbandTeam(player, data(player), team);
    }

    private static boolean disbandTeam(ServerPlayer player, ClaimData data, Team team) {
        notifyTeam(player, team, Msg.info("team.disbanded", team.name(), data.claimCount(team)));
        data.disband(team);
        return true;
    }
}
