package com.landclaims.network;

import java.util.List;
import java.util.UUID;

import com.landclaims.LandClaims;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server to client: what the team screen shows.
 *
 * @param team        the receiver's team name, empty if they have none
 * @param color       the team's chosen RGB, or -1 if none
 * @param owner       the owner's id (the receiver's own id when there is no team)
 * @param members     team members, owner first
 * @param invites     names of teams that invited the receiver
 * @param candidates  online players without a team, who could be invited
 */
public record TeamSyncPayload(String team, int color, UUID owner, List<Player> members, List<String> invites,
        List<Player> candidates) implements CustomPacketPayload {
    public static final Type<TeamSyncPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(LandClaims.MODID, "team_sync"));

    public record Player(UUID id, String name, boolean online) {
        static final StreamCodec<ByteBuf, Player> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Player::id,
                ByteBufCodecs.STRING_UTF8, Player::name,
                ByteBufCodecs.BOOL, Player::online,
                Player::new);
    }

    public static final StreamCodec<ByteBuf, TeamSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, TeamSyncPayload::team,
            ByteBufCodecs.INT, TeamSyncPayload::color,
            UUIDUtil.STREAM_CODEC, TeamSyncPayload::owner,
            Player.STREAM_CODEC.apply(ByteBufCodecs.list()), TeamSyncPayload::members,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), TeamSyncPayload::invites,
            Player.STREAM_CODEC.apply(ByteBufCodecs.list()), TeamSyncPayload::candidates,
            TeamSyncPayload::new);

    public boolean inTeam() {
        return !team.isEmpty();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
