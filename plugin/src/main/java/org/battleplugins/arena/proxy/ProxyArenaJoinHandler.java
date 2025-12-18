package org.battleplugins.arena.proxy;

import org.battleplugins.arena.Arena;
import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.competition.Competition;
import org.battleplugins.arena.competition.PlayerRole;
import org.battleplugins.arena.competition.map.LiveCompetitionMap;
import org.battleplugins.arena.event.player.ArenaLeaveEvent;
import org.battleplugins.arena.messages.Message;
import org.battleplugins.arena.messages.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.*;

/**
 * Handles generic proxy arena join requests on the proxy host.
 * <p>
 * This allows players to queue for games on non-host servers and
 * only be sent to the proxy host when a specific remote map has
 * been selected for them.
 */
public class ProxyArenaJoinHandler implements Listener {

    private final BattleArena plugin;

    private record PendingJoin(Arena arena, String mapName, Set<SerializedPlayer> players, String originServer) {
            private PendingJoin(Arena arena, String mapName, Collection<SerializedPlayer> players, String originServer) {
                this(arena, mapName, new HashSet<>(players), originServer);
            }
        }

    private final Map<UUID, PendingJoin> pendingJoins = new HashMap<>();
    private final Map<UUID, String> playerOrigins = new HashMap<>();

    public ProxyArenaJoinHandler(BattleArena plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onProxyArenaJoinRequest(ProxyArenaJoinRequestEvent event) {
        if (!this.plugin.getMainConfig().isProxySupport() || !this.plugin.getMainConfig().isProxyHost()) {
            return;
        }

        PendingJoin join = new PendingJoin(event.getArena(), event.getMapName(), event.getPlayers(), event.getOriginServer());
        for (SerializedPlayer serializedPlayer : join.players) {
            UUID playerId = UUID.fromString(serializedPlayer.getUuid());
            this.pendingJoins.put(playerId, join);
            String origin = serializedPlayer.getOrigin();
            if (origin == null || origin.isEmpty()) {
                origin = join.originServer;
            }
            if (origin != null && !origin.isEmpty()) {
                this.playerOrigins.put(playerId, origin);
            }
        }

        this.tryStartJoin(join);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!this.plugin.getMainConfig().isProxySupport() || !this.plugin.getMainConfig().isProxyHost()) {
            return;
        }

        UUID id = event.getPlayer().getUniqueId();
        PendingJoin join = this.pendingJoins.get(id);
        if (join != null) {
            this.tryStartJoin(join);
        }
    }

    private void tryStartJoin(PendingJoin join) {
        Set<Player> onlinePlayers = new HashSet<>();
        for (SerializedPlayer serializedPlayer : join.players) {
            UUID id = UUID.fromString(serializedPlayer.getUuid());
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                return; // Wait until all requested players are present
            }

            onlinePlayers.add(player);
        }

        // All players are now present on the proxy host
        for (SerializedPlayer id : join.players) {
            id.start(Bukkit.getPlayer(UUID.fromString(id.getUuid())));
            this.pendingJoins.remove(UUID.fromString(id.getUuid()));
        }

        // Try to find the requested map
        LiveCompetitionMap map = this.plugin.getMap(join.arena, join.mapName);
        if (map == null) {
            this.plugin.warn("Proxy arena join request for arena {} map {} could not be fulfilled: map not found.",
                    join.arena.getName(), join.mapName);
            this.sendPlayersBackToOrigin(onlinePlayers, Messages.NO_ARENA_WITH_NAME);
            return;
        }

        this.plugin.getOrCreateCompetition(join.arena, onlinePlayers, PlayerRole.PLAYING, map.getName())
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        this.plugin.error("An error occurred while handling proxy arena join for arena {} map {}.",
                                join.arena.getName(), map.getName(), ex);
                        for (Player player : onlinePlayers) {
                            Messages.ARENA_ERROR.send(player, ex.getMessage());
                        }
                        this.sendPlayersBackToOrigin(onlinePlayers, null);
                        return;
                    }

                    Competition<?> competition = result.competition();
                    if (competition == null) {
                        this.sendPlayersBackToOrigin(onlinePlayers,
                                result.result() != null && result.result().message() != null
                                        ? result.result().message()
                                        : Messages.ARENA_NOT_JOINABLE);
                        return;
                    }

                    for (Player player : onlinePlayers) {
                        competition.join(player, PlayerRole.PLAYING);
                        Messages.ARENA_JOINED.send(player, competition.getMap().getName());
                    }
                });
    }

    private void sendPlayersBackToOrigin(Set<Player> players, Message message) {
        for (Player player : players) {
            if (message != null) {
                message.send(player);
            }

            String origin = this.playerOrigins.get(player.getUniqueId());
            if (origin != null && !origin.isEmpty()) {
                this.plugin.sendPlayerToServer(player, origin);
            }

            this.pendingJoins.remove(player.getUniqueId());
            this.playerOrigins.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onArenaLeave(ArenaLeaveEvent event) {
        if (!this.plugin.getMainConfig().isProxySupport() || !this.plugin.getMainConfig().isProxyHost()) {
            return;
        }

        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        String origin = this.playerOrigins.remove(player.getUniqueId());
        if (origin != null && !origin.isEmpty()) {
            this.plugin.sendPlayerToServer(player, origin);
        }
    }
}
