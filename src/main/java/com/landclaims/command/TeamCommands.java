package com.landclaims.command;

import java.util.UUID;
import java.util.stream.Collectors;

import com.landclaims.LandClaimsConfig;
import com.landclaims.data.ClaimData;
import com.landclaims.data.Team;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;

/** /clan create|invite|join|leave|kick|disband|info|list */
public final class TeamCommands {
    private TeamCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("clan")
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.word()).executes(TeamCommands::create)))
                .then(Commands.literal("invite")
                        .then(Commands.argument("player", EntityArgument.player()).executes(TeamCommands::invite)))
                .then(Commands.literal("join")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        data(ctx).teams().stream().map(Team::name), builder))
                                .executes(TeamCommands::join)))
                .then(Commands.literal("leave").executes(TeamCommands::leave))
                .then(Commands.literal("kick")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    ServerPlayer player = ctx.getSource().getPlayer();
                                    Team team = player == null ? null : data(ctx).teamOf(player.getUUID());
                                    return team == null ? builder.buildFuture() : SharedSuggestionProvider.suggest(
                                            team.members().stream().map(data(ctx)::nameOf), builder);
                                })
                                .executes(TeamCommands::kick)))
                .then(Commands.literal("disband").executes(TeamCommands::disband))
                .then(Commands.literal("info")
                        .executes(ctx -> info(ctx, null))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> info(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.literal("list").executes(TeamCommands::list)));
    }

    static ClaimData data(CommandContext<CommandSourceStack> ctx) {
        return ClaimData.get(ctx.getSource().getServer());
    }

    private static int create(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ClaimData data = data(ctx);
        String name = StringArgumentType.getString(ctx, "name");
        Team current = data.teamOf(player.getUUID());
        if (current != null) {
            ctx.getSource().sendFailure(Msg.error("team.already_in", current.name()));
            return 0;
        }
        if (!ClaimData.isValidTeamName(name)) {
            ctx.getSource().sendFailure(Msg.error("team.name_invalid"));
            return 0;
        }
        if (data.teamByName(name) != null) {
            ctx.getSource().sendFailure(Msg.error("team.name_taken", name));
            return 0;
        }
        data.rememberName(player.getUUID(), player.getGameProfile().name());
        Team team = data.createTeam(name, player.getUUID());
        ctx.getSource().sendSuccess(() -> Msg.ok("team.created", team.name()), false);
        return 1;
    }

    private static int invite(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        ClaimData data = data(ctx);
        Team team = ownTeamAsOwner(ctx, data, player);
        if (team == null) {
            return 0;
        }
        if (data.teamOf(target.getUUID()) != null) {
            ctx.getSource().sendFailure(Msg.error("team.target_in_team", target.getGameProfile().name()));
            return 0;
        }
        data.invite(team, target.getUUID());
        target.sendSystemMessage(Msg.ok("team.invite_received", player.getGameProfile().name(), team.name(), team.name()));
        ctx.getSource().sendSuccess(() -> Msg.ok("team.invited", target.getGameProfile().name(), team.name()), false);
        return 1;
    }

    private static int join(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ClaimData data = data(ctx);
        String name = StringArgumentType.getString(ctx, "name");
        Team current = data.teamOf(player.getUUID());
        if (current != null) {
            ctx.getSource().sendFailure(Msg.error("team.already_in", current.name()));
            return 0;
        }
        Team team = data.teamByName(name);
        if (team == null) {
            ctx.getSource().sendFailure(Msg.error("team.not_found", name));
            return 0;
        }
        if (!data.hasInvite(team, player.getUUID())) {
            ctx.getSource().sendFailure(Msg.error("team.no_invite", team.name()));
            return 0;
        }
        data.rememberName(player.getUUID(), player.getGameProfile().name());
        data.addMember(team, player.getUUID());
        notifyTeam(ctx, team, Msg.ok("team.joined", player.getGameProfile().name(), team.name()));
        return 1;
    }

    private static int leave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ClaimData data = data(ctx);
        Team team = data.teamOf(player.getUUID());
        if (team == null) {
            ctx.getSource().sendFailure(Msg.error("no_team"));
            return 0;
        }
        if (team.members().size() == 1) {
            return disbandTeam(ctx, data, team);
        }
        UUID newOwner = data.removeMember(team, player.getUUID());
        ctx.getSource().sendSuccess(() -> Msg.ok("team.left", team.name()), false);
        notifyTeam(ctx, team, Msg.info("team.member_left", player.getGameProfile().name(), team.name()));
        if (newOwner != null) {
            notifyTeam(ctx, team, Msg.ok("team.new_owner", data.nameOf(newOwner), team.name()));
        }
        return 1;
    }

    private static int kick(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ClaimData data = data(ctx);
        Team team = ownTeamAsOwner(ctx, data, player);
        if (team == null) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "player");
        UUID target = data.playerByName(name);
        if (target == null || !team.isMember(target)) {
            ctx.getSource().sendFailure(Msg.error("team.not_member", name));
            return 0;
        }
        if (target.equals(player.getUUID())) {
            ctx.getSource().sendFailure(Msg.error("team.cannot_kick_self"));
            return 0;
        }
        data.removeMember(team, target);
        notifyTeam(ctx, team, Msg.info("team.kicked", data.nameOf(target), team.name()));
        ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayer(target);
        if (online != null) {
            online.sendSystemMessage(Msg.error("team.you_were_kicked", team.name()));
        }
        return 1;
    }

    private static int disband(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ClaimData data = data(ctx);
        Team team = ownTeamAsOwner(ctx, data, player);
        return team == null ? 0 : disbandTeam(ctx, data, team);
    }

    private static int disbandTeam(CommandContext<CommandSourceStack> ctx, ClaimData data, Team team) {
        notifyTeam(ctx, team, Msg.info("team.disbanded", team.name(), data.claimCount(team)));
        data.disband(team);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        ClaimData data = data(ctx);
        Team team;
        if (name == null) {
            team = data.teamOf(ctx.getSource().getPlayerOrException().getUUID());
            if (team == null) {
                ctx.getSource().sendFailure(Msg.error("no_team"));
                return 0;
            }
        } else {
            team = data.teamByName(name);
            if (team == null) {
                ctx.getSource().sendFailure(Msg.error("team.not_found", name));
                return 0;
            }
        }
        String members = team.members().stream().map(data::nameOf).collect(Collectors.joining(", "));
        ctx.getSource().sendSuccess(() -> Msg.info("team.info", team.name(), data.nameOf(team.owner()), members,
                data.claimCount(team), LandClaimsConfig.MAX_CLAIMS_PER_TEAM.getAsInt()), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        ClaimData data = data(ctx);
        var teams = data.teams();
        ctx.getSource().sendSuccess(() -> Msg.info("team.list_header", teams.size()), false);
        for (Team team : teams) {
            ctx.getSource().sendSuccess(() -> Msg.info("team.list_entry", team.name(),
                    team.members().size(), data.claimCount(team)), false);
        }
        return teams.size();
    }

    /** The player's team if they own it; otherwise sends an error and returns null. */
    private static Team ownTeamAsOwner(CommandContext<CommandSourceStack> ctx, ClaimData data, ServerPlayer player) {
        Team team = data.teamOf(player.getUUID());
        if (team == null) {
            ctx.getSource().sendFailure(Msg.error("no_team"));
            return null;
        }
        if (!team.isOwner(player.getUUID())) {
            ctx.getSource().sendFailure(Msg.error("team.not_owner"));
            return null;
        }
        return team;
    }

    private static void notifyTeam(CommandContext<CommandSourceStack> ctx, Team team,
            net.minecraft.network.chat.Component message) {
        for (UUID member : team.members()) {
            ServerPlayer online = ctx.getSource().getServer().getPlayerList().getPlayer(member);
            if (online != null) {
                online.sendSystemMessage(message);
            }
        }
        ServerPlayer self = ctx.getSource().getPlayer();
        if (self == null || !team.isMember(self.getUUID())) {
            ctx.getSource().sendSuccess(() -> message, false);
        }
    }
}
