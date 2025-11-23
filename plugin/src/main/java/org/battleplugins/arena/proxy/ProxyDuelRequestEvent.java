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
    private final String originServer;

    public ProxyDuelRequestEvent(Arena arena,
                                 SerializedPlayer requester,
                                 SerializedPlayer target,
                                 Collection<UUID> requesterParty,
                                 Collection<UUID> targetParty,
                                 String originServer) {
        this.arena = arena;
        this.requester = requester;
        this.target = target;
        this.requesterParty = List.copyOf(requesterParty);
        this.targetParty = List.copyOf(targetParty);
        this.originServer = originServer;
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

    public String getOriginServer() {
        return this.originServer;
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
