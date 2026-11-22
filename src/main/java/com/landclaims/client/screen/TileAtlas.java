package com.landclaims.client.screen;

import java.util.Arrays;

import com.landclaims.LandClaims;
import com.landclaims.client.map.MapTiles;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

/**
 * A GPU texture holding the visible chunk tiles at a fixed resolution per chunk.
 * Slots wrap around (chunk x maps to slot x mod width), so panning only writes the chunks that came into view,
 * and drawing takes at most four blits, split where the slots wrap.
 */
final class TileAtlas implements AutoCloseable {
    private final Identifier id;
    private final DynamicTexture texture;
    private final int slotsX;
    private final int slotsZ;
    /** Pixels per chunk side: 16 for full detail, smaller for the zoomed-out view. */
    private final int res;
    private final long[] owner;
    private final int[] ownerStamp;
    private boolean dirty;

    TileAtlas(String name, int slotsX, int slotsZ, int res) {
        this.id = Identifier.fromNamespaceAndPath(LandClaims.MODID, "map_" + name);
        this.slotsX = slotsX;
        this.slotsZ = slotsZ;
        this.res = res;
        this.owner = new long[slotsX * slotsZ];
        this.ownerStamp = new int[slotsX * slotsZ];
        Arrays.fill(owner, Long.MIN_VALUE);
        this.texture = new DynamicTexture(() -> "Land Claims " + name, slotsX * res, slotsZ * res, true);
        Minecraft.getInstance().getTextureManager().register(id, texture);
    }

    int slotsX() {
        return slotsX;
    }

    int slotsZ() {
        return slotsZ;
    }

    /** Makes sure the slots for chunks [cx0, cx1] x [cz0, cz1] hold the current tiles. */
    void sync(int cx0, int cz0, int cx1, int cz1) {
        NativeImage pixels = texture.getPixels();
        if (pixels == null) {
            return;
        }
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                int sx = Math.floorMod(cx, slotsX);
                int sz = Math.floorMod(cz, slotsZ);
                int slot = sz * slotsX + sx;
                long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
                int stamp = MapTiles.stamp(cx, cz);
                if (owner[slot] == key && ownerStamp[slot] == stamp) {
                    continue;
                }
                owner[slot] = key;
                ownerStamp[slot] = stamp;
                write(pixels, MapTiles.get(cx, cz), sx * res, sz * res);
                dirty = true;
            }
        }
        if (dirty) {
            texture.upload();
            dirty = false;
        }
    }

    private void write(NativeImage pixels, int[] tile, int px, int pz) {
        if (tile == null) {
            pixels.fillRect(px, pz, res, res, 0);
            return;
        }
        if (res == 16) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    pixels.setPixel(px + x, pz + z, tile[z * 16 + x]);
                }
            }
            return;
        }
        // Average each block of blocks into one pixel.
        int step = 16 / res;
        for (int z = 0; z < res; z++) {
            for (int x = 0; x < res; x++) {
                int r = 0, g = 0, b = 0, a = 0, n = 0;
                for (int dz = 0; dz < step; dz++) {
                    for (int dx = 0; dx < step; dx++) {
                        int argb = tile[(z * step + dz) * 16 + x * step + dx];
                        if (ARGB.alpha(argb) == 0) {
                            continue;
                        }
                        r += ARGB.red(argb);
                        g += ARGB.green(argb);
                        b += ARGB.blue(argb);
                        a += ARGB.alpha(argb);
                        n++;
                    }
                }
                pixels.setPixel(px + x, pz + z, n == 0 ? 0 : ARGB.color(a / n, r / n, g / n, b / n));
            }
        }
    }

    /**
     * Draws chunks [cx0, cx1] x [cz0, cz1]. {@code toScreenX/Z} turn a block coordinate into a screen coordinate.
     */
    void draw(GuiGraphicsExtractor g, int cx0, int cz0, int cx1, int cz1,
            java.util.function.LongToIntFunction toScreenX, java.util.function.LongToIntFunction toScreenZ) {
        int texW = slotsX * res;
        int texH = slotsZ * res;
        for (int ax = cx0; ax <= cx1; ) {
            int bx = Math.min(cx1, ax + (slotsX - 1 - Math.floorMod(ax, slotsX)));
            for (int az = cz0; az <= cz1; ) {
                int bz = Math.min(cz1, az + (slotsZ - 1 - Math.floorMod(az, slotsZ)));
                int x0 = toScreenX.applyAsInt(ax * 16L);
                int x1 = toScreenX.applyAsInt((bx + 1) * 16L);
                int y0 = toScreenZ.applyAsInt(az * 16L);
                int y1 = toScreenZ.applyAsInt((bz + 1) * 16L);
                if (x1 > x0 && y1 > y0) {
                    g.blit(RenderPipelines.GUI_TEXTURED, id, x0, y0,
                            Math.floorMod(ax, slotsX) * res, Math.floorMod(az, slotsZ) * res,
                            x1 - x0, y1 - y0, (bx - ax + 1) * res, (bz - az + 1) * res, texW, texH);
                }
                az = bz + 1;
            }
            ax = bx + 1;
        }
    }

    @Override
    public void close() {
        Minecraft.getInstance().getTextureManager().release(id);
    }
}
