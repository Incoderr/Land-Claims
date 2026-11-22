package com.landclaims.client.screen;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.landclaims.client.ClientClaims;
import com.landclaims.client.ClientTeam;
import com.landclaims.network.TeamActionPayload;
import com.landclaims.network.TeamActionPayload.Action;
import com.landclaims.network.TeamSyncPayload;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Team management: create or join a team, see members and who is online, invite, kick, hand over ownership,
 * pick the team color, rename, leave or disband. Everything is a request to the server, which checks it and
 * answers with a chat message that is also shown at the bottom of this screen.
 */
public final class TeamScreen extends Screen {
    private static final int HEADER = 30;
    private static final int FOOTER = 22;
    private static final int PANEL_W = 340;
    private static final int ROW = 20;

    private static final int BACKGROUND = 0xFF15171B;
    private static final int PANEL = 0xFF1C1F24;
    private static final int PANEL_EDGE = 0xFF2B3038;
    private static final int BAR = 0xE60D0F12;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int TEXT_DIM = 0xFF9AA0A8;
    private static final int ONLINE = 0xFF5BE37D;
    private static final int OFFLINE = 0xFF5A5F66;
    private static final int GOLD = 0xFFFFC94D;
    private static final long STATUS_MS = 6000;
    private static final long CONFIRM_MS = 4000;

    /** Team colors the owner can pick; -1 means "automatic". */
    private static final int[] PALETTE = {
            0x3CC864, 0x2E9E5B, 0x4FC3F7, 0x3F7FE0, 0x8C6CF0, 0xD36CE6,
            0xF06292, 0xE05050, 0xFF8A3D, 0xFFC94D, 0xC0C6CE, 0x6D4C41,
    };

    private final @Nullable Screen parent;
    private final Runnable onTeamChanged = this::rebuildWidgets;

    private EditBox nameBox;
    private String nameDraft = "";
    private int memberScroll;
    private int candidateScroll;

    private @Nullable Component status;
    private long statusUntil;
    private @Nullable Action confirming;
    private long confirmUntil;

    // Layout, recomputed in init().
    private int panelX0, panelX1, contentY;
    private int leftX, rightX, colW;
    private int listY, listRows;
    private int paletteY;

