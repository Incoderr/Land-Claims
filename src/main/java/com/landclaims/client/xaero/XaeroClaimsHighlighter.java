package com.landclaims.client.xaero;

import java.util.List;

import com.landclaims.client.ClientClaims;
import com.landclaims.network.ClaimSyncPayload;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import xaero.map.highlight.ChunkHighlighter;

/**
 * Colors claimed chunks on Xaero's World Map (and the minimap, which reuses the world map's highlighters).
 * Registered by {@link com.landclaims.mixin.xaero.WorldMapSessionMixin}; only loaded when Xaero's World Map is present.
 */
public final class XaeroClaimsHighlighter extends ChunkHighlighter {
    private static final int FILL_ALPHA = 0x55;
    private static final int BORDER_ALPHA = 0xC0;

    /** Per Xaero region (32x32 chunks): a hash of its claims, so Xaero knows when to redraw it. */
    private final Long2IntMap regionHashes = new Long2IntOpenHashMap();
    private int indexedVersion = -1;

    public XaeroClaimsHighlighter() {
        // true: also draw over parts of the map the player hasn't explored yet.
        super(true);
    }

    private boolean ours(ResourceKey<Level> dimension) {
        return dimension.equals(ClientClaims.dimension());
    }

    private void reindexIfStale() {
        if (indexedVersion == ClientClaims.version()) {
            return;
        }
        indexedVersion = ClientClaims.version();
        regionHashes.clear();
        for (ClaimSyncPayload.Entry entry : ClientClaims.claims().values()) {
            ChunkPos chunk = ChunkPos.unpack(entry.chunk());
            long region = ChunkPos.pack(chunk.x() >> 5, chunk.z() >> 5);
            int contribution = Long.hashCode(entry.chunk()) * 31 + entry.color();
            regionHashes.put(region, regionHashes.getOrDefault(region, 17) * 31 + contribution);
        }
    }

    @Override
    public boolean regionHasHighlights(ResourceKey<Level> dimension, int regionX, int regionZ) {
        if (!ours(dimension)) {
            return false;
        }
        reindexIfStale();
        return regionHashes.containsKey(ChunkPos.pack(regionX, regionZ));
    }

    @Override
    public int calculateRegionHash(ResourceKey<Level> dimension, int regionX, int regionZ) {
        if (!ours(dimension)) {
            return 0;
        }
        reindexIfStale();
        return regionHashes.getOrDefault(ChunkPos.pack(regionX, regionZ), 0);
    }

    @Override
    public boolean chunkIsHighlit(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return ours(dimension) && ClientClaims.at(chunkX, chunkZ) != null;
    }

    /** Xaero wants RGBA: center, then the top, right, bottom and left edges. */
    @Override
    protected int[] getColors(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        ClaimSyncPayload.Entry entry = ours(dimension) ? ClientClaims.at(chunkX, chunkZ) : null;
        if (entry == null) {
            return null;
        }
        int rgb = entry.color() & 0xFFFFFF;
        int fill = rgb << 8 | FILL_ALPHA;
        int border = rgb << 8 | BORDER_ALPHA;
        resultStore[0] = fill;
        resultStore[1] = sameTeam(entry, chunkX, chunkZ - 1) ? fill : border;
        resultStore[2] = sameTeam(entry, chunkX + 1, chunkZ) ? fill : border;
        resultStore[3] = sameTeam(entry, chunkX, chunkZ + 1) ? fill : border;
        resultStore[4] = sameTeam(entry, chunkX - 1, chunkZ) ? fill : border;
        return resultStore;
    }

    private static boolean sameTeam(ClaimSyncPayload.Entry entry, int chunkX, int chunkZ) {
        ClaimSyncPayload.Entry other = ClientClaims.at(chunkX, chunkZ);
        return other != null && other.team().equals(entry.team());
    }

    private Component tooltip(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        ClaimSyncPayload.Entry entry = ours(dimension) ? ClientClaims.at(chunkX, chunkZ) : null;
        return entry == null ? null : Component.translatable("landclaims.screen.owner", entry.team());
    }

    @Override
    public Component getChunkHighlightSubtleTooltip(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return tooltip(dimension, chunkX, chunkZ);
    }

    @Override
    public Component getChunkHighlightBluntTooltip(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return tooltip(dimension, chunkX, chunkZ);
    }

    @Override
    public void addMinimapBlockHighlightTooltips(List<Component> list, ResourceKey<Level> dimension,
            int blockX, int blockZ, int width) {
        Component line = tooltip(dimension, blockX >> 4, blockZ >> 4);
        if (line != null) {
            list.add(line);
        }
    }
}
