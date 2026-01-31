package org.battleplugins.arena.proxy;

import org.battleplugins.arena.Arena;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Fired on the proxy host whenever a duel request
 * is forwarded from another BattleArena instance
 * via the proxy bridge.
 */
public class ProxyDuelRequestEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Arena arena;
    private final SerializedPlayer requester;
    private final SerializedPlayer target;
    private final List<UUID> requesterParty;
    private final List<UUID> targetParty;
    private final List<SerializedPlayer> players;
    private final String mapName;
    private final String originServer;
    private final int rounds;

    public ProxyDuelRequestEvent(Arena arena,
                                 SerializedPlayer requester,
                                 SerializedPlayer target,
                                 Collection<UUID> requesterParty,
                                 Collection<UUID> targetParty,
                                 Collection<SerializedPlayer> players,
                                 String mapName,
                                 String originServer,
                                 int rounds) {
        this.arena = arena;
        this.requester = requester;
        this.target = target;
        this.requesterParty = List.copyOf(requesterParty);
        this.targetParty = List.copyOf(targetParty);
        this.players = players == null ? List.of() : List.copyOf(players);
        this.mapName = mapName;
        this.originServer = originServer;
        this.rounds = Math.max(1, rounds);
    }

    public Arena getArena() {
        return arena;
    }

    public SerializedPlayer getRequester() {
        return requester;
    }

    public SerializedPlayer getTarget() {
        return target;
    }

    public UUID getRequesterUuid() {
        return UUID.fromString(requester.getUuid());
    }

    public UUID getTargetUuid() {
        return UUID.fromString(target.getUuid());
    }

    public List<UUID> getRequesterPartyMembers() {
        return this.requesterParty;
    }

    public List<UUID> getTargetPartyMembers() {
        return this.targetParty;
    }

    public List<SerializedPlayer> getPlayers() {
        return this.players;
    }

    public String getMapName() {
        return this.mapName;
    }

    public String getOriginServer() {
        return this.originServer;
    }

    public int getRounds() {
        return this.rounds;
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
