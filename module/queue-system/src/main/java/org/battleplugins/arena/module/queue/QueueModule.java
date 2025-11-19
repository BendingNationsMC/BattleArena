package org.battleplugins.arena.module.queue;

import org.battleplugins.arena.Arena;
import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.competition.map.LiveCompetitionMap;
import org.battleplugins.arena.competition.map.options.Spawns;
import org.battleplugins.arena.event.BattleArenaPostInitializeEvent;
import org.battleplugins.arena.event.BattleArenaReloadedEvent;
import org.battleplugins.arena.event.arena.ArenaCreateExecutorEvent;
import org.battleplugins.arena.module.ArenaModule;
import org.battleplugins.arena.module.ArenaModuleInitializer;
import org.battleplugins.arena.options.Teams;
import org.battleplugins.arena.proxy.ProxyQueueJoinEvent;
import org.battleplugins.arena.proxy.SerializedPlayer;
import org.battleplugins.arena.util.IntRange;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A module that adds a proxy-wide queue system for arenas.
 * <p>
 * Players can join a queue using /&lt;arena&gt; queue and will be
 * matched on the proxy host once enough players are queued. The queue
 * is coordinated via Redis using SerializedPlayer payloads.
 */
@ArenaModule(id = QueueModule.ID, name = "Queue System", description = "Adds a proxy-wide queue system for arenas.", authors = "BattlePlugins")
public class QueueModule implements ArenaModuleInitializer {
    public static final String ID = "queue-system";

    // arenaName -> originServer -> queued players
    private static final Map<String, Map<String, List<SerializedPlayer>>> QUEUES = new ConcurrentHashMap<>();
    private final java.util.Set<java.util.UUID> localQueued = new java.util.HashSet<>();

    private static BukkitTask scannerTask;

    /**
     * Toggles a player in the in-memory queue on the proxy host.
     * If the player is already queued for the given arena+origin,
     * they will be removed; otherwise they will be added.
     *
     * @param arenaName the arena name
     * @param origin    the origin server (proxy-server-name)
     * @param player    the serialized player
     * @return true if the player was added to the queue, false if removed
     */
    public static boolean toggleQueue(String arenaName, String origin, SerializedPlayer player) {
        String arenaKey = arenaName.toLowerCase(Locale.ROOT);
        String originKey = origin == null ? "" : origin;

        Map<String, List<SerializedPlayer>> byOrigin = QUEUES.computeIfAbsent(arenaKey, k -> new ConcurrentHashMap<>());
        List<SerializedPlayer> queue = byOrigin.computeIfAbsent(originKey, k -> Collections.synchronizedList(new ArrayList<>()));

        // Toggle entry for this UUID.
        boolean added;
        synchronized (queue) {
            int index = -1;
            for (int i = 0; i < queue.size(); i++) {
                if (queue.get(i).getUuid().equals(player.getUuid())) {
                    index = i;
                    break;
                }
            }

            if (index >= 0) {
                queue.remove(index);
                added = false;
            } else {
                queue.add(player);
                added = true;
            }
        }

        return added;
    }

    /**
     * Removes the given player UUID from all queues on the host.
     *
     * @param uuid the player UUID (string form) to remove
     */
    public static void removeFromQueues(String uuid) {
        for (Map<String, List<SerializedPlayer>> byOrigin : QUEUES.values()) {
            for (List<SerializedPlayer> queue : byOrigin.values()) {
                synchronized (queue) {
                    queue.removeIf(p -> p.getUuid().equals(uuid));
                }
            }
        }
    }

    @EventHandler
    public void onPostInitialize(BattleArenaPostInitializeEvent event) {
        BattleArena plugin = event.getBattleArena();
        startScanner(plugin);
    }

    @EventHandler
    public void onReloaded(BattleArenaReloadedEvent event) {
        BattleArena plugin = event.getBattleArena();
        startScanner(plugin);
    }

    @EventHandler
    public void onCreateExecutor(ArenaCreateExecutorEvent event) {
        if (!event.getArena().isModuleEnabled(ID)) {
            return;
        }

        event.registerSubExecutor(new QueueModulePerArenaExecutor(event.getArena(), this));
    }

    @EventHandler
    public void onProxyQueueJoin(ProxyQueueJoinEvent event) {
        if (!event.getArena().isModuleEnabled(ID)) {
            return;
        }

        toggleQueue(event.getArena().getName(), event.getOriginServer(), event.getPlayer());
    }

    @EventHandler
    public void onProxyQueueLeave(org.battleplugins.arena.proxy.ProxyQueueLeaveEvent event) {
        removeFromQueues(event.getPlayerUuid());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        BattleArena plugin = BattleArena.getInstance();
        if (plugin == null || !plugin.getMainConfig().isProxySupport()) {
            return;
        }

        localQueued.remove(event.getPlayer().getUniqueId());
        Player player = event.getPlayer();
        String uuid = player.getUniqueId().toString();

        if (plugin.getMainConfig().isProxyHost()) {
            // Directly clear from any queues on the host.
            removeFromQueues(uuid);
        } else if (plugin.getConnector() != null) {
            // Notify the proxy host so it can clear this player from any queues.
            JsonObject payload = new JsonObject();
            payload.addProperty("type", "queue_leave");
            payload.addProperty("uuid", uuid);

            String origin = plugin.getMainConfig().getProxyServerName();
            if (origin != null && !origin.isEmpty()) {
                payload.addProperty("origin", origin);
            }

            plugin.getConnector().sendToRouter(payload.toString());
        }
    }

