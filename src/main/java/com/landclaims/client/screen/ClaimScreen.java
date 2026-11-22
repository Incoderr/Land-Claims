package com.landclaims.client.screen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.landclaims.ClaimService;
import com.landclaims.client.ClientClaims;
import com.landclaims.client.ClientEvents;
import com.landclaims.client.map.MapTiles;
import com.landclaims.network.ClaimActionPayload;
import com.landclaims.network.ClaimSyncPayload;
import com.mojang.blaze3d.platform.InputConstants;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Top-down map for claiming. Left-drag claims, right-drag unclaims, the wheel zooms around the cursor,
 * WASD/arrows or middle-drag pan, Space flies back to the player. All movement is animated.
 */
public final class ClaimScreen extends Screen {
    /** Pixels per block. 0.25 is 4 px per chunk, 3 is 48 px per chunk. */
    private static final double MIN_SCALE = 0.25;
    private static final double MAX_SCALE = 3.0;
    /** Below this scale the low-resolution atlas is used (4 px per chunk). */
    private static final double DETAIL_SCALE = 0.75;
    private static final double ZOOM_STEP = 1.2;
    /** Pan speed with keys, in screen pixels per second. */
    private static final double KEY_PAN_SPEED = 420;
    /** How quickly animations catch up: higher is snappier. */
    private static final double SMOOTHING = 14;
    /** Tiles rendered per frame while the screen is open (on top of the background capture). */
    private static final int TILES_PER_FRAME = 16;

    private static final int HEADER = 30;
    private static final int FOOTER = 22;

    private static final int BACKGROUND = 0xFF15171B;
    private static final int BACKGROUND_GRID = 0xFF20242A;
    private static final int BAR = 0xE60D0F12;
    private static final int BAR_EDGE = 0xFF2B3038;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int TEXT_DIM = 0xFF9AA0A8;
    private static final int OWN = 0xFF5BE37D;

    // View: the block at the center of the map and pixels per block, plus animation targets.
    private double centerX;
    private double centerZ;
    private double scale = 1.0;
    private double targetScale = 1.0;
    private double zoomAnchorScreenX;
    private double zoomAnchorScreenY;
    private double zoomAnchorBlockX;
    private double zoomAnchorBlockZ;
    private boolean flying;
    private double flyTargetX;
    private double flyTargetZ;
    private long lastFrameNanos;
    private final Set<Integer> heldKeys = new HashSet<>();
    private boolean panning;

    // Map area on screen.
    private int mapX0, mapY0, mapX1, mapY1;

    private TileAtlas detail;
    private TileAtlas overview;

    // Drag selection: the chunk where the drag started, the button, and the chunk under the mouse now.
    private boolean selecting;
    private ClaimActionPayload.Action selAction = ClaimActionPayload.Action.CLAIM;
    private int selStartX, selStartZ, selEndX, selEndZ;

    private boolean initialized;

    public ClaimScreen() {
        super(Component.translatable("landclaims.screen.title"));
    }

    @Override
    protected void init() {
        if (!initialized) {
            initialized = true;
            if (minecraft.player != null) {
                centerX = minecraft.player.getX();
                centerZ = minecraft.player.getZ();
            }
        }
        mapX0 = 0;
        mapX1 = width;
        mapY0 = HEADER + 1;
        mapY1 = height - FOOTER - 1;
        createAtlases();

        addRenderableWidget(Button.builder(Component.translatable("landclaims.team.title"),
                        b -> minecraft.gui.setScreen(new TeamScreen(this)))
                .bounds(7, 6, 80, 18).build());

        int x = 7;
        int y = HEADER + 8;
        addRenderableWidget(Button.builder(Component.literal("+"), b -> zoomAround(1, centerScreenX(), centerScreenY()))
                .bounds(x, y, 18, 18).tooltip(Tooltip.create(Component.translatable("landclaims.screen.zoom_in"))).build());
        addRenderableWidget(Button.builder(Component.literal("-"), b -> zoomAround(-1, centerScreenX(), centerScreenY()))
                .bounds(x, y + 22, 18, 18).tooltip(Tooltip.create(Component.translatable("landclaims.screen.zoom_out"))).build());
        addRenderableWidget(Button.builder(Component.literal("◎"), b -> flyToPlayer())
                .bounds(x, y + 44, 18, 18).tooltip(Tooltip.create(Component.translatable("landclaims.screen.center"))).build());
    }

