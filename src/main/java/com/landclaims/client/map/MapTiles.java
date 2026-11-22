package com.landclaims.client.map;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Every chunk the player has seen this session, rendered from above. Chunks are captured in the background
 * while the player moves, so zooming out on the claim screen shows the explored area, not just loaded chunks.
 * Lives only in memory and is cleared on logout or dimension change.
 */
public final class MapTiles {
    /** Upper bound on stored tiles (about 1 KB each); the ones farthest from the player go first. */
    private static final int MAX_TILES = 40_000;
    /** A loaded chunk is redrawn after this long, to pick up changes in the world. */
    private static final long REFRESH_MS = 20_000;
    /** Chunks drawn per client tick in the background, so walking around never costs a frame. */
    private static final int BACKGROUND_PER_TICK = 3;

    private static final TileRenderer renderer = new TileRenderer();
    private static final Long2ObjectMap<int[]> tiles = new Long2ObjectOpenHashMap<>();
    private static final Long2LongMap renderedAt = new Long2LongOpenHashMap();
    /** Bumped whenever a tile changes, so textures know which slots to rewrite. */
    private static final Long2IntMap stamps = new Long2IntOpenHashMap();
    private static int nextStamp = 1;
    private static @Nullable ResourceKey<Level> dimension;

    /** Position of the background scan in the square around the player (row by row). */
    private static int scanIndex;

    private MapTiles() {
    }

    public static int @Nullable [] get(int cx, int cz) {
        return tiles.get(ChunkPos.pack(cx, cz));
    }

    /** 0 if there is no tile. Changes every time the tile is redrawn. */
    public static int stamp(int cx, int cz) {
        return stamps.get(ChunkPos.pack(cx, cz));
    }

    public static void clear() {
        tiles.clear();
        renderedAt.clear();
        stamps.clear();
        dimension = null;
        scanIndex = 0;
    }

    private static @Nullable ClientLevel level() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        if (!level.dimension().equals(dimension)) {
            clear();
            dimension = level.dimension();
        }
        return level;
    }

    /**
     * Renders the chunk if it is loaded and missing or stale. Returns true if it did work,
     * so callers can keep to a per-frame budget.
     */
    public static boolean refresh(int cx, int cz, long now) {
        ClientLevel level = level();
        if (level == null) {
            return false;
        }
        long key = ChunkPos.pack(cx, cz);
        if (tiles.containsKey(key) && now - renderedAt.get(key) < REFRESH_MS) {
            return false;
        }
        if (TileRenderer.loadedChunk(level, cx, cz) == null) {
            return false;
        }
        int[] tile = renderer.render(level, cx, cz);
        if (tile == null) {
            return false;
        }
        tiles.put(key, tile);
        renderedAt.put(key, now);
        stamps.put(key, nextStamp++);
        if (tiles.size() > MAX_TILES) {
            trim(cx, cz);
        }
        return true;
    }

    /** Called every client tick: walks the square around the player and draws a few loaded chunks. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = level();
        if (level == null || mc.player == null) {
            return;
        }
        int radius = mc.options.getEffectiveRenderDistance() + 1;
        int side = radius * 2 + 1;
        int total = side * side;
        ChunkPos center = mc.player.chunkPosition();
        long now = System.currentTimeMillis();
        int done = 0;
        // Look at up to a quarter of the area per tick; most chunks are fresh and are skipped instantly.
        for (int checked = 0; checked < total / 4 + 1 && done < BACKGROUND_PER_TICK; checked++) {
            scanIndex = (scanIndex + 1) % total;
            int dx = scanIndex % side - radius;
            int dz = scanIndex / side - radius;
            if (refresh(center.x() + dx, center.z() + dz, now)) {
                done++;
            }
        }
    }

    private static void trim(int cx, int cz) {
        // Drop the farthest quarter in one go, so this rarely runs.
        long[] keys = tiles.keySet().toLongArray();
        long[] distances = new long[keys.length];
        for (int i = 0; i < keys.length; i++) {
            ChunkPos c = ChunkPos.unpack(keys[i]);
            long dx = c.x() - cx;
            long dz = c.z() - cz;
            distances[i] = dx * dx + dz * dz;
        }
        long[] sorted = distances.clone();
        java.util.Arrays.sort(sorted);
        long cutoff = sorted[sorted.length * 3 / 4];
        for (int i = 0; i < keys.length; i++) {
            if (distances[i] > cutoff) {
                tiles.remove(keys[i]);
                renderedAt.remove(keys[i]);
                stamps.remove(keys[i]);
            }
        }
    }
}
