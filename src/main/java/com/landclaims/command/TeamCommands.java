package com.landclaims.command;

import java.util.UUID;
import java.util.stream.Collectors;

import com.landclaims.LandClaimsConfig;
import com.landclaims.TeamService;
import com.landclaims.data.ClaimData;
import com.landclaims.data.Team;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;

/** /clan create|invite|join|leave|kick|transfer|rename|disband|info|list. The logic lives in TeamService. */
public final class TeamCommands {
    private TeamCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        SuggestionProvider<CommandSourceStack> members = (ctx, builder) -> {
            ServerPlayer player = ctx.getSource().getPlayer();
            Team team = player == null ? null : data(ctx).teamOf(player.getUUID());
            return team == null ? builder.buildFuture()
                    : SharedSuggestionProvider.suggest(team.members().stream().map(data(ctx)::nameOf), builder);
        };
        dispatcher.register(Commands.literal("clan")
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> ok(TeamService.create(player(ctx), name(ctx))))))
                .then(Commands.literal("invite")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> ok(TeamService.invite(player(ctx), EntityArgument.getPlayer(ctx, "player"))))))
                .then(Commands.literal("join")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        data(ctx).teams().stream().map(Team::name), builder))
                                .executes(ctx -> ok(TeamService.join(player(ctx), name(ctx))))))
                .then(Commands.literal("leave").executes(ctx -> ok(TeamService.leave(player(ctx)))))
                .then(Commands.literal("kick")
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(members)
                                .executes(ctx -> byName(ctx, target -> TeamService.kick(player(ctx), target)))))
                .then(Commands.literal("transfer")
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(members)
                                .executes(ctx -> byName(ctx, target -> TeamService.transfer(player(ctx), target)))))
                .then(Commands.literal("rename")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> ok(TeamService.rename(player(ctx), name(ctx))))))
                .then(Commands.literal("disband").executes(ctx -> ok(TeamService.disband(player(ctx)))))
                .then(Commands.literal("info")
                        .executes(ctx -> info(ctx, null))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> info(ctx, name(ctx)))))
                .then(Commands.literal("list").executes(TeamCommands::list)));
    }

    static ClaimData data(CommandContext<CommandSourceStack> ctx) {
        return ClaimData.get(ctx.getSource().getServer());
    }

    private static ServerPlayer player(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return ctx.getSource().getPlayerOrException();
    }

    private static String name(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "name");
    }

    private static int ok(boolean success) {
        return success ? 1 : 0;
    }

    @FunctionalInterface
    private interface TargetAction {
        boolean run(UUID target) throws CommandSyntaxException;
    }

    /** Resolves a player name, including offline members, to their id. */
    private static int byName(CommandContext<CommandSourceStack> ctx, TargetAction action) throws CommandSyntaxException {
        String name = StringArgumentType.getString(ctx, "player");
        UUID target = data(ctx).playerByName(name);
        if (target == null) {
            ctx.getSource().sendFailure(Msg.error("team.not_member", name));
            return 0;
        }
        return ok(action.run(target));
    }

    private static int info(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        ClaimData data = data(ctx);
        Team team;
        if (name == null) {
            team = data.teamOf(player(ctx).getUUID());
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
        String memberNames = team.members().stream().map(data::nameOf).collect(Collectors.joining(", "));
        ctx.getSource().sendSuccess(() -> Msg.info("team.info", team.name(), data.nameOf(team.owner()), memberNames,
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
}
