package com.landclaims.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.landclaims.LandClaims;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** All teams and claims of the server. Stored once per world, in the server-wide data storage. */
public final class ClaimData extends SavedData {
    private record ClaimEntry(ResourceKey<Level> dimension, long chunk, UUID team) {
        static final Codec<ClaimEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
                ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(ClaimEntry::dimension),
                Codec.LONG.fieldOf("chunk").forGetter(ClaimEntry::chunk),
                UUIDUtil.CODEC.fieldOf("team").forGetter(ClaimEntry::team)
        ).apply(i, ClaimEntry::new));
    }

    private record NameEntry(UUID player, String name) {
        static final Codec<NameEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("player").forGetter(NameEntry::player),
                Codec.STRING.fieldOf("name").forGetter(NameEntry::name)
        ).apply(i, NameEntry::new));
    }

    public static final Codec<ClaimData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Team.CODEC.listOf().optionalFieldOf("teams", List.of()).forGetter(d -> List.copyOf(d.teams.values())),
            ClaimEntry.CODEC.listOf().optionalFieldOf("claims", List.of()).forGetter(ClaimData::claimEntries),
            NameEntry.CODEC.listOf().optionalFieldOf("names", List.of()).forGetter(ClaimData::nameEntries)
    ).apply(i, ClaimData::new));

    public static final SavedDataType<ClaimData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(LandClaims.MODID, "claims"), ClaimData::new, CODEC);

    private final Map<UUID, Team> teams = new HashMap<>();
    private final Map<UUID, UUID> teamOfPlayer = new HashMap<>();
    private final Map<ResourceKey<Level>, Long2ObjectMap<UUID>> claims = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();

    // Not saved: invites expire on restart, bypass is an admin toggle per session.
    private final Map<UUID, Set<UUID>> invites = new HashMap<>();
    private final Set<UUID> bypass = new HashSet<>();

    /** Set on every change; the server sends fresh claims to clients on the next tick. */
    private boolean syncPending;

    public ClaimData() {
    }

    @Override
    public void setDirty(boolean dirty) {
        super.setDirty(dirty);
        if (dirty) {
            syncPending = true;
        }
    }

    /** Returns true once after any change. */
    public boolean consumeSyncPending() {
        boolean pending = syncPending;
        syncPending = false;
        return pending;
    }

    /** Claims in one dimension, for sending to clients: packed chunk -> team id. */
    public Long2ObjectMap<UUID> claimsInDimension(ResourceKey<Level> dimension) {
        Long2ObjectMap<UUID> map = claims.get(dimension);
        return map == null ? new Long2ObjectOpenHashMap<>() : map;
    }

    private ClaimData(List<Team> teams, List<ClaimEntry> claims, List<NameEntry> names) {
        for (Team team : teams) {
            this.teams.put(team.id(), team);
            team.members().forEach(member -> teamOfPlayer.put(member, team.id()));
        }
        for (ClaimEntry entry : claims) {
            if (this.teams.containsKey(entry.team())) {
                claimsIn(entry.dimension()).put(entry.chunk(), entry.team());
            }
        }
        names.forEach(entry -> this.names.put(entry.player(), entry.name()));
    }

    public static ClaimData get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    private List<ClaimEntry> claimEntries() {
        List<ClaimEntry> result = new ArrayList<>();
        claims.forEach((dimension, map) -> map.long2ObjectEntrySet()
                .forEach(e -> result.add(new ClaimEntry(dimension, e.getLongKey(), e.getValue()))));
        return result;
    }

    private List<NameEntry> nameEntries() {
        return names.entrySet().stream().map(e -> new NameEntry(e.getKey(), e.getValue())).toList();
    }

    private Long2ObjectMap<UUID> claimsIn(ResourceKey<Level> dimension) {
        return claims.computeIfAbsent(dimension, d -> new Long2ObjectOpenHashMap<>());
    }

    // ---- Player names (for offline members in /team info) ----

    public void rememberName(UUID player, String name) {
        if (!name.equals(names.put(player, name))) {
            setDirty();
        }
    }

    public String nameOf(UUID player) {
        return names.getOrDefault(player, player.toString().substring(0, 8));
    }

    public @Nullable UUID playerByName(String name) {
        return names.entrySet().stream()
                .filter(e -> e.getValue().equalsIgnoreCase(name))
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
    }

    // ---- Teams ----

    public static boolean isValidTeamName(String name) {
        return name.matches("[A-Za-z0-9_-]{2,16}");
    }

    public @Nullable Team teamOf(UUID player) {
        UUID teamId = teamOfPlayer.get(player);
        return teamId == null ? null : teams.get(teamId);
    }

    public @Nullable Team team(UUID teamId) {
        return teams.get(teamId);
    }

    public @Nullable Team teamByName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return teams.values().stream()
                .filter(t -> t.name().toLowerCase(Locale.ROOT).equals(lower))
                .findFirst().orElse(null);
    }

    public List<Team> teams() {
        return List.copyOf(teams.values());
    }

    /** Creates a team with the player as owner. The caller checks the name and that the player has no team. */
    public Team createTeam(String name, UUID owner) {
        Team team = new Team(UUID.randomUUID(), name, owner, List.of(owner));
        teams.put(team.id(), team);
        teamOfPlayer.put(owner, team.id());
        invites.remove(owner);
        setDirty();
        return team;
    }

    /** Picks a free team name based on the player's name, for teams created by the first /claim. */
    public String freeTeamName(String base) {
        String name = base.length() > 16 ? base.substring(0, 16) : base;
        for (int n = 2; teamByName(name) != null; n++) {
            String suffix = String.valueOf(n);
            name = base.substring(0, Math.min(base.length(), 16 - suffix.length())) + suffix;
        }
        return name;
    }

    public void invite(Team team, UUID player) {
        invites.computeIfAbsent(player, p -> new HashSet<>()).add(team.id());
    }

    public boolean hasInvite(Team team, UUID player) {
        return invites.getOrDefault(player, Set.of()).contains(team.id());
    }

    public void addMember(Team team, UUID player) {
        team.addMember(player);
        teamOfPlayer.put(player, team.id());
        invites.remove(player);
        setDirty();
    }

    /**
     * Removes a member. If the owner leaves, the next member becomes owner.
     * @return the new owner, or null if ownership did not change
     */
    public @Nullable UUID removeMember(Team team, UUID player) {
        team.removeMember(player);
        teamOfPlayer.remove(player);
        UUID newOwner = null;
        if (team.isOwner(player) && !team.members().isEmpty()) {
            newOwner = team.members().iterator().next();
            team.setOwner(newOwner);
        }
        setDirty();
        return newOwner;
    }

    /** Deletes the team and all its claims. Returns the removed claims so the caller can release chunk loading. */
    public List<ClaimPos> disband(Team team) {
        List<ClaimPos> removed = unclaimAll(team);
        team.members().forEach(teamOfPlayer::remove);
        teams.remove(team.id());
        invites.values().forEach(set -> set.remove(team.id()));
        setDirty();
        return removed;
    }

    // ---- Claims ----

    public @Nullable UUID ownerOf(ResourceKey<Level> dimension, ChunkPos chunk) {
        Long2ObjectMap<UUID> map = claims.get(dimension);
        return map == null ? null : map.get(chunk.pack());
    }

    public @Nullable Team teamAt(ResourceKey<Level> dimension, ChunkPos chunk) {
        UUID owner = ownerOf(dimension, chunk);
        return owner == null ? null : teams.get(owner);
    }

    public void claim(ResourceKey<Level> dimension, ChunkPos chunk, Team team) {
        claimsIn(dimension).put(chunk.pack(), team.id());
        setDirty();
    }

    public void unclaim(ResourceKey<Level> dimension, ChunkPos chunk) {
        Long2ObjectMap<UUID> map = claims.get(dimension);
        if (map != null && map.remove(chunk.pack()) != null) {
            setDirty();
        }
    }

    public List<ClaimPos> claimsOf(Team team) {
        List<ClaimPos> result = new ArrayList<>();
        claims.forEach((dimension, map) -> map.long2ObjectEntrySet().forEach(e -> {
            if (e.getValue().equals(team.id())) {
                result.add(new ClaimPos(dimension, ChunkPos.unpack(e.getLongKey())));
            }
        }));
        return result;
    }

    public int claimCount(Team team) {
        int count = 0;
        for (Long2ObjectMap<UUID> map : claims.values()) {
            for (UUID owner : map.values()) {
                if (owner.equals(team.id())) {
                    count++;
                }
            }
        }
        return count;
    }

    public List<ClaimPos> unclaimAll(Team team) {
        List<ClaimPos> removed = claimsOf(team);
        removed.forEach(pos -> claims.get(pos.dimension()).remove(pos.chunk().pack()));
        if (!removed.isEmpty()) {
            setDirty();
        }
        return removed;
    }

    // ---- Access ----

    /** True if the player may build and interact in this chunk. */
    public boolean canAccess(UUID player, ResourceKey<Level> dimension, ChunkPos chunk) {
        UUID owner = ownerOf(dimension, chunk);
        return owner == null || owner.equals(teamOfPlayer.get(player)) || bypass.contains(player);
    }

    /** Toggles admin bypass. Returns the new state. */
    public boolean toggleBypass(UUID player) {
        if (bypass.remove(player)) {
            return false;
        }
        bypass.add(player);
        return true;
    }

    public record ClaimPos(ResourceKey<Level> dimension, ChunkPos chunk) {
    }
}
