package org.battleplugins.arena.proxy;

import org.battleplugins.arena.Arena;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired on the proxy host whenever a non-host server
 * enqueues a player for a proxy-wide queue via the
 * queue_join Redis message.
 */
public class ProxyQueueJoinEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Arena arena;
    private final String originServer;
    private final SerializedPlayer player;

    public ProxyQueueJoinEvent(Arena arena, String originServer, SerializedPlayer player) {
        this.arena = arena;
        this.originServer = originServer;
        this.player = player;
    }

    public Arena getArena() {
        return arena;
    }

    /**
     * Returns the origin proxy server name for this player,
     * if provided by the backend they queued on.
     */
    public String getOriginServer() {
        return originServer;
    }

    public SerializedPlayer getPlayer() {
        return player;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

