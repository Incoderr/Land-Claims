package com.landclaims.client;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jspecify.annotations.Nullable;

import com.landclaims.network.TeamSyncPayload;

import net.neoforged.neoforge.network.handling.IPayloadContext;

/** The client's copy of its team, as last sent by the server. */
public final class ClientTeam {
    private static @Nullable TeamSyncPayload state;
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private ClientTeam() {
    }

    /** Null until the server sends anything (or if it doesn't have the mod). */
    public static @Nullable TeamSyncPayload state() {
        return state;
    }

    /** Called on the client thread whenever the team changes. */
    public static void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    static void handleSync(TeamSyncPayload payload, IPayloadContext context) {
        state = payload;
        listeners.forEach(Runnable::run);
    }

    static void clear() {
        state = null;
        listeners.forEach(Runnable::run);
    }
}