    /** Sized so the atlas always has a slot for every chunk visible at the zoom it's used for. */
    private void createAtlases() {
        closeAtlases();
        int mapW = mapX1 - mapX0;
        int mapH = mapY1 - mapY0;
        double detailCell = DETAIL_SCALE * 16;
        double overviewCell = MIN_SCALE * 16;
        detail = new TileAtlas("detail", (int) Math.ceil(mapW / detailCell) + 2, (int) Math.ceil(mapH / detailCell) + 2, 16);
        overview = new TileAtlas("overview", (int) Math.ceil(mapW / overviewCell) + 2, (int) Math.ceil(mapH / overviewCell) + 2, 4);
    }

    private void closeAtlases() {
        if (detail != null) {
            detail.close();
            detail = null;
        }
        if (overview != null) {
            overview.close();
            overview = null;
        }
    }

    @Override
    public void removed() {
        closeAtlases();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- Coordinates ----

    private double centerScreenX() {
        return (mapX0 + mapX1) / 2.0;
    }

    private double centerScreenY() {
        return (mapY0 + mapY1) / 2.0;
    }

    private int toScreenX(double blockX) {
        return (int) Math.floor(centerScreenX() + (blockX - centerX) * scale);
    }

    private int toScreenY(double blockZ) {
        return (int) Math.floor(centerScreenY() + (blockZ - centerZ) * scale);
    }

    private double toBlockX(double screenX) {
        return centerX + (screenX - centerScreenX()) / scale;
    }

    private double toBlockZ(double screenY) {
        return centerZ + (screenY - centerScreenY()) / scale;
    }

    private int chunkAtX(double screenX) {
        return Math.floorDiv((int) Math.floor(toBlockX(screenX)), 16);
    }

    private int chunkAtY(double screenY) {
        return Math.floorDiv((int) Math.floor(toBlockZ(screenY)), 16);
    }

    private boolean insideMap(double x, double y) {
        return x >= mapX0 && x < mapX1 && y >= mapY0 && y < mapY1;
    }

    // ---- Movement ----

    private void zoomAround(int steps, double screenX, double screenY) {
        targetScale = Math.clamp(targetScale * Math.pow(ZOOM_STEP, steps), MIN_SCALE, MAX_SCALE);
        zoomAnchorScreenX = screenX;
        zoomAnchorScreenY = screenY;
        zoomAnchorBlockX = toBlockX(screenX);
        zoomAnchorBlockZ = toBlockZ(screenY);
    }

    private void flyToPlayer() {
        if (minecraft.player != null) {
            flying = true;
            flyTargetX = minecraft.player.getX();
            flyTargetZ = minecraft.player.getZ();
        }
    }

    /** Advances zoom, fly-to and key panning by the time since the last frame. */
    private void animate() {
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 0 : Math.min(0.1, (now - lastFrameNanos) / 1e9);
        lastFrameNanos = now;
        double k = 1 - Math.exp(-SMOOTHING * dt);

        if (Math.abs(targetScale - scale) > 1e-4) {
            scale += (targetScale - scale) * k;
            if (Math.abs(targetScale - scale) < 1e-3) {
                scale = targetScale;
            }
            // Keep the block that was under the cursor under the cursor.
            centerX = zoomAnchorBlockX - (zoomAnchorScreenX - centerScreenX()) / scale;
            centerZ = zoomAnchorBlockZ - (zoomAnchorScreenY - centerScreenY()) / scale;
        }

        if (flying) {
            centerX += (flyTargetX - centerX) * k;
            centerZ += (flyTargetZ - centerZ) * k;
            if (Math.abs(flyTargetX - centerX) * scale < 0.5 && Math.abs(flyTargetZ - centerZ) * scale < 0.5) {
                flying = false;
            }
        }

        double dx = 0;
        double dz = 0;
        if (held(InputConstants.KEY_W, InputConstants.KEY_UP)) dz -= 1;
        if (held(InputConstants.KEY_S, InputConstants.KEY_DOWN)) dz += 1;
        if (held(InputConstants.KEY_A, InputConstants.KEY_LEFT)) dx -= 1;
        if (held(InputConstants.KEY_D, InputConstants.KEY_RIGHT)) dx += 1;
        if (dx != 0 || dz != 0) {
            double len = Math.sqrt(dx * dx + dz * dz);
            double speed = KEY_PAN_SPEED * (heldKeys.contains(InputConstants.KEY_LSHIFT) ? 2.5 : 1) * dt / scale;
            centerX += dx / len * speed;
            centerZ += dz / len * speed;
            flying = false;
        }
    }

    private boolean held(int a, int b) {
        return heldKeys.contains(a) || heldKeys.contains(b);
    }

    // ---- Drawing ----

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, BACKGROUND);
        double cell = scale * 16;
        int every = cell >= 8 ? 1 : 8;
        int cx0 = chunkAtX(mapX0) - 1;
        int cx1 = chunkAtX(mapX1) + 1;
        int cz0 = chunkAtY(mapY0) - 1;
        int cz1 = chunkAtY(mapY1) + 1;
        for (int cx = Math.floorDiv(cx0, every) * every; cx <= cx1; cx += every) {
            int x = toScreenX(cx * 16.0);
            g.fill(x, mapY0, x + 1, mapY1, BACKGROUND_GRID);
        }
        for (int cz = Math.floorDiv(cz0, every) * every; cz <= cz1; cz += every) {
            int y = toScreenY(cz * 16.0);
            g.fill(mapX0, y, mapX1, y + 1, BACKGROUND_GRID);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        animate();
        if (selecting) {
            updateSelection(mouseX, mouseY);
        }

        int cx0 = chunkAtX(mapX0);
        int cx1 = chunkAtX(mapX1 - 1);
        int cz0 = chunkAtY(mapY0);
        int cz1 = chunkAtY(mapY1 - 1);
        renderSomeTiles(cx0, cz0, cx1, cz1);

        TileAtlas atlas = scale >= DETAIL_SCALE ? detail : overview;
        if (cx1 - cx0 + 1 > atlas.slotsX() || cz1 - cz0 + 1 > atlas.slotsZ()) {
            atlas = overview; // mid-animation below the detail threshold
        }
        g.enableScissor(mapX0, mapY0, mapX1, mapY1);
        if (cx1 - cx0 + 1 <= atlas.slotsX() && cz1 - cz0 + 1 <= atlas.slotsZ()) {
            atlas.sync(cx0, cz0, cx1, cz1);
            atlas.draw(g, cx0, cz0, cx1, cz1, b -> toScreenX(b), b -> toScreenY(b));
        }
        if (scale * 16 >= 14) {
            drawGrid(g, cx0, cz0, cx1, cz1);
        }
        drawClaims(g, cx0, cz0, cx1, cz1);
        drawSelection(g, mouseX, mouseY);
        drawPlayer(g);
        g.disableScissor();

        drawHeader(g);
        drawFooter(g, mouseX, mouseY);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        drawTooltip(g, mouseX, mouseY);
    }

