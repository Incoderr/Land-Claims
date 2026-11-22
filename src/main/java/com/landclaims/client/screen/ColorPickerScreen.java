package com.landclaims.client.screen;

import java.awt.Color;
import java.util.Locale;
import java.util.function.IntConsumer;

import com.landclaims.LandClaims;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Full color picker: a saturation/brightness square, a hue bar, a hex field and preset swatches.
 * Nothing is sent until Apply; Cancel or Esc returns to the parent unchanged.
 */
public final class ColorPickerScreen extends Screen {
    private static final Identifier SV_TEXTURE = Identifier.fromNamespaceAndPath(LandClaims.MODID, "picker_sv");
    private static final Identifier HUE_TEXTURE = Identifier.fromNamespaceAndPath(LandClaims.MODID, "picker_hue");
    private static final int TEX = 128;
    private static final int SQUARE = 120;
    private static final int HUE_W = 14;
    private static final int PANEL_W = 300;
    private static final int PANEL_H = 196;

    private static final int BACKGROUND = 0xC0000000;
    private static final int PANEL = 0xFF1C1F24;
    private static final int PANEL_EDGE = 0xFF2B3038;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int TEXT_DIM = 0xFF9AA0A8;

    static final int[] PRESETS = {
            0x3CC864, 0x2E9E5B, 0x4FC3F7, 0x3F7FE0, 0x8C6CF0, 0xD36CE6,
            0xF06292, 0xE05050, 0xFF8A3D, 0xFFC94D, 0xC0C6CE, 0x6D4C41,
    };

    private final Screen parent;
    private final int original;
    private final IntConsumer onApply;

    private float hue;
    private float saturation;
    private float brightness;
    private int renderedHue = -1;

    private DynamicTexture svTexture;
    private DynamicTexture hueTexture;
    private EditBox hexBox;
    private boolean updatingHex;

    private enum Drag { NONE, SQUARE, HUE }
    private Drag drag = Drag.NONE;

    // Layout.
    private int px, py, sqX, sqY, hueX, sideX;

    public ColorPickerScreen(Screen parent, int initialRgb, IntConsumer onApply) {
        super(Component.translatable("landclaims.picker.title"));
        this.parent = parent;
        this.original = initialRgb & 0xFFFFFF;
        this.onApply = onApply;
        setRgb(original);
    }

    private void setRgb(int rgb) {
        float[] hsb = Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
        // Keep the current hue for greys, where it is undefined, so the square doesn't jump to red.
        if (hsb[1] > 0.001f && hsb[2] > 0.001f) {
            hue = hsb[0];
        }
        saturation = hsb[1];
        brightness = hsb[2];
    }

    private int rgb() {
        return Color.HSBtoRGB(hue, saturation, brightness) & 0xFFFFFF;
    }

    @Override
    protected void init() {
        if (svTexture == null) {
            svTexture = new DynamicTexture(() -> "Land Claims picker", TEX, TEX, true);
            hueTexture = new DynamicTexture(() -> "Land Claims hue", 1, TEX, true);
            minecraft.getTextureManager().register(SV_TEXTURE, svTexture);
            minecraft.getTextureManager().register(HUE_TEXTURE, hueTexture);
            NativeImage hues = hueTexture.getPixels();
            if (hues != null) {
                for (int y = 0; y < TEX; y++) {
                    hues.setPixel(0, y, 0xFF000000 | Color.HSBtoRGB(y / (float) (TEX - 1), 1f, 1f));
                }
                hueTexture.upload();
            }
        }

        px = (width - PANEL_W) / 2;
        py = (height - PANEL_H) / 2;
        sqX = px + 12;
        sqY = py + 26;
        hueX = sqX + SQUARE + 8;
        sideX = hueX + HUE_W + 14;
        int sideW = px + PANEL_W - 12 - sideX;

        hexBox = addRenderableWidget(new EditBox(font, sideX, sqY + 52, sideW, 18, Component.literal("HEX")));
        hexBox.setMaxLength(7);
        updateHexBox();
        hexBox.setResponder(this::onHexTyped);

        int buttonsY = py + PANEL_H - 28;
        int bw = (PANEL_W - 36) / 2;
        addRenderableWidget(Button.builder(Component.translatable("landclaims.picker.apply"), b -> {
            onApply.accept(rgb());
            minecraft.gui.setScreen(parent);
        }).bounds(px + 12, buttonsY, bw, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("landclaims.picker.cancel"), b -> onClose())
                .bounds(px + 24 + bw, buttonsY, bw, 18).build());
    }

    private void updateHexBox() {
        updatingHex = true;
        hexBox.setValue(String.format(Locale.ROOT, "#%06X", rgb()));
        updatingHex = false;
    }

