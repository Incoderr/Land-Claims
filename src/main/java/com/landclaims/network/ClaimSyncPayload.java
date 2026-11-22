package com.landclaims.network;

import java.util.List;

import com.landclaims.LandClaims;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Server to client: every claim in the player's current dimension, plus the player's team summary.
 * Replaces what the client had.
 *
 * @param ownTeam       the receiver's team name, or empty if they have none
 * @param ownClaimCount claims of the receiver's team in all dimensions
 * @param claimLimit    the server's claim limit per team
 * @param ownForcedCount force-loaded chunks of the receiver's team in all dimensions
 * @param forceLimit     the server's force-load limit per team
 */
public record ClaimSyncPayload(ResourceKey<Level> dimension, List<Entry> claims,
        String ownTeam, int ownClaimCount, int claimLimit, int ownForcedCount, int forceLimit)
        implements CustomPacketPayload {
    public static final Type<ClaimSyncPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(LandClaims.MODID, "claim_sync"));

    /**
     * One claimed chunk. {@code own} is true if it belongs to the receiving player's team,
     * {@code forced} if its team keeps it loaded.
     */
    public record Entry(long chunk, String team, int color, boolean own, boolean forced) {
        static final StreamCodec<ByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.LONG, Entry::chunk,
                ByteBufCodecs.STRING_UTF8, Entry::team,
                ByteBufCodecs.INT, Entry::color,
                ByteBufCodecs.BOOL, Entry::own,
                ByteBufCodecs.BOOL, Entry::forced,
                Entry::new);
    }

    public static final StreamCodec<ByteBuf, ClaimSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ResourceKey.streamCodec(Registries.DIMENSION), ClaimSyncPayload::dimension,
            Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), ClaimSyncPayload::claims,
            ByteBufCodecs.STRING_UTF8, ClaimSyncPayload::ownTeam,
            ByteBufCodecs.VAR_INT, ClaimSyncPayload::ownClaimCount,
            ByteBufCodecs.VAR_INT, ClaimSyncPayload::claimLimit,
            ByteBufCodecs.VAR_INT, ClaimSyncPayload::ownForcedCount,
            ByteBufCodecs.VAR_INT, ClaimSyncPayload::forceLimit,
            ClaimSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