    /** Renders a few missing or stale tiles near the player that are on screen, closest to the map center first. */
    private void renderSomeTiles(int cx0, int cz0, int cx1, int cz1) {
        if (minecraft.player == null) {
            return;
        }
        // Only loaded chunks can be rendered, and those are within render distance of the player.
        int radius = minecraft.options.getEffectiveRenderDistance() + 1;
        ChunkPos p = minecraft.player.chunkPosition();
        int x0 = Math.max(cx0, p.x() - radius);
        int x1 = Math.min(cx1, p.x() + radius);
        int z0 = Math.max(cz0, p.z() - radius);
        int z1 = Math.min(cz1, p.z() + radius);
        if (x0 > x1 || z0 > z1) {
            return;
        }
        int mx = (x0 + x1) / 2;
        int mz = (z0 + z1) / 2;
        int maxRing = Math.max(Math.max(mx - x0, x1 - mx), Math.max(mz - z0, z1 - mz));
        long now = System.currentTimeMillis();
        int budget = TILES_PER_FRAME;
        for (int ring = 0; ring <= maxRing && budget > 0; ring++) {
            int side = ring * 2;
            for (int i = 0; i < Math.max(1, side * 4) && budget > 0; i++) {
                // Walk the ring's perimeter: top edge, right edge, bottom edge, left edge.
                int cx;
                int cz;
                if (ring == 0) { cx = mx; cz = mz; }
                else if (i < side) { cx = mx - ring + i; cz = mz - ring; }
                else if (i < 2 * side) { cx = mx + ring; cz = mz - ring + (i - side); }
                else if (i < 3 * side) { cx = mx + ring - (i - 2 * side); cz = mz + ring; }
                else { cx = mx - ring; cz = mz + ring - (i - 3 * side); }
                if (cx < x0 || cx > x1 || cz < z0 || cz > z1) {
                    continue;
                }
                if (MapTiles.refresh(cx, cz, now)) {
                    budget--;
                }
            }
        }
    }

