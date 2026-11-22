package com.landclaims;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.landclaims.data.ClaimData;
import com.landclaims.data.Team;

import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Keeps force-loaded claims loaded through NeoForge chunk tickets, owned by the team's id.
 * ClaimData is the only source of truth: tickets saved by the game from the last run are dropped on load,
 * and {@link #reconcile} adds exactly the ones that should exist now (respecting "only while online").
 */
@EventBusSubscriber(modid = LandClaims.MODID)
public final class ForceLoadManager {
    static final TicketController TICKETS = new TicketController(
            Identifier.fromNamespaceAndPath(LandClaims.MODID, "force_load"),
            (level, helper) -> List.copyOf(helper.getEntityTickets().keySet()).forEach(helper::removeAllTickets));

    private record Ticket(ResourceKey<Level> dimension, long chunk, UUID team) {
    }

    /** Tickets we currently hold. */
    private static final Set<Ticket> applied = new HashSet<>();
    private static boolean dirty = true;

    private ForceLoadManager() {
    }

    @SubscribeEvent
    public static void registerTickets(RegisterTicketControllersEvent event) {
        event.register(TICKETS);
    }

    /** Recompute on the next server tick: claims, members or who is online changed. */
    public static void markDirty() {
        dirty = true;
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        applied.clear();
        dirty = true;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        applied.clear();
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        dirty = true;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        // The player is still in the player list during this event; the next tick sees them gone.
        dirty = true;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (dirty) {
            dirty = false;
            reconcile(event.getServer());
        }
    }

    private static boolean active(MinecraftServer server, Team team) {
        if (!LandClaimsConfig.FORCE_LOAD_ONLY_WHILE_ONLINE.getAsBoolean()) {
            return true;
        }
        for (UUID member : team.members()) {
            if (server.getPlayerList().getPlayer(member) != null) {
                return true;
            }
        }
        return false;
    }

    private static void reconcile(MinecraftServer server) {
        ClaimData data = ClaimData.get(server);
        Set<Ticket> desired = new HashSet<>();
        data.forEachForced((dimension, chunk, team) -> {
            if (active(server, team)) {
                desired.add(new Ticket(dimension, chunk, team.id()));
            }
        });

        for (Ticket ticket : List.copyOf(applied)) {
            if (!desired.contains(ticket)) {
                apply(server, ticket, false);
                applied.remove(ticket);
            }
        }
        for (Ticket ticket : desired) {
            if (!applied.contains(ticket) && apply(server, ticket, true)) {
                applied.add(ticket);
            }
        }
    }

    private static boolean apply(MinecraftServer server, Ticket ticket, boolean add) {
        ServerLevel level = server.getLevel(ticket.dimension());
        if (level == null) {
            return false;
        }
        ChunkPos chunk = ChunkPos.unpack(ticket.chunk());
        // ticking = true: machines and farms run as if a player were nearby.
        TICKETS.forceChunk(level, ticket.team(), chunk.x(), chunk.z(), add, true);
        return true;
    }
}
