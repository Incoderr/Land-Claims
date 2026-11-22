package com.landclaims.client.screen;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/**
 * The color a block shows from above, like on JourneyMap: the average color of the texture on its top face,
 * tinted by the biome where the game tints it (grass, leaves, vines...). Falls back to the vanilla map color.
 */
public final class BlockColorCache {
    /** Average texture color, and which tint layer applies (-1 for none). */
    private record Look(int rgb, int tintIndex) {
    }

    /** Cached for blocks with nothing to draw, so we don't retry them for every pixel. */
    private static final Look INVISIBLE = new Look(0, -1);

    private final Map<BlockState, Look> looks = new IdentityHashMap<>();
    private final RandomSource random = RandomSource.create(42L);
    private final List<BlockStateModelPart> parts = new ArrayList<>();

    /** Opaque RGB (0xFFrrggbb) of the block at pos, or 0 if it has no color worth drawing. */
    public int colorAt(BlockAndTintGetter level, BlockState state, BlockPos pos) {
        Look look = looks.computeIfAbsent(state, this::computeLook);
        if (look == INVISIBLE) {
            return 0;
        }
        int rgb = look.rgb();
        if (look.tintIndex() >= 0) {
            BlockTintSource tint = Minecraft.getInstance().getBlockColors().getTintSource(state, look.tintIndex());
            if (tint != null) {
                rgb = ARGB.multiply(rgb | 0xFF000000, tint.colorInWorld(state, level, pos) | 0xFF000000);
            }
        }
        return rgb | 0xFF000000;
    }

    private Look computeLook(BlockState state) {
        try {
            BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
            parts.clear();
            random.setSeed(42L);
            model.collectParts(random, parts);
            BakedQuad quad = topQuad(parts);
            if (quad != null) {
                int average = averageColor(quad.materialInfo().sprite());
                if (average != 0) {
                    return new Look(average & 0xFFFFFF, quad.materialInfo().tintIndex());
                }
            }
        } catch (RuntimeException ignored) {
            // A modded model we can't read: use the map color below.
        }
        try {
            MapColor mapColor = state.getMapColor(null, BlockPos.ZERO);
            return mapColor == MapColor.NONE ? INVISIBLE : new Look(mapColor.col, -1);
        } catch (RuntimeException e) {
            return INVISIBLE;
        }
    }

    /** The quad facing up; for blocks without one (flowers, torches), any quad. */
    private static BakedQuad topQuad(List<BlockStateModelPart> parts) {
        BakedQuad any = null;
        for (BlockStateModelPart part : parts) {
            for (BakedQuad quad : part.getQuads(Direction.UP)) {
                return quad;
            }
            for (BakedQuad quad : part.getQuads(null)) {
                if (quad.direction() == Direction.UP) {
                    return quad;
                }
                if (any == null) {
                    any = quad;
                }
            }
        }
        return any;
    }

    /** Average of the sprite's visible pixels (first animation frame), or 0 if it is fully transparent. */
    private static int averageColor(TextureAtlasSprite sprite) {
        SpriteContents contents = sprite.contents();
        NativeImage image = contents.getOriginalImage();
        int w = Math.min(contents.width(), image.getWidth());
        int h = Math.min(contents.height(), image.getHeight());
        long r = 0, g = 0, b = 0, count = 0;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int argb = image.getPixel(x, y);
                int alpha = ARGB.alpha(argb);
                if (alpha < 32) {
                    continue;
                }
                r += (long) ARGB.red(argb) * alpha;
                g += (long) ARGB.green(argb) * alpha;
                b += (long) ARGB.blue(argb) * alpha;
                count += alpha;
            }
        }
        if (count == 0) {
            return 0;
        }
        return ARGB.color(255, (int) (r / count), (int) (g / count), (int) (b / count));
    }
}
