package com.landclaims.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.landclaims.ClaimService;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/** Every payload must come out of the network exactly as it went in. */
class PayloadCodecTest {
    private static <T> T roundTrip(StreamCodec<ByteBuf, T> codec, T value) {
        ByteBuf buf = Unpooled.buffer();
        try {
            codec.encode(buf, value);
            T decoded = codec.decode(buf);
            assertEquals(0, buf.readableBytes(), "decoder must read everything the encoder wrote");
            return decoded;
        } finally {
            buf.release();
        }
    }

    @Test
    void claimSync() {
        ClaimSyncPayload payload = new ClaimSyncPayload(Level.NETHER,
                List.of(new ClaimSyncPayload.Entry(new ChunkPos(-5, 12).pack(), "Dev", 0x3CC864, true, true),
                        new ClaimSyncPayload.Entry(new ChunkPos(100, -3).pack(), "Other", 0xFF0000, false, false)),
                "Dev", 7, 100, 1, 25);
        assertEquals(payload, roundTrip(ClaimSyncPayload.STREAM_CODEC, payload));
    }

    @Test
    void claimAction() {
        for (ClaimActionPayload.Action action : ClaimActionPayload.Action.values()) {
            ClaimActionPayload payload = new ClaimActionPayload(action, Level.OVERWORLD,
                    ClaimService.square(new ChunkPos(0, 0), 1));
            assertEquals(payload, roundTrip(ClaimActionPayload.STREAM_CODEC, payload));
        }
    }

    @Test
    void claimActionRejectsOversizedRequests() {
        ClaimActionPayload tooBig = new ClaimActionPayload(ClaimActionPayload.Action.CLAIM, Level.OVERWORLD,
                ClaimService.square(new ChunkPos(0, 0), ClaimService.MAX_RADIUS + 1));
        ByteBuf buf = Unpooled.buffer();
        try {
            boolean failed = false;
            try {
                ClaimActionPayload.STREAM_CODEC.encode(buf, tooBig);
            } catch (RuntimeException e) {
                failed = true;
            }
            assertTrue(failed, "more chunks than the server accepts per request must not be sent");
        } finally {
            buf.release();
        }
    }

    @Test
    void unknownActionIndexIsClamped() {
        ByteBuf buf = Unpooled.buffer();
        try {
            ByteBufCodecs.VAR_INT.encode(buf, 999);
            ClaimActionPayload.Action action = ClaimActionPayload.Action.STREAM_CODEC.decode(buf);
            ClaimActionPayload.Action[] all = ClaimActionPayload.Action.values();
            assertEquals(all[all.length - 1], action);
        } finally {
            buf.release();
        }
    }

    @Test
    void teamSync() {
        UUID owner = UUID.randomUUID();
        TeamSyncPayload payload = new TeamSyncPayload("Dev", 0x123456, owner,
                List.of(new TeamSyncPayload.Player(owner, "Alice", true),
                        new TeamSyncPayload.Player(UUID.randomUUID(), "Bob", false)),
                List.of(),
                List.of(new TeamSyncPayload.Player(UUID.randomUUID(), "Carol", true)));
        TeamSyncPayload decoded = roundTrip(TeamSyncPayload.STREAM_CODEC, payload);
        assertEquals(payload, decoded);
        assertTrue(decoded.inTeam());

        TeamSyncPayload noTeam = new TeamSyncPayload("", -1, owner, List.of(), List.of("Dev", "Other"), List.of());
        assertFalse(roundTrip(TeamSyncPayload.STREAM_CODEC, noTeam).inTeam());
    }

    @Test
    void teamAction() {
        UUID target = UUID.randomUUID();
        List<TeamActionPayload> payloads = List.of(
                TeamActionPayload.of(TeamActionPayload.Action.LEAVE),
                TeamActionPayload.withText(TeamActionPayload.Action.CREATE, "Builders"),
                TeamActionPayload.withTarget(TeamActionPayload.Action.KICK, target),
                TeamActionPayload.withValue(TeamActionPayload.Action.COLOR, 0xABCDEF));
        for (TeamActionPayload payload : payloads) {
            assertEquals(payload, roundTrip(TeamActionPayload.STREAM_CODEC, payload));
        }
    }

    @Test
    void squareCoversTheArea() {
        List<ChunkPos> area = ClaimService.square(new ChunkPos(10, -10), 2);
        assertEquals(25, area.size());
        assertTrue(area.contains(new ChunkPos(8, -12)));
        assertTrue(area.contains(new ChunkPos(12, -8)));
        assertEquals(ClaimService.MAX_CHUNKS_PER_REQUEST,
                ClaimService.square(new ChunkPos(0, 0), ClaimService.MAX_RADIUS).size());
    }
}