    public TeamScreen(@Nullable Screen parent) {
        super(Component.translatable("landclaims.team.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ClientTeam.removeListener(onTeamChanged);
        ClientTeam.addListener(onTeamChanged);

        panelX0 = (width - PANEL_W) / 2;
        panelX1 = panelX0 + PANEL_W;
        contentY = HEADER + 12;
        colW = (PANEL_W - 30) / 2;
        leftX = panelX0 + 10;
        rightX = panelX0 + 20 + colW;

        addRenderableWidget(Button.builder(Component.translatable("landclaims.team.back"), b -> onClose())
                .bounds(7, 6, 70, 18).build());

        TeamSyncPayload state = ClientTeam.state();
        if (state == null) {
            return;
        }
        if (state.inTeam()) {
            initTeam(state);
        } else {
            initNoTeam(state);
        }
    }

    @Override
    public void removed() {
        ClientTeam.removeListener(onTeamChanged);
        super.removed();
    }

    @Override
    public void onClose() {
        if (parent != null) {
            minecraft.gui.setScreen(parent);
        } else {
            super.onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private boolean isOwner(TeamSyncPayload state) {
        return minecraft.player != null && state.owner().equals(minecraft.player.getUUID());
    }

    private static void send(TeamActionPayload payload) {
        ClientPacketDistributor.sendToServer(payload);
    }

    /** Shown at the bottom for a few seconds: the server's answers arrive as chat messages. */
    public void showStatus(Component message) {
        status = message;
        statusUntil = System.currentTimeMillis() + STATUS_MS;
    }

    private EditBox nameBox(int x, int y, int w) {
        if (nameBox != null) {
            nameDraft = nameBox.getValue();
        }
        nameBox = new EditBox(font, x, y, w, 18, Component.translatable("landclaims.team.name"));
        nameBox.setMaxLength(16);
        nameBox.setHint(Component.translatable("landclaims.team.name_hint").withStyle(ChatFormatting.DARK_GRAY));
        nameBox.setValue(nameDraft);
        nameBox.setResponder(value -> nameDraft = value);
        return addRenderableWidget(nameBox);
    }

    // ---- No team ----

    private void initNoTeam(TeamSyncPayload state) {
        int y = contentY + 22;
        nameBox(leftX, y, PANEL_W - 20 - 84);
        addRenderableWidget(Button.builder(Component.translatable("landclaims.team.create"),
                        b -> send(TeamActionPayload.withText(Action.CREATE, nameDraft.trim())))
                .bounds(panelX1 - 10 - 80, y, 80, 18).build());

        listY = y + 50;
        int row = 0;
        for (String team : state.invites()) {
            int ry = listY + row * ROW;
            addRenderableWidget(Button.builder(Component.translatable("landclaims.team.join"),
                            b -> send(TeamActionPayload.withText(Action.JOIN, team)))
                    .bounds(panelX1 - 10 - 80, ry, 80, 18).build());
            row++;
        }
    }

    // ---- In a team ----

    private void initTeam(TeamSyncPayload state) {
        boolean owner = isOwner(state);
        listY = contentY + 48;
        int bottom = height - FOOTER - 16 - (owner ? 74 : 26);
        listRows = Math.max(1, (bottom - listY) / ROW);

        // Members, owner first. The owner gets buttons on everyone else.
        List<TeamSyncPayload.Player> members = state.members();
        memberScroll = Math.clamp(memberScroll, 0, Math.max(0, members.size() - listRows));
        for (int i = 0; i < Math.min(listRows, members.size() - memberScroll); i++) {
            TeamSyncPayload.Player member = members.get(i + memberScroll);
            if (!owner || member.id().equals(state.owner())) {
                continue;
            }
            int ry = listY + i * ROW;
            UUID id = member.id();
            Button crown = Button.builder(Component.literal("★"), b -> confirmOr(Action.TRANSFER,
                            () -> send(TeamActionPayload.withTarget(Action.TRANSFER, id))))
                    .bounds(leftX + colW - 42, ry, 20, 18).build();
            crown.setTooltip(Tooltip.create(Component.translatable("landclaims.team.transfer", member.name())));
            addRenderableWidget(crown);
            Button kick = Button.builder(Component.literal("✕"), b -> send(TeamActionPayload.withTarget(Action.KICK, id)))
                    .bounds(leftX + colW - 20, ry, 20, 18).build();
            kick.setTooltip(Tooltip.create(Component.translatable("landclaims.team.kick", member.name())));
            addRenderableWidget(kick);
        }

        // Online players without a team.
        List<TeamSyncPayload.Player> candidates = state.candidates();
        candidateScroll = Math.clamp(candidateScroll, 0, Math.max(0, candidates.size() - listRows));
        for (int i = 0; i < Math.min(listRows, candidates.size() - candidateScroll); i++) {
            TeamSyncPayload.Player candidate = candidates.get(i + candidateScroll);
            int ry = listY + i * ROW;
            Button invite = Button.builder(Component.translatable("landclaims.team.invite"),
                            b -> send(TeamActionPayload.withTarget(Action.INVITE, candidate.id())))
                    .bounds(rightX + colW - 70, ry, 70, 18).build();
            invite.active = owner;
            if (!owner) {
                invite.setTooltip(Tooltip.create(Component.translatable("landclaims.msg.team.not_owner")));
            }
            addRenderableWidget(invite);
        }

        int y = height - FOOTER - 12 - 20;
        if (owner) {
            // Color palette is drawn and clicked by hand; rename sits under it.
            paletteY = y - 50;
            int renameY = y - 26;
            nameBox(leftX, renameY, PANEL_W - 20 - 104);
            addRenderableWidget(Button.builder(Component.translatable("landclaims.team.rename"),
                            b -> send(TeamActionPayload.withText(Action.RENAME, nameDraft.trim())))
                    .bounds(panelX1 - 10 - 100, renameY, 100, 18).build());
        }

        int half = (PANEL_W - 30) / 2;
        addRenderableWidget(Button.builder(confirmLabel(Action.LEAVE, "landclaims.team.leave"),
                        b -> confirmOr(Action.LEAVE, () -> send(TeamActionPayload.of(Action.LEAVE))))
                .bounds(leftX, y, owner ? half : PANEL_W - 20, 18).build());
        if (owner) {
            addRenderableWidget(Button.builder(confirmLabel(Action.DISBAND, "landclaims.team.disband"),
                            b -> confirmOr(Action.DISBAND, () -> send(TeamActionPayload.of(Action.DISBAND))))
                    .bounds(rightX, y, half, 18).build());
        }
    }

    private Component confirmLabel(Action action, String key) {
        if (confirming == action && System.currentTimeMillis() < confirmUntil) {
            return Component.translatable("landclaims.team.confirm").withStyle(ChatFormatting.RED);
        }
        return Component.translatable(key);
    }

    /** Destructive actions need a second click within a few seconds. */
    private void confirmOr(Action action, Runnable run) {
        long now = System.currentTimeMillis();
        if (confirming == action && now < confirmUntil) {
            confirming = null;
            run.run();
        } else {
            confirming = action;
            confirmUntil = now + CONFIRM_MS;
            if (action == Action.TRANSFER) {
                showStatus(Component.translatable("landclaims.team.confirm_transfer").withStyle(ChatFormatting.YELLOW));
            }
        }
        rebuildWidgets();
    }

    @Override
    public void tick() {
        // Turn a pending confirmation back into the normal label once it expires.
        if (confirming != null && System.currentTimeMillis() >= confirmUntil) {
            confirming = null;
            rebuildWidgets();
        }
    }

    // ---- Drawing ----

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, BACKGROUND);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, HEADER, BAR);
        g.fill(0, HEADER, width, HEADER + 1, PANEL_EDGE);
        g.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, 11, TEXT);

        g.fill(panelX0 - 1, HEADER + 5, panelX1 + 1, height - FOOTER - 5, PANEL_EDGE);
        g.fill(panelX0, HEADER + 6, panelX1, height - FOOTER - 6, PANEL);

        TeamSyncPayload state = ClientTeam.state();
        if (state == null) {
            g.centeredText(font, Component.translatable("landclaims.screen.unavailable").withStyle(ChatFormatting.RED),
                    width / 2, contentY + 20, TEXT);
        } else if (state.inTeam()) {
            drawTeam(g, state, mouseX, mouseY);
        } else {
            drawNoTeam(g, state);
        }

        int top = height - FOOTER;
        g.fill(0, top, width, height, BAR);
        g.fill(0, top - 1, width, top, PANEL_EDGE);
        if (status != null && System.currentTimeMillis() < statusUntil) {
            g.centeredText(font, status, width / 2, top + 7, TEXT);
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void drawNoTeam(GuiGraphicsExtractor g, TeamSyncPayload state) {
        g.text(font, Component.translatable("landclaims.team.create_title").withStyle(ChatFormatting.BOLD),
                leftX, contentY, TEXT, false);
        g.text(font, Component.translatable("landclaims.team.create_help"), leftX, contentY + 11, TEXT_DIM, false);

        g.text(font, Component.translatable("landclaims.team.invites").withStyle(ChatFormatting.BOLD),
                leftX, listY - 14, TEXT, false);
        if (state.invites().isEmpty()) {
            g.text(font, Component.translatable("landclaims.team.no_invites"), leftX, listY + 5, TEXT_DIM, false);
        }
        for (int i = 0; i < state.invites().size(); i++) {
            g.text(font, state.invites().get(i), leftX + 4, listY + i * ROW + 5, TEXT, false);
        }
    }

    private void drawTeam(GuiGraphicsExtractor g, TeamSyncPayload state, int mouseX, int mouseY) {
        // Title row: color swatch, name, counters.
        int color = state.color() == -1 ? 0x3CC864 : state.color();
        g.fill(leftX, contentY, leftX + 12, contentY + 12, 0xFF000000);
        g.fill(leftX + 1, contentY + 1, leftX + 11, contentY + 11, 0xFF000000 | color);
        g.text(font, Component.literal(state.team()).withStyle(ChatFormatting.BOLD), leftX + 18, contentY + 2, TEXT, false);
        Component counts = Component.translatable("landclaims.team.counts",
                ClientClaims.ownClaimCount(), ClientClaims.claimLimit(),
                ClientClaims.ownForcedCount(), ClientClaims.forceLimit());
        g.text(font, counts, panelX1 - 10 - font.width(counts), contentY + 2, TEXT_DIM, false);

        // Column headers.
        int headerY = listY - 14;
        g.text(font, Component.translatable("landclaims.team.members", state.members().size())
                .withStyle(ChatFormatting.BOLD), leftX, headerY, TEXT, false);
        g.text(font, Component.translatable("landclaims.team.online_without_team")
                .withStyle(ChatFormatting.BOLD), rightX, headerY, TEXT, false);
        g.fill(leftX, listY - 3, leftX + colW, listY - 2, PANEL_EDGE);
        g.fill(rightX, listY - 3, rightX + colW, listY - 2, PANEL_EDGE);

        List<TeamSyncPayload.Player> members = state.members();
        for (int i = 0; i < Math.min(listRows, members.size() - memberScroll); i++) {
            TeamSyncPayload.Player member = members.get(i + memberScroll);
            int ry = listY + i * ROW;
            boolean isOwner = member.id().equals(state.owner());
            g.fill(leftX + 2, ry + 6, leftX + 8, ry + 12, member.online() ? ONLINE : OFFLINE);
            g.text(font, member.name(), leftX + 13, ry + 5, member.online() ? TEXT : TEXT_DIM, false);
            if (isOwner) {
                g.text(font, "★", leftX + 15 + font.width(member.name()), ry + 5, GOLD, false);
            }
        }
        drawScrollHint(g, leftX, members.size(), memberScroll);

        List<TeamSyncPayload.Player> candidates = state.candidates();
        if (candidates.isEmpty()) {
            g.text(font, Component.translatable("landclaims.team.nobody_online"), rightX, listY + 5, TEXT_DIM, false);
        }
        for (int i = 0; i < Math.min(listRows, candidates.size() - candidateScroll); i++) {
            g.fill(rightX + 2, listY + i * ROW + 6, rightX + 8, listY + i * ROW + 12, ONLINE);
            g.text(font, candidates.get(i + candidateScroll).name(), rightX + 13, listY + i * ROW + 5, TEXT, false);
        }
        drawScrollHint(g, rightX, candidates.size(), candidateScroll);

        if (isOwner(state)) {
            drawPalette(g, state, mouseX, mouseY);
        }
    }

    private void drawScrollHint(GuiGraphicsExtractor g, int x, int total, int scroll) {
        int hidden = total - scroll - listRows;
        if (hidden > 0) {
            g.text(font, Component.translatable("landclaims.team.more", hidden), x + 13, listY + listRows * ROW,
                    TEXT_DIM, false);
        }
    }

    private int swatchX(int index) {
        return leftX + 56 + index * 18;
    }

    private void drawPalette(GuiGraphicsExtractor g, TeamSyncPayload state, int mouseX, int mouseY) {
        g.text(font, Component.translatable("landclaims.team.color"), leftX, paletteY + 4, TEXT_DIM, false);
        for (int i = 0; i <= PALETTE.length; i++) {
            int x = swatchX(i);
            boolean auto = i == PALETTE.length;
            int rgb = auto ? 0x2A2F36 : PALETTE[i];
            boolean selected = auto ? state.color() == -1 : state.color() == rgb;
            boolean hover = mouseX >= x && mouseX < x + 16 && mouseY >= paletteY && mouseY < paletteY + 16;
            g.fill(x, paletteY, x + 16, paletteY + 16, selected ? 0xFFFFFFFF : hover ? 0xFF9AA0A8 : 0xFF000000);
            g.fill(x + 2, paletteY + 2, x + 14, paletteY + 14, 0xFF000000 | rgb);
            if (auto) {
                g.centeredText(font, "A", x + 8, paletteY + 4, TEXT_DIM);
            }
            if (hover) {
                g.setTooltipForNextFrame(Component.translatable(auto ? "landclaims.team.color_auto" : "landclaims.team.color_pick"),
                        mouseX, mouseY);
            }
        }
    }

    // ---- Input ----

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        TeamSyncPayload state = ClientTeam.state();
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && state != null && state.inTeam() && isOwner(state)
                && event.y() >= paletteY && event.y() < paletteY + 16) {
            for (int i = 0; i <= PALETTE.length; i++) {
                int x = swatchX(i);
                if (event.x() >= x && event.x() < x + 16) {
                    send(TeamActionPayload.withValue(Action.COLOR, i == PALETTE.length ? -1 : PALETTE[i]));
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        int delta = scrollY > 0 ? -1 : 1;
        if (x < rightX) {
            memberScroll = Math.max(0, memberScroll + delta);
        } else {
            candidateScroll = Math.max(0, candidateScroll + delta);
        }
        rebuildWidgets();
        return true;
    }
}
