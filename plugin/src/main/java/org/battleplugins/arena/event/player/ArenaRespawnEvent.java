package org.battleplugins.arena.event.player;

import org.battleplugins.arena.ArenaPlayer;
import org.battleplugins.arena.event.EventTrigger;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Called when a player respawns in an arena.
 */
@EventTrigger("on-respawn")
public class ArenaRespawnEvent extends BukkitArenaPlayerEvent {
    private final static HandlerList HANDLERS = new HandlerList();
    private final PlayerRespawnEvent event;

    public ArenaRespawnEvent(PlayerRespawnEvent event, ArenaPlayer player) {
        super(player.getArena(), player);
        this.event = event;
    }

    public PlayerRespawnEvent getEvent() {
        return event;
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
