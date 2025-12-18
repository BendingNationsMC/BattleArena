package org.battleplugins.arena.proxy;

import com.google.gson.JsonObject;
import org.battleplugins.arena.Arena;
import org.battleplugins.arena.ArenaLike;
import org.battleplugins.arena.ArenaPlayer;
import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.competition.Competition;
import org.battleplugins.arena.competition.PlayerRole;
import org.battleplugins.arena.messages.Message;
import org.battleplugins.arena.messages.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles routing proxy-wide spectate requests on the proxy host.
 */
public class ProxySpectateHandler implements Listener {
    private final BattleArena plugin;
    private final Map<UUID, PendingSpectate> pendingSpectators = new ConcurrentHashMap<>();
    private final Map<UUID, String> spectatorOrigins = new ConcurrentHashMap<>();

    public ProxySpectateHandler(BattleArena plugin) {
        this.plugin = plugin;
    }

    public void handleRequest(ProxySpectateRequest request) {
        if (!this.plugin.getMainConfig().isProxySupport() || !this.plugin.getMainConfig().isProxyHost()) {
            return;
        }

        Competition<?> competition = null;
        Message failureMessage = null;
        if (request.mode() == ProxySpectateRequest.Mode.PLAYER) {
            Player target = null;
            if (request.targetId() != null) {
                target = Bukkit.getPlayer(request.targetId());
            }

            if (target == null && request.targetName() != null && !request.targetName().isEmpty()) {
                target = Bukkit.getPlayerExact(request.targetName());
            }

            if (target == null) {
                failureMessage = Messages.PROXY_SPECTATE_TARGET_NOT_FOUND;
            } else {
                ArenaPlayer arenaPlayer = ArenaPlayer.getArenaPlayer(target);
                if (arenaPlayer == null || arenaPlayer.getCompetition() == null) {
                    failureMessage = Messages.PROXY_SPECTATE_TARGET_NOT_IN_ARENA;
                } else {
                    competition = arenaPlayer.getCompetition();
                }
            }
        } else {
            competition = this.findCompetitionForArena(request);
            if (competition == null) {
                failureMessage = Messages.NO_OPEN_ARENAS;
            }
        }

        if (competition == null) {
            this.sendReject(request.spectator(), request.originServer(), failureMessage);
            return;
        }

        UUID spectatorId = UUID.fromString(request.spectator().getUuid());
        PendingSpectate pending = new PendingSpectate(competition, request.spectator(), request.originServer());
        if (this.pendingSpectators.putIfAbsent(spectatorId, pending) != null) {
            this.sendReject(request.spectator(), request.originServer(), Messages.PROXY_SPECTATE_ALREADY_PENDING);
            return;
        }

        this.sendReady(pending, request.originServer());
    }

    private Competition<?> findCompetitionForArena(ProxySpectateRequest request) {
        Arena arena = request.arena();
        if (arena == null) {
            return null;
        }

        if (request.mapName() != null && !request.mapName().isEmpty()) {
            List<Competition<?>> competitions = this.plugin.getCompetitions(arena, request.mapName());
            if (!competitions.isEmpty()) {
                return competitions.get(0);
            }
        }

        List<Competition<?>> competitions = this.plugin.getCompetitions(arena);
        return competitions.isEmpty() ? null : competitions.get(0);
    }

    private void sendReady(PendingSpectate pending, String originServer) {
        Connector connector = this.plugin.getConnector();
        if (connector == null) {
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("type", "spectate_ready");
        payload.addProperty("uuid", pending.serialized().getUuid());
        String arenaName;
        if (pending.competition() instanceof ArenaLike arenaLike) {
            arenaName = arenaLike.getArena().getName();
        } else {
            arenaName = pending.competition().getMap().getName();
        }
        payload.addProperty("arena", arenaName);
        payload.addProperty("map", pending.competition().getMap().getName());
        if (originServer != null && !originServer.isEmpty()) {
            payload.addProperty("origin", originServer);
        }

        connector.sendToRouter(payload.toString());
    }

    private void sendReject(SerializedPlayer spectator, String originServer, @Nullable Message reason) {
        Connector connector = this.plugin.getConnector();
        if (connector == null) {
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("type", "spectate_reject");
        payload.addProperty("uuid", spectator.getUuid());
        if (originServer != null && !originServer.isEmpty()) {
            payload.addProperty("origin", originServer);
        }

        if (reason != null) {
            payload.addProperty("reason", reason.asMiniMessage());
        }

        connector.sendToRouter(payload.toString());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        PendingSpectate pending = this.pendingSpectators.remove(id);
        if (pending == null) {
            return;
        }

        pending.serialized().start(event.getPlayer());
        if (pending.origin() != null && !pending.origin().isEmpty()) {
            this.spectatorOrigins.put(id, pending.origin());
        }

        pending.competition().canJoin(event.getPlayer(), PlayerRole.SPECTATING).whenComplete((result, error) -> {
            if (error != null) {
                Messages.ARENA_ERROR.send(event.getPlayer(), error.getMessage());
                this.plugin.error("An error occurred while spectating via proxy", error);
                this.sendPlayerBack(event.getPlayer());
                return;
            }

            if (result.canJoin()) {
                pending.competition().join(event.getPlayer(), PlayerRole.SPECTATING);
                Messages.ARENA_SPECTATE.send(event.getPlayer(), pending.competition().getMap().getName());
            } else {
                if (result.message() != null) {
                    result.message().send(event.getPlayer());
                } else {
                    Messages.ARENA_NOT_SPECTATABLE.send(event.getPlayer());
                }

                this.sendPlayerBack(event.getPlayer());
            }
        });
    }

    @EventHandler
    public void onArenaLeave(org.battleplugins.arena.event.player.ArenaLeaveEvent event) {
        Player player = event.getPlayer();
        if (player == null || event.getArenaPlayer() == null) {
            return;
        }

        if (event.getArenaPlayer().getRole() != PlayerRole.SPECTATING) {
            return;
        }

        this.sendPlayerBack(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        this.pendingSpectators.remove(id);
        this.spectatorOrigins.remove(id);
    }

    private void sendPlayerBack(Player player) {
        UUID id = player.getUniqueId();
        String origin = this.spectatorOrigins.remove(id);
        if (origin != null && !origin.isEmpty()) {
            this.plugin.sendPlayerToServer(player, origin);
        }
    }

    public record ProxySpectateRequest(
            Mode mode,
            @Nullable Arena arena,
            @Nullable String mapName,
            @Nullable UUID targetId,
            @Nullable String targetName,
            SerializedPlayer spectator,
            @Nullable String originServer
    ) {

        public enum Mode {
            ARENA,
            PLAYER
        }
    }

    private record PendingSpectate(Competition<?> competition, SerializedPlayer serialized, @Nullable String origin) {
    }
}
