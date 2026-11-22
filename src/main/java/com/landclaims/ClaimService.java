package com.landclaims;

import java.util.ArrayList;
import java.util.List;

import com.landclaims.command.Msg;
import com.landclaims.data.ClaimData;
import com.landclaims.data.Team;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/** Claim and unclaim on behalf of a player. Shared by the commands and the map. Sends feedback to the player. */
public final class ClaimService {
    /** Largest square the map may claim in one request: radius 5, so 11x11 chunks. */
    public static final int MAX_RADIUS = 5;
    public static final int MAX_CHUNKS_PER_REQUEST = (2 * MAX_RADIUS + 1) * (2 * MAX_RADIUS + 1);

    private ClaimService() {
    }

    public static List<ChunkPos> square(ChunkPos center, int radius) {
        List<ChunkPos> result = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                result.add(new ChunkPos(center.x() + dx, center.z() + dz));
            }
        }
        return result;
    }

    /** The player's team; creates a personal one named after the player if they have none. */
    public static Team teamOrCreate(ClaimData data, ServerPlayer player) {
        Team team = data.teamOf(player.getUUID());
        if (team != null) {
            return team;
        }
        String playerName = player.getGameProfile().name();
        data.rememberName(player.getUUID(), playerName);
        Team created = data.createTeam(data.freeTeamName(playerName), player.getUUID());
        player.sendSystemMessage(Msg.info("team.auto_created", created.name()));
        return created;
    }

    public static int claim(ServerPlayer player, ResourceKey<Level> dimension, List<ChunkPos> chunks) {
        ClaimData data = ClaimData.get(player.level().getServer());
        int limit = LandClaimsConfig.MAX_CLAIMS_PER_TEAM.getAsInt();

        if (chunks.size() == 1) {
            // Single chunk: say exactly why it failed, before creating a team for nothing.
            ChunkPos chunk = chunks.getFirst();
            Team owner = data.teamAt(dimension, chunk);
            if (owner != null) {
                player.sendSystemMessage(owner.isMember(player.getUUID())
                        ? Msg.error("already_yours") : Msg.error("taken", owner.name()));
                return 0;
            }
        }

        Team team = teamOrCreate(data, player);
        int claimed = 0;
        int skipped = 0;
        boolean hitLimit = false;
        for (ChunkPos chunk : chunks) {
            Team owner = data.teamAt(dimension, chunk);
            if (owner == team) {
                continue;
            }
            if (owner != null) {
                skipped++;
            } else if (data.claimCount(team) >= limit) {
                skipped++;
                hitLimit = true;
            } else {
                data.claim(dimension, chunk, team);
                claimed++;
            }
        }

        if (chunks.size() == 1 && claimed == 1) {
            ChunkPos chunk = chunks.getFirst();
            player.sendSystemMessage(Msg.ok("claimed", chunk.x(), chunk.z(), team.name(), data.claimCount(team), limit));
        } else if (chunks.size() > 1) {
            player.sendSystemMessage(Msg.ok("area", claimed, skipped));
        }
        if (hitLimit) {
            player.sendSystemMessage(Msg.error("limit", limit));
        }
        return claimed;
    }

    public static int unclaim(ServerPlayer player, ResourceKey<Level> dimension, List<ChunkPos> chunks) {
        ClaimData data = ClaimData.get(player.level().getServer());
        int removed = 0;
        int skipped = 0;
        for (ChunkPos chunk : chunks) {
            Team owner = data.teamAt(dimension, chunk);
            if (owner == null) {
                continue;
            }
            if (owner.isMember(player.getUUID())) {
                data.unclaim(dimension, chunk);
                removed++;
            } else {
                skipped++;
            }
        }

        if (chunks.size() == 1) {
            ChunkPos chunk = chunks.getFirst();
            if (removed == 1) {
                player.sendSystemMessage(Msg.ok("unclaimed", chunk.x(), chunk.z()));
            } else {
                player.sendSystemMessage(Msg.error(skipped == 1 ? "not_yours" : "not_claimed"));
            }
        } else {
            player.sendSystemMessage(Msg.ok("unclaimed_area", removed, skipped));
        }
        return removed;
    }
}
