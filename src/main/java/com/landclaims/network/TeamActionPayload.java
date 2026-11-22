package com.landclaims.network;

import java.util.UUID;

import com.landclaims.LandClaims;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client to server: a button pressed on the team screen. Which fields matter depends on the action:
 * {@code text} for CREATE/JOIN/RENAME, {@code target} for INVITE/KICK/TRANSFER, {@code value} for COLOR.
 */
public record TeamActionPayload(Action action, String text, UUID target, int value) implements CustomPacketPayload {
    public enum Action {
        CREATE, INVITE, JOIN, LEAVE, KICK, TRANSFER, RENAME, COLOR, DISBAND;

        static final StreamCodec<ByteBuf, Action> STREAM_CODEC =
                ByteBufCodecs.VAR_INT.map(i -> values()[Math.clamp(i, 0, values().length - 1)], Action::ordinal);
    }

    private static final UUID NONE = new UUID(0, 0);

    public static final Type<TeamActionPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(LandClaims.MODID, "team_action"));

    public static final StreamCodec<ByteBuf, TeamActionPayload> STREAM_CODEC = StreamCodec.composite(
            Action.STREAM_CODEC, TeamActionPayload::action,
            ByteBufCodecs.stringUtf8(32), TeamActionPayload::text,
            UUIDUtil.STREAM_CODEC, TeamActionPayload::target,
            ByteBufCodecs.INT, TeamActionPayload::value,
            TeamActionPayload::new);

    public static TeamActionPayload of(Action action) {
        return new TeamActionPayload(action, "", NONE, 0);
    }

    public static TeamActionPayload withText(Action action, String text) {
        return new TeamActionPayload(action, text, NONE, 0);
    }

    public static TeamActionPayload withTarget(Action action, UUID target) {
        return new TeamActionPayload(action, "", target, 0);
    }

    public static TeamActionPayload withValue(Action action, int value) {
        return new TeamActionPayload(action, "", NONE, value);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
