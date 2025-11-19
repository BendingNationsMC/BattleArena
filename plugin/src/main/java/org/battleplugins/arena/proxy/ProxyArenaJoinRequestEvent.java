package org.battleplugins.arena.proxy;

import org.battleplugins.arena.Arena;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Fired on the proxy host whenever a generic arena
 * join request is forwarded from another BattleArena
 * instance via the proxy bridge.
 */
public class ProxyArenaJoinRequestEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Arena arena;
    private final String mapName;
    private final List<SerializedPlayer> players;
    private final String originServer;

    public ProxyArenaJoinRequestEvent(Arena arena, String mapName, List<SerializedPlayer> players, String originServer) {
        this.arena = arena;
        this.mapName = mapName;
        this.players = players;
        this.originServer = originServer;
    }

    public Arena getArena() {
        return arena;
    }

    public String getMapName() {
        return mapName;
    }

    public List<SerializedPlayer> getPlayers() {
        return players;
    }

    public String getOriginServer() {
        return originServer;
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
