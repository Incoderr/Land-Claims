package com.landclaims.command;

import java.util.List;

import com.landclaims.ClaimService;
import com.landclaims.LandClaimsConfig;
import com.landclaims.data.ClaimData;
import com.landclaims.data.Team;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/** /claim, /claim area|info|list|admin, /unclaim, /unclaim all */
public final class ClaimCommands {
    private static final int MAX_AREA_RADIUS = ClaimService.MAX_RADIUS;

    private ClaimCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("claim")
                .executes(ClaimCommands::claimHere)
                .then(Commands.literal("area")
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_AREA_RADIUS))
                                .executes(ClaimCommands::claimArea)))
                .then(Commands.literal("info").executes(ClaimCommands::info))
                .then(Commands.literal("forceload")
                        .executes(ctx -> forceLoad(ctx, true))
                        .then(Commands.literal("off").executes(ctx -> forceLoad(ctx, false))))
                .then(Commands.literal("list").executes(ClaimCommands::list))
                .then(Commands.literal("admin")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("bypass").executes(ClaimCommands::toggleBypass))
                        .then(Commands.literal("unclaim").executes(ClaimCommands::adminUnclaim))));

        dispatcher.register(Commands.literal("unclaim")
                .executes(ClaimCommands::unclaimHere)
                .then(Commands.literal("all").executes(ClaimCommands::unclaimAll)));
    }

    private static ClaimData data(CommandContext<CommandSourceStack> ctx) {
        return ClaimData.get(ctx.getSource().getServer());
    }

    private static int claimHere(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        return ClaimService.claim(player, player.level().dimension(), List.of(player.chunkPosition()));
    }

    private static int claimArea(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int radius = IntegerArgumentType.getInteger(ctx, "radius");
        return ClaimService.claim(player, player.level().dimension(), ClaimService.square(player.chunkPosition(), radius));
    }

    private static int forceLoad(CommandContext<CommandSourceStack> ctx, boolean enable) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        return ClaimService.setForceLoad(player, player.level().dimension(), List.of(player.chunkPosition()), enable);
    }

    private static int unclaimHere(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        return ClaimService.unclaim(player, player.level().dimension(), List.of(player.chunkPosition()));
    }

    private static int unclaimAll(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ClaimData data = data(ctx);
        Team team = data.teamOf(player.getUUID());
        if (team == null) {
            ctx.getSource().sendFailure(Msg.error("no_team"));
            return 0;
        }
        if (!team.isOwner(player.getUUID())) {
            ctx.getSource().sendFailure(Msg.error("team.not_owner"));
            return 0;
        }
        int removed = data.unclaimAll(team).size();
        ctx.getSource().sendSuccess(() -> Msg.ok("unclaimed_all", removed), false);
        return removed;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ChunkPos chunk = player.chunkPosition();
        Team owner = data(ctx).teamAt(player.level().dimension(), chunk);
        if (owner == null) {
            ctx.getSource().sendSuccess(() -> Msg.info("info_free", chunk.x(), chunk.z()), false);
        } else {
            ctx.getSource().sendSuccess(() -> Msg.info("info_claimed", chunk.x(), chunk.z(), owner.name()), false);
        }
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ClaimData data = data(ctx);
        Team team = data.teamOf(player.getUUID());
        if (team == null) {
            ctx.getSource().sendFailure(Msg.error("no_team"));
            return 0;
        }
        List<ClaimData.ClaimPos> claims = data.claimsOf(team);
        ctx.getSource().sendSuccess(() -> Msg.info("list_header", team.name(), claims.size(),
                LandClaimsConfig.MAX_CLAIMS_PER_TEAM.getAsInt()), false);
        for (ClaimData.ClaimPos pos : claims) {
            ctx.getSource().sendSuccess(() -> Msg.info("list_entry", pos.dimension().identifier().toString(),
                    pos.chunk().x(), pos.chunk().z()), false);
        }
        return claims.size();
    }

    private static int toggleBypass(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        boolean on = data(ctx).toggleBypass(player.getUUID());
        ctx.getSource().sendSuccess(() -> Msg.ok(on ? "bypass_on" : "bypass_off"), true);
        return 1;
    }

    private static int adminUnclaim(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ClaimData data = data(ctx);
        ResourceKey<Level> dimension = player.level().dimension();
        ChunkPos chunk = player.chunkPosition();
        Team owner = data.teamAt(dimension, chunk);
        if (owner == null) {
            ctx.getSource().sendFailure(Msg.error("not_claimed"));
            return 0;
        }
        data.unclaim(dimension, chunk);
        ctx.getSource().sendSuccess(() -> Msg.ok("admin_unclaimed", owner.name()), true);
        return 1;
    }
}
