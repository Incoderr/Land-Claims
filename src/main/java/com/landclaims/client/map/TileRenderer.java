package com.landclaims.client.map;

import com.landclaims.client.screen.BlockColorCache;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/** Draws one loaded chunk as seen from above: 16x16 ARGB pixels, one per block column. */
final class TileRenderer {
    private static final int MAX_WATER_DEPTH = 24;
    /** How far down we look for the first visible block from the heightmap value. */
    private static final int MAX_SCAN_DOWN = 10;

    private final BlockColorCache colors = new BlockColorCache();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    static LevelChunk loadedChunk(ClientLevel level, int cx, int cz) {
        LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
        return chunk == null || chunk.isEmpty() ? null : chunk;
    }

    /** ARGB pixels of the chunk, or null if the client doesn't have it. */
    int[] render(ClientLevel level, int cx, int cz) {
        LevelChunk chunk = loadedChunk(level, cx, cz);
        if (chunk == null) {
            return null;
        }
        LevelChunk north = loadedChunk(level, cx, cz - 1);
        LevelChunk west = loadedChunk(level, cx - 1, cz);
        int[] heights = new int[256];
        for (int i = 0; i < 256; i++) {
            heights[i] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, i & 15, i >> 4);
        }

        int[] tile = new int[256];
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int h = heights[z * 16 + x];
                int hNorth = z > 0 ? heights[(z - 1) * 16 + x]
                        : north != null ? north.getHeight(Heightmap.Types.WORLD_SURFACE, x, 15) : h;
                int hWest = x > 0 ? heights[z * 16 + x - 1]
                        : west != null ? west.getHeight(Heightmap.Types.WORLD_SURFACE, 15, z) : h;
                tile[z * 16 + x] = column(level, chunk, baseX + x, baseZ + z, h, hNorth, hWest);
            }
        }
        return tile;
    }

    private int column(ClientLevel level, LevelChunk chunk, int worldX, int worldZ, int height, int hNorth, int hWest) {
        int minY = level.getMinY();
        // Start at the heightmap value itself: on the client it can point at the surface block rather than
        // the air above it, and starting one lower would show the dirt under the grass.
        for (int y = height, i = 0; i < MAX_SCAN_DOWN && y >= minY; i++, y--) {
            pos.set(worldX, y, worldZ);
            BlockState state = chunk.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            if (state.getFluidState().is(FluidTags.WATER) && !state.isSolidRender()) {
                return water(level, chunk, worldX, worldZ, y);
            }
            int rgb = colors.colorAt(level, state, pos);
            if (rgb != 0) {
                return shade(rgb, height, hNorth, hWest);
            }
        }
        return 0;
    }

    /** Water lets the bottom show through, more faintly the deeper it is. */
    private int water(ClientLevel level, LevelChunk chunk, int worldX, int worldZ, int surfaceY) {
        pos.set(worldX, surfaceY, worldZ);
        int waterRgb = BiomeColors.getAverageWaterColor(level, pos) | 0xFF000000;
        int depth = 1;
        int floorRgb = 0xFF2A3440;
        for (int y = surfaceY - 1; y >= level.getMinY() && depth < MAX_WATER_DEPTH; y--, depth++) {
            pos.set(worldX, y, worldZ);
            BlockState state = chunk.getBlockState(pos);
            if (!state.getFluidState().is(FluidTags.WATER) || state.isSolidRender()) {
                int rgb = colors.colorAt(level, state, pos);
                if (rgb != 0) {
                    floorRgb = rgb;
                }
                break;
            }
        }
        float waterShare = Mth.clamp(0.5f + depth * 0.045f, 0.5f, 0.9f);
        int mixed = ARGB.srgbLerp(waterShare, floorRgb, waterRgb);
        return ARGB.scaleRGB(mixed, Mth.clamp(1.0f - depth * 0.012f, 0.75f, 1.0f));
    }

    /** Lights slopes facing north-west and darkens the others, so hills read as hills. */
    private static int shade(int rgb, int height, int hNorth, int hWest) {
        int slope = (height - hNorth) + (height - hWest);
        float factor = 1.0f + Mth.clamp(slope, -6, 6) * 0.05f;
        return ARGB.scaleRGB(rgb, Mth.clamp(factor, 0.7f, 1.3f));
    }
}