    private void onHexTyped(String value) {
        if (updatingHex) {
            return;
        }
        String hex = value.startsWith("#") ? value.substring(1) : value;
        if (hex.length() == 6) {
            try {
                setRgb(Integer.parseInt(hex, 16));
            } catch (NumberFormatException ignored) {
                // Not a color yet; keep what we have.
            }
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void removed() {
        if (svTexture != null) {
            minecraft.getTextureManager().release(SV_TEXTURE);
            minecraft.getTextureManager().release(HUE_TEXTURE);
            svTexture = null;
            hueTexture = null;
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** The square only depends on the hue, so it is redrawn only when the hue changes. */
    private void refreshSquare() {
        int hueKey = Math.round(hue * 1000);
        if (hueKey == renderedHue || svTexture == null) {
            return;
        }
        renderedHue = hueKey;
        NativeImage pixels = svTexture.getPixels();
        if (pixels == null) {
            return;
        }
        for (int x = 0; x < TEX; x++) {
            for (int y = 0; y < TEX; y++) {
                float s = x / (float) (TEX - 1);
                float v = 1f - y / (float) (TEX - 1);
                pixels.setPixel(x, y, 0xFF000000 | Color.HSBtoRGB(hue, s, v));
            }
        }
        svTexture.upload();
    }

    // ---- Drawing ----

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (parent != null) {
            parent.extractBackground(g, mouseX, mouseY, partialTick);
        }
        g.fill(0, 0, width, height, BACKGROUND);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        refreshSquare();
        g.fill(px - 1, py - 1, px + PANEL_W + 1, py + PANEL_H + 1, PANEL_EDGE);
        g.fill(px, py, px + PANEL_W, py + PANEL_H, PANEL);
        g.text(font, title.copy().withStyle(ChatFormatting.BOLD), px + 12, py + 10, TEXT, false);

        // Saturation/brightness square with a ring at the current position.
        g.fill(sqX - 1, sqY - 1, sqX + SQUARE + 1, sqY + SQUARE + 1, 0xFF000000);
        g.blit(RenderPipelines.GUI_TEXTURED, SV_TEXTURE, sqX, sqY, 0, 0, SQUARE, SQUARE, TEX, TEX, TEX, TEX);
        int cx = sqX + Math.round(saturation * (SQUARE - 1));
        int cy = sqY + Math.round((1 - brightness) * (SQUARE - 1));
        g.outline(cx - 3, cy - 3, 7, 7, 0xFF000000);
        g.outline(cx - 2, cy - 2, 5, 5, 0xFFFFFFFF);

        // Hue bar with a marker.
        g.fill(hueX - 1, sqY - 1, hueX + HUE_W + 1, sqY + SQUARE + 1, 0xFF000000);
        g.blit(RenderPipelines.GUI_TEXTURED, HUE_TEXTURE, hueX, sqY, 0, 0, HUE_W, SQUARE, 1, TEX, 1, TEX);
        int hy = sqY + Math.round(hue * (SQUARE - 1));
        g.fill(hueX - 3, hy - 1, hueX + HUE_W + 3, hy + 2, 0xFF000000);
        g.fill(hueX - 2, hy, hueX + HUE_W + 2, hy + 1, 0xFFFFFFFF);

        // Preview: old color on the left half, new on the right.
        int pw = px + PANEL_W - 12 - sideX;
        g.text(font, Component.translatable("landclaims.picker.preview"), sideX, sqY, TEXT_DIM, false);
        g.fill(sideX - 1, sqY + 11, sideX + pw + 1, sqY + 37, 0xFF000000);
        g.fill(sideX, sqY + 12, sideX + pw / 2, sqY + 36, 0xFF000000 | original);
        g.fill(sideX + pw / 2, sqY + 12, sideX + pw, sqY + 36, 0xFF000000 | rgb());
        g.text(font, "HEX", sideX, sqY + 42, TEXT_DIM, false);

        // Presets, two rows of six.
        int sw = 14;
        int gap = (pw - 6 * sw) / 5;
        for (int i = 0; i < PRESETS.length; i++) {
            int x = sideX + (i % 6) * (sw + gap);
            int y = sqY + 80 + (i / 6) * (sw + 4);
            boolean hover = mouseX >= x && mouseX < x + sw && mouseY >= y && mouseY < y + sw;
            g.fill(x, y, x + sw, y + sw, rgb() == PRESETS[i] ? 0xFFFFFFFF : hover ? 0xFF9AA0A8 : 0xFF000000);
            g.fill(x + 2, y + 2, x + sw - 2, y + sw - 2, 0xFF000000 | PRESETS[i]);
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    // ---- Input ----

    private void pickSquare(double mouseX, double mouseY) {
        saturation = (float) Math.clamp((mouseX - sqX) / (SQUARE - 1), 0, 1);
        brightness = 1f - (float) Math.clamp((mouseY - sqY) / (SQUARE - 1), 0, 1);
        updateHexBox();
    }

    private void pickHue(double mouseY) {
        hue = (float) Math.clamp((mouseY - sqY) / (SQUARE - 1), 0, 1);
        updateHexBox();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
        }
        double x = event.x();
        double y = event.y();
        if (x >= sqX && x < sqX + SQUARE && y >= sqY && y < sqY + SQUARE) {
            drag = Drag.SQUARE;
            pickSquare(x, y);
            return true;
        }
        if (x >= hueX - 2 && x < hueX + HUE_W + 2 && y >= sqY && y < sqY + SQUARE) {
            drag = Drag.HUE;
            pickHue(y);
            return true;
        }
        int pw = px + PANEL_W - 12 - sideX;
        int sw = 14;
        int gap = (pw - 6 * sw) / 5;
        for (int i = 0; i < PRESETS.length; i++) {
            int sx = sideX + (i % 6) * (sw + gap);
            int sy = sqY + 80 + (i / 6) * (sw + 4);
            if (x >= sx && x < sx + sw && y >= sy && y < sy + sw) {
                setRgb(PRESETS[i]);
                updateHexBox();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        switch (drag) {
            case SQUARE -> pickSquare(event.x(), event.y());
            case HUE -> pickHue(event.y());
            default -> {
                return super.mouseDragged(event, dx, dy);
            }
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        drag = Drag.NONE;
        return super.mouseReleased(event);
    }
}
