package com.landclaims.network;

import java.util.List;

import com.landclaims.ClaimService;
import com.landclaims.LandClaims;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/** Client to server: an action on chunks picked on the map. The server checks everything again. */
public record ClaimActionPayload(Action action, ResourceKey<Level> dimension, List<ChunkPos> chunks)
        implements CustomPacketPayload {
    public enum Action {
        CLAIM, UNCLAIM, FORCE_LOAD, STOP_FORCE_LOAD;

        static final StreamCodec<ByteBuf, Action> STREAM_CODEC =
                ByteBufCodecs.VAR_INT.map(i -> values()[Math.clamp(i, 0, values().length - 1)], Action::ordinal);
    }

    public static final Type<ClaimActionPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(LandClaims.MODID, "claim_action"));

    public static final StreamCodec<ByteBuf, ClaimActionPayload> STREAM_CODEC = StreamCodec.composite(
            Action.STREAM_CODEC, ClaimActionPayload::action,
            ResourceKey.streamCodec(Registries.DIMENSION), ClaimActionPayload::dimension,
            ChunkPos.STREAM_CODEC.apply(ByteBufCodecs.list(ClaimService.MAX_CHUNKS_PER_REQUEST)), ClaimActionPayload::chunks,
            ClaimActionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
