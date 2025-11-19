package org.battleplugins.arena.proxy;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired on the proxy host whenever a non-host server
 * notifies that a player has left and should be removed
 * from any proxy-wide queues.
 */
public class ProxyQueueLeaveEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final String originServer;
    private final String playerUuid;

    public ProxyQueueLeaveEvent(String originServer, String playerUuid) {
        this.originServer = originServer;
        this.playerUuid = playerUuid;
    }

    public String getOriginServer() {
        return originServer;
    }

    public String getPlayerUuid() {
        return playerUuid;
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

