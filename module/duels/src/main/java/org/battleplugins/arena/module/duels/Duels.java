package org.battleplugins.arena.module.duels;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.battleplugins.arena.Arena;
import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.competition.Competition;
import org.battleplugins.arena.competition.JoinResult;
import org.battleplugins.arena.competition.LiveCompetition;
import org.battleplugins.arena.competition.PlayerRole;
import org.battleplugins.arena.competition.map.LiveCompetitionMap;
import org.battleplugins.arena.competition.map.MapType;
import org.battleplugins.arena.competition.phase.CompetitionPhaseType;
import org.battleplugins.arena.event.arena.ArenaCreateExecutorEvent;
import org.battleplugins.arena.event.player.ArenaPreJoinEvent;
import org.battleplugins.arena.proxy.ProxyDuelRequestEvent;
import org.battleplugins.arena.messages.Messages;
import org.battleplugins.arena.module.ArenaModule;
import org.battleplugins.arena.module.ArenaModuleInitializer;
import org.battleplugins.arena.proxy.SerializedPlayer;
import org.battleplugins.arena.team.ArenaTeam;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A module that adds duels to BattleArena.
 */
@ArenaModule(id = Duels.ID, name = "Duels", description = "Adds duels to BattleArena.", authors = "BattlePlugins")
public class Duels implements ArenaModuleInitializer {
    public static final String ID = "duels";
    public static final JoinResult PENDING_REQUEST = new JoinResult(false, DuelsMessages.PENDING_DUEL_REQUEST);

    private final Map<UUID, UUID> duelRequests = new HashMap<>();

    private static final class ProxyDuel {
        private final Arena arena;
        private final UUID requester;
        private final UUID target;

        private ProxyDuel(Arena arena, UUID requester, UUID target) {
            this.arena = arena;
            this.requester = requester;
            this.target = target;
        }

        private boolean contains(UUID id) {
            return this.requester.equals(id) || this.target.equals(id);
        }
    }

    // Pending duels that should start on the proxy host once both players are present.
    private final Map<UUID, ProxyDuel> proxyDuels = new HashMap<>();

