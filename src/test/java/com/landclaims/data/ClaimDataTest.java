package com.landclaims.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

class ClaimDataTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final ChunkPos HOME = new ChunkPos(3, -7);
    private static final ChunkPos NEXT = new ChunkPos(4, -7);

    private ClaimData data;

    @BeforeEach
    void setUp() {
        data = new ClaimData();
    }

    // ---- Teams ----

    @Test
    void teamNamesAreValidatedAndUnique() {
        assertTrue(ClaimData.isValidTeamName("Dev_2-x"));
        assertFalse(ClaimData.isValidTeamName("a"));
        assertFalse(ClaimData.isValidTeamName("way_too_long_team_name"));
        assertFalse(ClaimData.isValidTeamName("пробел"));
        assertFalse(ClaimData.isValidTeamName("two words"));

        data.createTeam("Dev", ALICE);
        assertNotNull(data.teamByName("dev"), "lookup ignores case");
        assertEquals("Dev2", data.freeTeamName("Dev"));
        assertEquals("Dev", data.freeTeamName("Dev").substring(0, 3));
    }

    @Test
    void freeTeamNameStaysWithinSixteenCharacters() {
        data.createTeam("ABCDEFGHIJKLMNOP", ALICE);
        String name = data.freeTeamName("ABCDEFGHIJKLMNOP");
        assertEquals(16, name.length());
        assertNull(data.teamByName(name));
    }

    @Test
    void invitesLetPlayersJoin() {
        Team team = data.createTeam("Dev", ALICE);
        assertFalse(data.hasInvite(team, BOB));

        data.invite(team, BOB);
        assertTrue(data.hasInvite(team, BOB));
        assertEquals(List.of(team), data.invitesFor(BOB));

        data.addMember(team, BOB);
        assertSame(team, data.teamOf(BOB));
        assertTrue(data.invitesFor(BOB).isEmpty(), "joining clears invites");
    }

    @Test
    void ownerLeavingHandsOverOwnership() {
        Team team = data.createTeam("Dev", ALICE);
        data.addMember(team, BOB);

        UUID newOwner = data.removeMember(team, ALICE);
        assertEquals(BOB, newOwner);
        assertTrue(team.isOwner(BOB));
        assertNull(data.teamOf(ALICE));
    }

    @Test
    void memberLeavingKeepsOwner() {
        Team team = data.createTeam("Dev", ALICE);
        data.addMember(team, BOB);

        assertNull(data.removeMember(team, BOB));
        assertTrue(team.isOwner(ALICE));
    }

    @Test
    void transferRenameAndColor() {
        Team team = data.createTeam("Dev", ALICE);
        data.addMember(team, BOB);

        data.transferOwnership(team, BOB);
        assertTrue(team.isOwner(BOB));

        data.rename(team, "Builders");
        assertSame(team, data.teamByName("builders"));
        assertNull(data.teamByName("Dev"));

        assertEquals(Team.NO_COLOR, team.color());
        data.setColor(team, 0x123456);
        assertEquals(0x123456, team.color());
    }

    // ---- Claims and access ----

    @Test
    void claimsControlAccess() {
        Team team = data.createTeam("Dev", ALICE);
        data.addMember(team, BOB);

        assertTrue(data.canAccess(CAROL, Level.OVERWORLD, HOME), "unclaimed land is open");

        data.claim(Level.OVERWORLD, HOME, team);
        assertSame(team, data.teamAt(Level.OVERWORLD, HOME));
        assertTrue(data.canAccess(ALICE, Level.OVERWORLD, HOME));
        assertTrue(data.canAccess(BOB, Level.OVERWORLD, HOME));
        assertFalse(data.canAccess(CAROL, Level.OVERWORLD, HOME));
        assertTrue(data.canAccess(CAROL, Level.NETHER, HOME), "claims are per dimension");

        assertTrue(data.toggleBypass(CAROL));
        assertTrue(data.canAccess(CAROL, Level.OVERWORLD, HOME));
        assertFalse(data.toggleBypass(CAROL));
        assertFalse(data.canAccess(CAROL, Level.OVERWORLD, HOME));
    }

    @Test
    void claimCountsAcrossDimensions() {
        Team team = data.createTeam("Dev", ALICE);
        data.claim(Level.OVERWORLD, HOME, team);
        data.claim(Level.OVERWORLD, NEXT, team);
        data.claim(Level.NETHER, HOME, team);

        assertEquals(3, data.claimCount(team));
        assertEquals(3, data.claimsOf(team).size());

        data.unclaim(Level.OVERWORLD, NEXT);
        assertEquals(2, data.claimCount(team));
        assertNull(data.teamAt(Level.OVERWORLD, NEXT));
    }

    // ---- Force loading ----

    @Test
    void forceLoadingFollowsClaims() {
        Team team = data.createTeam("Dev", ALICE);
        data.claim(Level.OVERWORLD, HOME, team);
        data.claim(Level.OVERWORLD, NEXT, team);

        data.setForced(Level.OVERWORLD, HOME, true);
        data.setForced(Level.OVERWORLD, NEXT, true);
        assertEquals(2, data.forcedCount(team));

        data.unclaim(Level.OVERWORLD, NEXT);
        assertFalse(data.isForced(Level.OVERWORLD, NEXT.pack()), "unclaiming stops force loading");
        assertEquals(1, data.forcedCount(team));

        data.setForced(Level.OVERWORLD, HOME, false);
        assertEquals(0, data.forcedCount(team));
    }

    @Test
    void disbandRemovesEverything() {
        Team team = data.createTeam("Dev", ALICE);
        data.addMember(team, BOB);
        data.claim(Level.OVERWORLD, HOME, team);
        data.setForced(Level.OVERWORLD, HOME, true);

        List<ClaimData.ClaimPos> removed = data.disband(team);

        assertEquals(1, removed.size());
        assertNull(data.teamOf(ALICE));
        assertNull(data.teamOf(BOB));
        assertNull(data.teamAt(Level.OVERWORLD, HOME));
        assertFalse(data.isForced(Level.OVERWORLD, HOME.pack()));
        assertTrue(data.teams().isEmpty());
    }

    @Test
    void forEachForcedReportsOwners() {
        Team team = data.createTeam("Dev", ALICE);
        data.claim(Level.OVERWORLD, HOME, team);
        data.setForced(Level.OVERWORLD, HOME, true);

        int[] seen = {0};
        data.forEachForced((dimension, chunk, owner) -> {
            assertEquals(Level.OVERWORLD, dimension);
            assertEquals(HOME.pack(), chunk);
            assertSame(team, owner);
            seen[0]++;
        });
        assertEquals(1, seen[0]);
    }

    // ---- Sync flag ----

    @Test
    void changesRequestASync() {
        data.consumeSyncPending();
        Team team = data.createTeam("Dev", ALICE);
        assertTrue(data.consumeSyncPending());
        assertFalse(data.consumeSyncPending(), "flag resets after reading");

        data.invite(team, BOB);
        assertTrue(data.consumeSyncPending(), "invites are not saved but still sync");
    }

    // ---- Saving ----

    @Test
    void survivesSaveAndLoad() {
        Team team = data.createTeam("Dev", ALICE);
        data.addMember(team, BOB);
        data.setColor(team, 0xABCDEF);
        data.rememberName(ALICE, "Alice");
        data.rememberName(BOB, "Bob");
        data.claim(Level.OVERWORLD, HOME, team);
        data.claim(Level.NETHER, NEXT, team);
        data.setForced(Level.OVERWORLD, HOME, true);

        Tag saved = ClaimData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
        ClaimData loaded = ClaimData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();

        Team loadedTeam = loaded.teamOf(BOB);
        assertNotNull(loadedTeam);
        assertEquals("Dev", loadedTeam.name());
        assertTrue(loadedTeam.isOwner(ALICE));
        assertEquals(0xABCDEF, loadedTeam.color());
        assertEquals("Bob", loaded.nameOf(BOB));
        assertEquals(BOB, loaded.playerByName("bob"));
        assertSame(loadedTeam, loaded.teamAt(Level.OVERWORLD, HOME));
        assertSame(loadedTeam, loaded.teamAt(Level.NETHER, NEXT));
        assertTrue(loaded.isForced(Level.OVERWORLD, HOME.pack()));
        assertFalse(loaded.isForced(Level.NETHER, NEXT.pack()));
    }

    /** Worlds saved before force loading and team colors existed must still load. */
    @Test
    void loadsSavesWithoutNewerFields() {
        Team team = data.createTeam("Dev", ALICE);
        data.claim(Level.OVERWORLD, HOME, team);
        JsonObject json = ClaimData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow().getAsJsonObject();
        for (JsonElement t : json.getAsJsonArray("teams")) {
            t.getAsJsonObject().remove("color");
        }
        for (JsonElement c : json.getAsJsonArray("claims")) {
            c.getAsJsonObject().remove("forced");
        }

        ClaimData loaded = ClaimData.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();

        Team loadedTeam = loaded.teamOf(ALICE);
        assertNotNull(loadedTeam);
        assertEquals(Team.NO_COLOR, loadedTeam.color());
        assertSame(loadedTeam, loaded.teamAt(Level.OVERWORLD, HOME));
        assertFalse(loaded.isForced(Level.OVERWORLD, HOME.pack()));
    }

    @Test
    void claimsOfMissingTeamsAreDroppedOnLoad() {
        Team team = data.createTeam("Dev", ALICE);
        data.claim(Level.OVERWORLD, HOME, team);
        JsonObject json = ClaimData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow().getAsJsonObject();
        json.remove("teams");

        ClaimData loaded = ClaimData.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();

        assertNull(loaded.ownerOf(Level.OVERWORLD, HOME));
    }
}