    private void drawGrid(GuiGraphicsExtractor g, int cx0, int cz0, int cx1, int cz1) {
        int line = 0x1A000000;
        for (int cx = cx0 + 1; cx <= cx1; cx++) {
            int x = toScreenX(cx * 16.0);
            g.fill(x, mapY0, x + 1, mapY1, line);
        }
        for (int cz = cz0 + 1; cz <= cz1; cz++) {
            int y = toScreenY(cz * 16.0);
            g.fill(mapX0, y, mapX1, y + 1, line);
        }
    }

    private void drawClaims(GuiGraphicsExtractor g, int cx0, int cz0, int cx1, int cz1) {
        int thickness = scale * 16 >= 24 ? 2 : 1;
        for (Long2ObjectMap.Entry<ClaimSyncPayload.Entry> e : ClientClaims.claims().long2ObjectEntrySet()) {
            ChunkPos chunk = ChunkPos.unpack(e.getLongKey());
            int cx = chunk.x();
            int cz = chunk.z();
            if (cx < cx0 || cx > cx1 || cz < cz0 || cz > cz1) {
                continue;
            }
            ClaimSyncPayload.Entry entry = e.getValue();
            int x0 = toScreenX(cx * 16.0);
            int x1 = toScreenX((cx + 1) * 16.0);
            int y0 = toScreenY(cz * 16.0);
            int y1 = toScreenY((cz + 1) * 16.0);
            int rgb = entry.color() & 0xFFFFFF;
            g.fill(x0, y0, x1, y1, 0x48000000 | rgb);
            int border = 0xE6000000 | rgb;
            if (!sameTeam(entry, cx, cz - 1)) g.fill(x0, y0, x1, y0 + thickness, border);
            if (!sameTeam(entry, cx, cz + 1)) g.fill(x0, y1 - thickness, x1, y1, border);
            if (!sameTeam(entry, cx - 1, cz)) g.fill(x0, y0, x0 + thickness, y1, border);
            if (!sameTeam(entry, cx + 1, cz)) g.fill(x1 - thickness, y0, x1, y1, border);
            if (entry.forced()) {
                drawForcedMarker(g, x0, y0, x1, y1);
            }
        }
    }

    /** Force-loaded chunks get an inner frame and a dot in the middle, readable at any zoom. */
    private static void drawForcedMarker(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
        int size = x1 - x0;
        int color = 0xE6FFFFFF;
        if (size >= 10) {
            int inset = Math.max(2, size / 6);
            g.outline(x0 + inset, y0 + inset, size - 2 * inset, (y1 - y0) - 2 * inset, 0x90FFFFFF);
        }
        int dot = Math.max(2, Math.min(6, size / 4));
        int cx = (x0 + x1) / 2;
        int cy = (y0 + y1) / 2;
        g.fill(cx - dot / 2 - 1, cy - dot / 2 - 1, cx - dot / 2 + dot + 1, cy - dot / 2 + dot + 1, 0xFF000000);
        g.fill(cx - dot / 2, cy - dot / 2, cx - dot / 2 + dot, cy - dot / 2 + dot, color);
    }

    private static int selectionColor(ClaimActionPayload.Action action) {
        return switch (action) {
            case CLAIM -> 0x5BE37D;
            case UNCLAIM -> 0xFF5050;
            case FORCE_LOAD -> 0x4FC3F7;
            case STOP_FORCE_LOAD -> 0xFFB74D;
        };
    }

    private static boolean sameTeam(ClaimSyncPayload.Entry entry, int cx, int cz) {
        ClaimSyncPayload.Entry other = ClientClaims.at(cx, cz);
        return other != null && other.team().equals(entry.team());
    }

    private void drawSelection(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!selecting) {
            return;
        }
        int x0 = toScreenX(Math.min(selStartX, selEndX) * 16.0);
        int y0 = toScreenY(Math.min(selStartZ, selEndZ) * 16.0);
        int x1 = toScreenX((Math.max(selStartX, selEndX) + 1) * 16.0);
        int y1 = toScreenY((Math.max(selStartZ, selEndZ) + 1) * 16.0);
        int rgb = selectionColor(selAction);
        g.fill(x0, y0, x1, y1, 0x40000000 | rgb);
        g.outline(x0, y0, x1 - x0, y1 - y0, 0xFF000000 | rgb);