    private void startScanner(BattleArena plugin) {
        if (scannerTask != null) {
            return;
        }

        if (plugin.getMainConfig().isProxySupport() && plugin.getMainConfig().isProxyHost()) {
            scannerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> processQueues(plugin), 40L, 40L);
        }
    }

    private void processQueues(BattleArena plugin) {
        if (!plugin.getMainConfig().isProxySupport() || !plugin.getMainConfig().isProxyHost() || plugin.getConnector() == null) {
            return;
        }

        for (Map.Entry<String, Map<String, List<SerializedPlayer>>> arenaEntry : QUEUES.entrySet()) {
            String arenaName = arenaEntry.getKey();
            Arena arena = plugin.getArena(arenaName);
            if (arena == null) {
                continue;
            }

            // Derive match size constraints from the arena's team configuration and map spawns.
            Teams teams = arena.getTeams();
            IntRange teamSize = teams.getTeamSize();
            IntRange teamAmount = teams.getTeamAmount();

            int minPlayers = Math.max(1, teamSize.getMin() * teamAmount.getMin());
            int maxPlayers;
            if (teamSize.getMax() == Integer.MAX_VALUE || teamAmount.getMax() == Integer.MAX_VALUE) {
                maxPlayers = Integer.MAX_VALUE;
            } else {
                maxPlayers = teamSize.getMax() * teamAmount.getMax();
            }

            // Prefer proxy/remote maps for queued games.
            List<LiveCompetitionMap> remoteMaps = plugin.getMaps(arena)
                    .stream()
                    .filter(LiveCompetitionMap::isRemote)
                    .toList();

            if (remoteMaps.isEmpty()) {
                continue;
            }

            LiveCompetitionMap map = remoteMaps.get(0);
            Spawns spawns = map.getSpawns();
            if (spawns != null && spawns.getSpawnPointCount() > 0 && maxPlayers != Integer.MAX_VALUE) {
                maxPlayers = Math.min(maxPlayers, spawns.getSpawnPointCount());
            }

            for (Map.Entry<String, List<SerializedPlayer>> originEntry : arenaEntry.getValue().entrySet()) {
                String origin = originEntry.getKey();
                List<SerializedPlayer> queue = originEntry.getValue();

                List<SerializedPlayer> batch = new ArrayList<>();
                synchronized (queue) {
                    if (queue.size() < minPlayers) {
                        continue; // Not enough players for a viable match yet
                    }

                    // Take up to the maximum players the arena/map can handle for a single match.
                    int count = Math.min(maxPlayers, queue.size());
                    for (int i = 0; i < count; i++) {
                        batch.add(queue.remove(0));
                    }
                }

                if (batch.isEmpty()) {
                    continue;
                }

                if (map.getType() == org.battleplugins.arena.competition.map.MapType.DYNAMIC) {
                    // Prepare the dynamic competition on the proxy host *before*
                    // signalling to non-host servers to move players.
                    map.createDynamicCompetitionAsync(arena).whenComplete((competition, ex) -> {
                        if (ex != null || competition == null) {
                            plugin.warn("Failed to prepare dynamic competition for queued match in arena {} map {}.", arena.getName(), map.getName());
                            // In case of failure, requeue the players at the front so they can try again later.
                            synchronized (queue) {
                                for (int i = batch.size() - 1; i >= 0; i--) {
                                    queue.add(0, batch.get(i));
                                }
                            }
                            return;
                        }

                        sendQueueMatch(plugin, arena, competition.getMap().getName(), origin, batch);
                    });
                } else {
                    // Static remote map: already present on the host; just use the queue_match
                    // pipeline so non-host servers move players only when signalled.
                    sendQueueMatch(plugin, arena, map.getName(), origin, batch);
                }
            }
        }
    }

    private void sendQueueMatch(BattleArena plugin,
                                Arena arena,
                                String mapName,
                                String origin,
                                List<SerializedPlayer> batch) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "queue_match");
        payload.addProperty("arena", arena.getName());
        payload.addProperty("map", mapName);
        if (!origin.isEmpty()) {
            payload.addProperty("origin", origin);
        }

        JsonArray playersArray = new JsonArray();
        for (SerializedPlayer sp : batch) {
            JsonObject playerObject = new JsonObject();
            playerObject.addProperty("uuid", sp.getUuid());

            if (!sp.getElements().isEmpty()) {
                JsonArray elementsArray = new JsonArray();
                sp.getElements().forEach(element -> elementsArray.add(element.name()));
                playerObject.add("elements", elementsArray);
            }

            if (!sp.getAbilities().isEmpty()) {
                JsonObject abilitiesObject = new JsonObject();
                sp.getAbilities().forEach((slot, ability) ->
                        abilitiesObject.addProperty(String.valueOf(slot), ability));
                playerObject.add("abilities", abilitiesObject);
            }

            playersArray.add(playerObject);
        }

        payload.add("players", playersArray);
        plugin.getConnector().sendToRouter(payload.toString());
    }

    public Set<UUID> getLocalQueued() {
        return localQueued;
    }
}