    @EventHandler
    public void onCreateExecutor(ArenaCreateExecutorEvent event) {
        if (!event.getArena().isModuleEnabled(ID)) {
            return;
        }

        event.registerSubExecutor(new DuelsExecutor(this, event.getArena()));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID requested = this.duelRequests.remove(event.getPlayer().getUniqueId());
        if (requested == null) {
            return;
        }

        Player requestedPlayer = Bukkit.getPlayer(requested);
        if (requestedPlayer != null) {
            DuelsMessages.DUEL_REQUESTED_CANCELLED_QUIT.send(requestedPlayer, event.getPlayer().getName());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        BattleArena plugin = BattleArena.getInstance();
        if (plugin == null || !plugin.getMainConfig().isProxySupport() || !plugin.getMainConfig().isProxyHost()) {
            return;
        }

        UUID id = event.getPlayer().getUniqueId();
        ProxyDuel duel = this.proxyDuels.get(id);
        if (duel != null) {
            this.tryStartProxyDuel(duel);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreJoin(ArenaPreJoinEvent event) {
        if (this.duelRequests.containsKey(event.getPlayer().getUniqueId())) {
            event.setResult(PENDING_REQUEST);
        }
    }

    @EventHandler
    public void onProxyDuelRequest(ProxyDuelRequestEvent event) {
        if (!event.getArena().isModuleEnabled(ID)) {
            return;
        }

        this.handleProxyDuelRequest(event.getArena(), event.getRequesterUuid(), event.getTargetUuid());
    }

    public Map<UUID, UUID> getDuelRequests() {
        return Map.copyOf(this.duelRequests);
    }

    public void addDuelRequest(UUID sender, UUID receiver) {
        this.duelRequests.put(sender, receiver);
    }

    public void removeDuelRequest(UUID sender) {
        this.duelRequests.remove(sender);
    }

    public void acceptDuel(Arena arena, Player player, Player target) {
        BattleArena plugin = arena.getPlugin();
        boolean proxySupport = plugin.getMainConfig().isProxySupport();
        boolean proxyHost = plugin.getMainConfig().isProxyHost();

        // If this server is not the proxy host but proxy support is enabled, we should
        // forward the duel to the host without requiring a local competition.
        if (proxySupport && !proxyHost) {
            // Choose a dynamic map name for the duel; this only needs to match a configured
            // map on the proxy host, not an active competition on this server.
            List<LiveCompetitionMap> dynamicMaps = plugin.getMaps(arena)
                    .stream()
                    .filter(map -> map.getType() == MapType.DYNAMIC)
                    .toList();

            if (dynamicMaps.isEmpty()) {
                Messages.NO_OPEN_ARENAS.send(player);
                Messages.NO_OPEN_ARENAS.send(target);
                return;
            }

            LiveCompetitionMap map = dynamicMaps.iterator().next();

            if (plugin.getConnector() != null) {
                JsonObject payload = new JsonObject();
                payload.addProperty("type", "arena_join");
                payload.addProperty("arena", arena.getName());
                payload.addProperty("map", map.getName());

                JsonArray playerData = new JsonArray();

                org.battleplugins.arena.proxy.SerializedPlayer requesterSerialized =
                        org.battleplugins.arena.proxy.SerializedPlayer.toSerializedPlayer(player);
                JsonObject requesterObject = new JsonObject();
                requesterObject.addProperty("uuid", requesterSerialized.getUuid());
                if (!requesterSerialized.getElements().isEmpty()) {
                    JsonArray elementsArray = new JsonArray();
                    requesterSerialized.getElements().forEach(element -> elementsArray.add(element.name()));
                    requesterObject.add("elements", elementsArray);
                }
                if (!requesterSerialized.getAbilities().isEmpty()) {
                    JsonObject abilitiesObject = new JsonObject();
                    requesterSerialized.getAbilities().forEach((slot, ability) ->
                            abilitiesObject.addProperty(String.valueOf(slot), ability));
                    requesterObject.add("abilities", abilitiesObject);
                }
                playerData.add(requesterObject);

                org.battleplugins.arena.proxy.SerializedPlayer targetSerialized =
                        SerializedPlayer.toSerializedPlayer(target);
                JsonObject targetObject = new JsonObject();
                targetObject.addProperty("uuid", targetSerialized.getUuid());
                if (!targetSerialized.getElements().isEmpty()) {
                    JsonArray elementsArray = new JsonArray();
                    targetSerialized.getElements().forEach(element -> elementsArray.add(element.name()));
                    targetObject.add("elements", elementsArray);
                }
                if (!targetSerialized.getAbilities().isEmpty()) {
                    JsonObject abilitiesObject = new JsonObject();
                    targetSerialized.getAbilities().forEach((slot, ability) ->
                            abilitiesObject.addProperty(String.valueOf(slot), ability));
                    targetObject.add("abilities", abilitiesObject);
                }
                playerData.add(targetObject);

                payload.add("players", playerData);

                String origin = plugin.getMainConfig().getProxyServerName();
                if (origin != null && !origin.isEmpty()) {
                    payload.addProperty("origin", origin);
                }

                plugin.getConnector().sendToRouter(payload.toString());
            }

            return;
        }

        // Local or proxy host: find or create an open competition and join immediately.
        LiveCompetition<?> competition = findOrJoinCompetition(arena);
        if (competition == null) {
            Messages.NO_OPEN_ARENAS.send(player);
            Messages.NO_OPEN_ARENAS.send(target);
            return;
        }

        competition.join(player, PlayerRole.PLAYING);
        competition.join(target, PlayerRole.PLAYING);
    }

    public void handleProxyDuelRequest(Arena arena, UUID requester, UUID target) {
        ProxyDuel duel = new ProxyDuel(arena, requester, target);
        this.proxyDuels.put(requester, duel);
        this.proxyDuels.put(target, duel);

        this.tryStartProxyDuel(duel);
    }

    private void tryStartProxyDuel(ProxyDuel duel) {
        Player requesterPlayer = Bukkit.getPlayer(duel.requester);
        Player targetPlayer = Bukkit.getPlayer(duel.target);
        if (requesterPlayer == null || targetPlayer == null) {
            return;
        }

        // Both players are now present on the proxy host server.
        this.proxyDuels.remove(duel.requester);
        this.proxyDuels.remove(duel.target);

        this.acceptDuel(duel.arena, requesterPlayer, targetPlayer);
    }

    private LiveCompetition<?> findOrJoinCompetition(Arena arena) {
        BattleArena plugin = arena.getPlugin();

        List<Competition<?>> openCompetitions = plugin.getCompetitions(arena)
                .stream()
                .filter(competition -> competition instanceof LiveCompetition<?> liveCompetition
                        && liveCompetition.getPhaseManager().getCurrentPhase().canJoin()
                        && liveCompetition.getPlayers().isEmpty()
                )
                .toList();

        // Ensure we have found an open competition
        if (openCompetitions.isEmpty()) {
            List<LiveCompetitionMap> dynamicMaps = plugin.getMaps(arena)
                    .stream()
                    .filter(map -> map.getType() == MapType.DYNAMIC)
                    .filter(map -> {
                        // When running as the proxy host, prefer maps marked as proxy
                        // so only designated proxy maps are used for these duels.
                        if (plugin.getMainConfig().isProxySupport() && plugin.getMainConfig().isProxyHost()) {
                            return map.isRemote();
                        }
                        return true;
                    })
                    .toList();

            if (dynamicMaps.isEmpty()) {
                return null;
            }

            LiveCompetitionMap map = dynamicMaps.iterator().next();

            LiveCompetition<?> competition = map.createDynamicCompetition(arena);
            arena.getPlugin().addCompetition(arena, competition);
            return competition;
        } else {
            return (LiveCompetition<?>) openCompetitions.iterator().next();
        }
    }
}