        int count = (Math.abs(selEndX - selStartX) + 1) * (Math.abs(selEndZ - selStartZ) + 1);
        boolean adding = selAction == ClaimActionPayload.Action.CLAIM || selAction == ClaimActionPayload.Action.FORCE_LOAD;
        String label = (adding ? "+" : "−") + count;
        int w = font.width(label) + 6;
        g.fill(mouseX + 10, mouseY - 4, mouseX + 10 + w, mouseY + 8, 0xC0000000);
        g.text(font, label, mouseX + 13, mouseY - 2, 0xFF000000 | rgb, false);
    }

    private void drawPlayer(GuiGraphicsExtractor g) {
        if (minecraft.player == null) {
            return;
        }
        int size = 10;
        int x = toScreenX(minecraft.player.getX()) - size / 2;
        int y = toScreenY(minecraft.player.getZ()) - size / 2;
        g.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0xFF000000);
        PlayerFaceExtractor.extractRenderState(g, minecraft.player.getSkin(), x, y, size);
    }

    private void drawHeader(GuiGraphicsExtractor g) {
        g.fill(0, 0, width, HEADER, BAR);
        g.fill(0, HEADER, width, HEADER + 1, BAR_EDGE);
        g.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, 5, TEXT);

        Component status;
        if (!ClientClaims.available()) {
            status = Component.translatable("landclaims.screen.unavailable").withStyle(ChatFormatting.RED);
        } else if (ClientClaims.ownTeam().isEmpty()) {
            status = Component.translatable("landclaims.screen.no_team").withStyle(ChatFormatting.GRAY);
        } else {
            status = Component.translatable("landclaims.screen.team",
                    Component.literal(ClientClaims.ownTeam()).withColor(OWN),
                    ClientClaims.ownClaimCount(), ClientClaims.claimLimit())
                    .append(" · ")
                    .append(Component.translatable("landclaims.screen.force_status",
                            ClientClaims.ownForcedCount(), ClientClaims.forceLimit()))
                    .withStyle(ChatFormatting.GRAY);
        }
        g.centeredText(font, status, width / 2, 17, TEXT_DIM);

        if (ClientClaims.available() && ClientClaims.claimLimit() > 0) {
            int barW = 120;
            int bx = width - barW - 10;
            int by = 13;
            float used = Math.min(1f, ClientClaims.ownClaimCount() / (float) ClientClaims.claimLimit());
            g.fill(bx - 1, by - 1, bx + barW + 1, by + 5, 0xFF000000);
            g.fill(bx, by, bx + barW, by + 4, 0xFF2A2F36);
            g.fill(bx, by, bx + (int) (barW * used), by + 4, used >= 1f ? 0xFFE05050 : OWN);
        }
    }

    private void drawFooter(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int top = height - FOOTER;
        g.fill(0, top, width, height, BAR);
        g.fill(0, top - 1, width, top, BAR_EDGE);
        g.centeredText(font, Component.translatable("landclaims.screen.help"), width / 2, top + 7, TEXT_DIM);
        if (insideMap(mouseX, mouseY)) {
            g.text(font, Component.translatable("landclaims.screen.chunk", chunkAtX(mouseX), chunkAtY(mouseY)),
                    8, top + 7, TEXT, false);
        }
        String zoom = Math.round(scale * 100) + "%";
        g.text(font, zoom, width - 8 - font.width(zoom), top + 7, TEXT_DIM, false);
    }

    private void drawTooltip(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (selecting || panning || !insideMap(mouseX, mouseY)) {
            return;
        }
        ClaimSyncPayload.Entry entry = ClientClaims.at(chunkAtX(mouseX), chunkAtY(mouseY));
        if (entry == null) {
            return;
        }
        List<FormattedCharSequence> lines = new ArrayList<>();
        lines.add(Component.translatable("landclaims.screen.owner", entry.team())
                .withStyle(entry.own() ? ChatFormatting.GREEN : ChatFormatting.YELLOW).getVisualOrderText());
        if (entry.forced()) {
            lines.add(Component.translatable("landclaims.screen.forced").withColor(0xFF4FC3F7).getVisualOrderText());
        }
        if (entry.own()) {
            lines.add(Component.translatable("landclaims.screen.own_hint").withStyle(ChatFormatting.GRAY).getVisualOrderText());
            lines.add(Component.translatable("landclaims.screen.force_hint").withStyle(ChatFormatting.GRAY).getVisualOrderText());
        }
        g.setTooltipForNextFrame(lines, mouseX, mouseY);
    }

    // ---- Input ----

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (!insideMap(event.x(), event.y())) {
            return false;
        }
        int button = event.button();
        if (button == InputConstants.MOUSE_BUTTON_MIDDLE) {
            panning = true;
            flying = false;
            return true;
        }
        if ((button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_RIGHT)
                && ClientClaims.available()) {
            selecting = true;
            boolean left = button == InputConstants.MOUSE_BUTTON_LEFT;
            if (event.hasShiftDown()) {
                selAction = left ? ClaimActionPayload.Action.FORCE_LOAD : ClaimActionPayload.Action.STOP_FORCE_LOAD;
            } else {
                selAction = left ? ClaimActionPayload.Action.CLAIM : ClaimActionPayload.Action.UNCLAIM;
            }
            selStartX = selEndX = chunkAtX(event.x());
            selStartZ = selEndZ = chunkAtY(event.y());
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (panning) {
            centerX -= dx / scale;
            centerZ -= dy / scale;
            targetScale = scale;
            return true;
        }
        if (selecting) {
            updateSelection(event.x(), event.y());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    /** Clamped to the visible map so the selection can't grow past what the player sees. */
    private void updateSelection(double mouseX, double mouseY) {
        selEndX = chunkAtX(Math.clamp(mouseX, mapX0, mapX1 - 1));
        selEndZ = chunkAtY(Math.clamp(mouseY, mapY0, mapY1 - 1));
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (panning && event.button() == InputConstants.MOUSE_BUTTON_MIDDLE) {
            panning = false;
            return true;
        }
        if (selecting) {
            selecting = false;
            sendSelection();
            return true;
        }
        return super.mouseReleased(event);
    }

    private void sendSelection() {
        if (minecraft.level == null) {
            return;
        }
        boolean single = selStartX == selEndX && selStartZ == selEndZ;
        List<ChunkPos> chunks = new ArrayList<>();
        for (int cx = Math.min(selStartX, selEndX); cx <= Math.max(selStartX, selEndX); cx++) {
            for (int cz = Math.min(selStartZ, selEndZ); cz <= Math.max(selStartZ, selEndZ); cz++) {
                ClaimSyncPayload.Entry entry = ClientClaims.at(cx, cz);
                boolean own = entry != null && entry.own();
                boolean forced = own && entry.forced();
                boolean useful = switch (selAction) {
                    case CLAIM -> !own;
                    case UNCLAIM -> own;
                    case FORCE_LOAD -> own && !forced;
                    case STOP_FORCE_LOAD -> forced;
                };
                // Skip chunks where nothing would change, so the server's reply only counts real attempts.
                // A single click is always sent, so the player gets a message explaining what happened.
                if (single || useful) {
                    chunks.add(new ChunkPos(cx, cz));
                }
            }
        }
        for (int i = 0; i < chunks.size(); i += ClaimService.MAX_CHUNKS_PER_REQUEST) {
            List<ChunkPos> batch = chunks.subList(i, Math.min(chunks.size(), i + ClaimService.MAX_CHUNKS_PER_REQUEST));
            ClientPacketDistributor.sendToServer(new ClaimActionPayload(selAction, minecraft.level.dimension(),
                    List.copyOf(batch)));
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (scrollY != 0) {
            zoomAround(scrollY > 0 ? 1 : -1, insideMap(x, y) ? x : centerScreenX(), insideMap(x, y) ? y : centerScreenY());
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        switch (key) {
            case InputConstants.KEY_W, InputConstants.KEY_UP, InputConstants.KEY_S, InputConstants.KEY_DOWN,
                    InputConstants.KEY_A, InputConstants.KEY_LEFT, InputConstants.KEY_D, InputConstants.KEY_RIGHT,
                    InputConstants.KEY_LSHIFT -> {
                heldKeys.add(key);
                return true;
            }
            case InputConstants.KEY_SPACE -> {
                flyToPlayer();
                return true;
            }
            default -> {
                if (ClientEvents.OPEN_SCREEN.matches(event)) {
                    onClose();
                    return true;
                }
                return super.keyPressed(event);
            }
        }
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        heldKeys.remove(event.key());
        return super.keyReleased(event);
    }
}
