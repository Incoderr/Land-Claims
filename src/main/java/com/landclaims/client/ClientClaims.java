package com.landclaims.client;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jspecify.annotations.Nullable;

import com.landclaims.network.ClaimSyncPayload;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** The client's copy of the claims in the current dimension, as last sent by the server. */
public final class ClientClaims {
    private static @Nullable ResourceKey<Level> dimension;
    private static Long2ObjectMap<ClaimSyncPayload.Entry> claims = Long2ObjectMaps.emptyMap();
    private static String ownTeam = "";
    private static int ownClaimCount;
    private static int claimLimit;
    private static int ownForcedCount;
    private static int forceLimit;
    /** Increases on every update, so map integrations can tell their cached data is stale. */
    private static int version;
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private ClientClaims() {
    }

    /** False when the server doesn't have Land Claims (nothing was ever synced). */
    public static boolean available() {
        return dimension != null;
    }

    public static @Nullable ResourceKey<Level> dimension() {
        return dimension;
    }

    public static Long2ObjectMap<ClaimSyncPayload.Entry> claims() {
        return Long2ObjectMaps.unmodifiable(claims);
    }

    public static ClaimSyncPayload.@Nullable Entry at(int chunkX, int chunkZ) {
        return claims.get(ChunkPos.pack(chunkX, chunkZ));
    }

    public static String ownTeam() {
        return ownTeam;
    }

    public static int ownClaimCount() {
        return ownClaimCount;
    }

    public static int claimLimit() {
        return claimLimit;
    }

    public static int ownForcedCount() {
        return ownForcedCount;
    }

    public static int forceLimit() {
        return forceLimit;
    }

    public static int version() {
        return version;
    }

    /** Called on the client thread whenever the claims change. */
    public static void addListener(Runnable listener) {
        listeners.add(listener);
    }

    static void handleSync(ClaimSyncPayload payload, IPayloadContext context) {
        Long2ObjectMap<ClaimSyncPayload.Entry> map = new Long2ObjectOpenHashMap<>(payload.claims().size());
        payload.claims().forEach(entry -> map.put(entry.chunk(), entry));
        dimension = payload.dimension();
        claims = map;
        ownTeam = payload.ownTeam();
        ownClaimCount = payload.ownClaimCount();
        claimLimit = payload.claimLimit();
        ownForcedCount = payload.ownForcedCount();
        forceLimit = payload.forceLimit();
        version++;
        listeners.forEach(Runnable::run);
    }

    static void clear() {
        dimension = null;
        claims = Long2ObjectMaps.emptyMap();
        ownTeam = "";
        ownClaimCount = 0;
        claimLimit = 0;
        ownForcedCount = 0;
        forceLimit = 0;
        version++;
        listeners.forEach(Runnable::run);
    }
}
