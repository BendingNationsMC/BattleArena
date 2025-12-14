package org.battleplugins.arena.module.queue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.battleplugins.arena.Arena;
import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.competition.map.ElementMatchup;
import org.battleplugins.arena.competition.map.LiveCompetitionMap;
import org.battleplugins.arena.competition.map.options.Spawns;
import org.battleplugins.arena.event.BattleArenaPostInitializeEvent;
import org.battleplugins.arena.event.BattleArenaReloadedEvent;
import org.battleplugins.arena.event.arena.ArenaCreateExecutorEvent;
import org.battleplugins.arena.module.ArenaModule;
import org.battleplugins.arena.module.ArenaModuleInitializer;
import org.battleplugins.arena.options.Teams;
import org.battleplugins.arena.queue.QueueService;
import org.battleplugins.arena.proxy.Elements;
import org.battleplugins.arena.proxy.ProxyQueueJoinEvent;
import org.battleplugins.arena.proxy.SerializedPlayer;
import org.battleplugins.arena.util.IntRange;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
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
public class QueueModule implements ArenaModuleInitializer, QueueService {
    public static final String ID = "queue-system";

    // arenaName -> originServer -> queued players
    private static final Map<String, Map<String, Deque<SerializedPlayer>>> QUEUES = new ConcurrentHashMap<>();
    private static final Logger log = LoggerFactory.getLogger(QueueModule.class);
    // Local queued tracker per backend for /<arena> queue toggling
    private final Set<UUID> localQueued = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> queueStartTimes = new ConcurrentHashMap<>();
    private record QueuedEntry(String origin, SerializedPlayer player) {}
    private record MatchSelection(LiveCompetitionMap map, List<QueuedEntry> players) {}
    private static final class Slot {
        private final Elements requiredElement;

        private Slot(Elements requiredElement) {
            this.requiredElement = requiredElement;
        }
    }

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

        Map<String, Deque<SerializedPlayer>> byOrigin = QUEUES.computeIfAbsent(arenaKey, k -> new ConcurrentHashMap<>());
        Deque<SerializedPlayer> queue = byOrigin.computeIfAbsent(originKey, k -> new ArrayDeque<>());

        // Toggle entry for this UUID.
        synchronized (queue) {
            Iterator<SerializedPlayer> iterator = queue.iterator();
            while (iterator.hasNext()) {
                if (iterator.next().getUuid().equals(player.getUuid())) {
                    iterator.remove();
                    return false;
                }
            }

            queue.addLast(player);
            return true;
        }
    }

    /**
     * Removes the given player UUID from all queues on the host.
     *
     * @param uuid the player UUID (string form) to remove
     */
    public static void removeFromQueues(String uuid) {
        for (Map<String, Deque<SerializedPlayer>> byOrigin : QUEUES.values()) {
            for (Deque<SerializedPlayer> queue : byOrigin.values()) {
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

        UUID playerId = event.getPlayer().getUniqueId();
        removeLocalQueue(playerId);
        plugin.removePendingProxyJoin(playerId);
        String uuid = playerId.toString();

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

        for (Map.Entry<String, Map<String, Deque<SerializedPlayer>>> arenaEntry : QUEUES.entrySet()) {
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
            int minPlayersPerTeam = Math.max(1, teamSize.getMin());
            int maxPlayersBase;
            if (teamSize.getMax() == Integer.MAX_VALUE || teamAmount.getMax() == Integer.MAX_VALUE) {
                maxPlayersBase = Integer.MAX_VALUE;
            } else {
                maxPlayersBase = teamSize.getMax() * teamAmount.getMax();
            }

            // Prefer proxy/remote maps for queued games.
            List<LiveCompetitionMap> remoteMaps = new ArrayList<>(plugin.getMaps(arena)
                    .stream()
                    .filter(LiveCompetitionMap::isRemote)
                    .toList());

            if (remoteMaps.isEmpty()) {
                continue;
            }

            // Shuffle maps so that selection order is randomized.
            Collections.shuffle(remoteMaps);

            Map<String, Deque<SerializedPlayer>> byOrigin = arenaEntry.getValue();
            while (true) {
                int queuedPlayers = totalQueuedPlayers(byOrigin);
                if (queuedPlayers < minPlayers) {
                    break;
                }

                int count = Math.min(maxPlayersBase, queuedPlayers);
                List<QueuedEntry> batch = takePlayerBatch(byOrigin, count);
                if (batch.size() < minPlayers) {
                    requeueEntries(byOrigin, batch);
                    break;
                }

                // Choose a map for this batch based on matchups and shuffle order.
                MatchSelection selection = selectMapForBatch(remoteMaps, batch, minPlayersPerTeam);
                if (selection == null) {
                    // No map exists whose matchups fit all players in this batch; requeue
                    // them at the front and wait for a better combination.
                    requeueEntries(byOrigin, batch);
                    break;
                }

                LiveCompetitionMap map = selection.map();
                List<QueuedEntry> selectedPlayers = selection.players();
                if (selectedPlayers.isEmpty()) {
                    requeueEntries(byOrigin, batch);
                    break;
                }

                if (selectedPlayers.size() < batch.size()) {
                    List<QueuedEntry> leftovers = new ArrayList<>(batch);
                    leftovers.removeAll(selectedPlayers);
                    requeueEntries(byOrigin, leftovers);
                }

                // Adjust maxPlayers by spawn count for the selected map.
                int maxPlayers = maxPlayersBase;
                Spawns spawns = map.getSpawns();
                if (spawns != null && spawns.getSpawnPointCount() > 0 && maxPlayers != Integer.MAX_VALUE) {
                    maxPlayers = Math.min(maxPlayers, spawns.getSpawnPointCount());
                }

                if (map.getType() == org.battleplugins.arena.competition.map.MapType.DYNAMIC) {
                    // Prepare the dynamic competition on the proxy host *before*
                    // signalling to non-host servers to move players.
                    map.createDynamicCompetitionAsync(arena).whenComplete((competition, ex) -> {
                        if (ex != null || competition == null) {
                            plugin.warn("Failed to prepare dynamic competition for queued match in arena {} map {}.", arena.getName(), map.getName());
                            // In case of failure, requeue the players at the front so they can try again later.
                            requeueEntries(byOrigin, selectedPlayers);
                            return;
                        }

                        sendQueueMatch(plugin, arena, competition.getMap().getName(), selectedPlayers);
                    });
                } else {
                    // Static remote map: already present on the host; just use the queue_match
                    // pipeline so non-host servers move players only when signalled.
                    sendQueueMatch(plugin, arena, map.getName(), selectedPlayers);
                }
            }
        }
    }

    private MatchSelection selectMapForBatch(List<LiveCompetitionMap> maps, List<QueuedEntry> batch, int minPlayersPerTeam) {
        for (LiveCompetitionMap map : maps) {
            List<ElementMatchup> matchups = map.getMatchups();
            if (matchups.isEmpty()) {
                BattleArena.getInstance().getLogger().info("Map has no element restrictions; allowing the full batch through");
                // Map has no element restrictions; allow the full batch through.
                return new MatchSelection(map, new ArrayList<>(batch));
            }

            for (ElementMatchup matchup : matchups) {
                List<QueuedEntry> selection = allocatePlayersForMatchup(matchup, batch, minPlayersPerTeam);
                if (selection != null) {
                    return new MatchSelection(map, selection);
                }
            }
        }

        // No map found where all players fit the matchups
        return null;
    }

    private List<QueuedEntry> allocatePlayersForMatchup(ElementMatchup matchup,
                                                        List<QueuedEntry> batch,
                                                        int minPlayersPerTeam) {
        if (matchup.isConstraint()) {
            return allocateConstraintMatchup(matchup, batch);
        }
        if (matchup.isComposition()) {
            return allocateCompositionMatchup(matchup, batch);
        }
        return allocateLegacyMatchup(matchup, batch, minPlayersPerTeam);
    }

    private List<QueuedEntry> allocateLegacyMatchup(ElementMatchup matchup,
                                                    List<QueuedEntry> batch,
                                                    int minPlayersPerTeam) {
        Elements leftElement = matchup.leftElements().get(0);
        Elements rightElement = matchup.rightElements().get(0);

        int leftCount = 0;
        int rightCount = 0;
        int flexible = 0;
        int required = Math.max(1, minPlayersPerTeam);

        for (QueuedEntry queued : batch) {
            SerializedPlayer sp = queued.player();
            if (sp.getElements().isEmpty()) {
                return null;
            }

            boolean matchesLeft = sp.getElements().contains(leftElement);
            boolean matchesRight = sp.getElements().contains(rightElement);

            if (!matchesLeft && !matchesRight) {
                return null;
            }

            if (matchesLeft && matchesRight) {
                flexible++;
            } else if (matchesLeft) {
                leftCount++;
            } else if (matchesRight) {
                rightCount++;
            }
        }

        int leftShortfall = Math.max(0, required - leftCount);
        int rightShortfall = Math.max(0, required - rightCount);
        int totalFlexibleNeeded = leftShortfall + rightShortfall;
        if (flexible < totalFlexibleNeeded) {
            return null;
        }

        if ((leftCount + flexible) >= required && (rightCount + flexible) >= required) {
            return new ArrayList<>(batch);
        }

        return null;
    }

    private List<QueuedEntry> allocateCompositionMatchup(ElementMatchup matchup,
                                                         List<QueuedEntry> batch) {
        List<Elements> leftSlots = matchup.leftElements();
        List<Elements> rightSlots = matchup.rightElements();
        if (leftSlots.size() != rightSlots.size()) {
            log.warn("Ignoring matchup {} due to uneven sides ({} vs {})", matchup, leftSlots.size(), rightSlots.size());
            return null;
        }

        int totalSlots = leftSlots.size() + rightSlots.size();
        if (totalSlots > batch.size()) {
            return null;
        }

        List<Slot> slots = new ArrayList<>(totalSlots);
        for (Elements element : leftSlots) {
            slots.add(new Slot(element));
        }
        for (Elements element : rightSlots) {
            slots.add(new Slot(element));
        }

        slots.sort(Comparator.comparingInt(slot -> countEligiblePlayersForSlot(slot, batch)));

        Map<Integer, QueuedEntry> assignment = new HashMap<>();
        Set<QueuedEntry> used = new HashSet<>();
        if (!assignSlot(0, slots, batch, assignment, used)) {
            return null;
        }

        Set<QueuedEntry> selected = new HashSet<>(assignment.values());
        List<QueuedEntry> ordered = new ArrayList<>();
        for (QueuedEntry entry : batch) {
            if (selected.contains(entry)) {
                ordered.add(entry);
            }
        }
        return ordered;
    }

    private List<QueuedEntry> allocateConstraintMatchup(ElementMatchup matchup,
                                                        List<QueuedEntry> batch) {
        int teamSize = matchup.constraintTeamSize();
        if (teamSize <= 0) {
            return null;
        }

        int requiredPlayers = teamSize * 2;
        if (batch.size() < requiredPlayers) {
            return null;
        }

        Map<Elements, Integer> limits = normalizeConstraintLimits(matchup.constraintMaxPerElement(), teamSize);
        List<QueuedEntry> candidates = new ArrayList<>(batch);
        candidates.sort(Comparator.comparingInt(entry -> entry.player().getElements().size()));

        List<QueuedEntry> selected = new ArrayList<>(requiredPlayers);
        if (buildConstraintTeams(candidates, teamSize, limits, selected)) {
            return selected;
        }

        return null;
    }

    private Map<Elements, Integer> normalizeConstraintLimits(Map<Elements, Integer> raw, int teamSize) {
        EnumMap<Elements, Integer> limits = new EnumMap<>(Elements.class);
        if (raw != null) {
            for (Map.Entry<Elements, Integer> entry : raw.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }

                int limit = Math.max(0, Math.min(teamSize, entry.getValue()));
                if (limit == 0) {
                    continue;
                }
                limits.put(entry.getKey(), limit);
            }
        }
        return limits;
    }

    private boolean buildConstraintTeams(List<QueuedEntry> candidates,
                                         int teamSize,
                                         Map<Elements, Integer> limits,
                                         List<QueuedEntry> selection) {
        Set<QueuedEntry> used = new HashSet<>();
        List<QueuedEntry> teamOne = new ArrayList<>(teamSize);
        Map<Elements, Integer> teamOneCounts = new EnumMap<>(Elements.class);
        return fillFirstTeam(candidates, teamSize, limits, selection, used, teamOne, teamOneCounts);
    }

    private boolean fillFirstTeam(List<QueuedEntry> candidates,
                                  int teamSize,
                                  Map<Elements, Integer> limits,
                                  List<QueuedEntry> selection,
                                  Set<QueuedEntry> used,
                                  List<QueuedEntry> currentTeam,
                                  Map<Elements, Integer> counts) {
        if (currentTeam.size() == teamSize) {
            List<QueuedEntry> teamTwo = new ArrayList<>(teamSize);
            Map<Elements, Integer> teamTwoCounts = new EnumMap<>(Elements.class);
            if (fillTeam(candidates, teamSize, limits, teamTwo, used, teamTwoCounts)) {
                selection.addAll(currentTeam);
                selection.addAll(teamTwo);
                return true;
            }
            return false;
        }

        for (QueuedEntry entry : candidates) {
            if (used.contains(entry)) {
                continue;
            }
            SerializedPlayer player = entry.player();
            if (player.getElements().isEmpty()) {
                continue;
            }

            for (Elements element : player.getElements()) {
                if (!canUseElement(element, teamSize, limits, counts)) {
                    continue;
                }

                used.add(entry);
                currentTeam.add(entry);
                incrementElementCount(counts, element);

                if (fillFirstTeam(candidates, teamSize, limits, selection, used, currentTeam, counts)) {
                    return true;
                }

                decrementElementCount(counts, element);
                currentTeam.remove(currentTeam.size() - 1);
                used.remove(entry);
            }
        }

        return false;
    }

    private boolean fillTeam(List<QueuedEntry> candidates,
                             int teamSize,
                             Map<Elements, Integer> limits,
                             List<QueuedEntry> team,
                             Set<QueuedEntry> used,
                             Map<Elements, Integer> counts) {
        if (team.size() == teamSize) {
            return true;
        }

        for (QueuedEntry entry : candidates) {
            if (used.contains(entry)) {
                continue;
            }
            SerializedPlayer player = entry.player();
            if (player.getElements().isEmpty()) {
                continue;
            }

            for (Elements element : player.getElements()) {
                if (!canUseElement(element, teamSize, limits, counts)) {
                    continue;
                }

                used.add(entry);
                team.add(entry);
                incrementElementCount(counts, element);

                if (fillTeam(candidates, teamSize, limits, team, used, counts)) {
                    return true;
                }

                decrementElementCount(counts, element);
                team.remove(team.size() - 1);
                used.remove(entry);
            }
        }

        return false;
    }

    private boolean canUseElement(Elements element,
                                  int teamSize,
                                  Map<Elements, Integer> limits,
                                  Map<Elements, Integer> counts) {
        int limit = limits.getOrDefault(element, teamSize);
        if (limit <= 0) {
            return false;
        }

        return counts.getOrDefault(element, 0) < limit;
    }

    private void incrementElementCount(Map<Elements, Integer> counts, Elements element) {
        counts.put(element, counts.getOrDefault(element, 0) + 1);
    }

    private void decrementElementCount(Map<Elements, Integer> counts, Elements element) {
        int current = counts.getOrDefault(element, 0);
        if (current <= 1) {
            counts.remove(element);
        } else {
            counts.put(element, current - 1);
        }
    }

    private int countEligiblePlayersForSlot(Slot slot, List<QueuedEntry> batch) {
        int count = 0;
        for (QueuedEntry entry : batch) {
            if (playerHasElement(entry.player(), slot.requiredElement)) {
                count++;
            }
        }
        return count;
    }

    private boolean assignSlot(int index,
                               List<Slot> slots,
                               List<QueuedEntry> batch,
                               Map<Integer, QueuedEntry> assignment,
                               Set<QueuedEntry> used) {
        if (index >= slots.size()) {
            return true;
        }

        Slot slot = slots.get(index);
        for (QueuedEntry entry : batch) {
            if (used.contains(entry)) {
                continue;
            }
            if (!playerHasElement(entry.player(), slot.requiredElement)) {
                continue;
            }

            used.add(entry);
            assignment.put(index, entry);
            if (assignSlot(index + 1, slots, batch, assignment, used)) {
                return true;
            }
            used.remove(entry);
            assignment.remove(index);
        }

        return false;
    }

    private boolean playerHasElement(SerializedPlayer player, Elements element) {
        return player.getElements().contains(element);
    }

    private void sendQueueMatch(BattleArena plugin,
                                Arena arena,
                                String mapName,
                                List<QueuedEntry> batch) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "queue_match");
        payload.addProperty("arena", arena.getName());
        payload.addProperty("map", mapName);
        String sharedOrigin = sharedOrigin(batch);
        if (!sharedOrigin.isEmpty()) {
            payload.addProperty("origin", sharedOrigin);
        }

        JsonArray playersArray = new JsonArray();
        for (QueuedEntry queued : batch) {
            SerializedPlayer sp = queued.player();
            JsonObject playerObject = new JsonObject();
            playerObject.addProperty("uuid", sp.getUuid());
            if (!queued.origin().isEmpty()) {
                playerObject.addProperty("origin", queued.origin());
            }

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

    private int totalQueuedPlayers(Map<String, Deque<SerializedPlayer>> byOrigin) {
        int total = 0;
        for (Deque<SerializedPlayer> queue : byOrigin.values()) {
            synchronized (queue) {
                total += queue.size();
            }
        }
        return total;
    }

    private List<QueuedEntry> takePlayerBatch(Map<String, Deque<SerializedPlayer>> byOrigin, int maxPlayers) {
        List<QueuedEntry> batch = new ArrayList<>();
        if (maxPlayers <= 0) {
            return batch;
        }

        List<Map.Entry<String, Deque<SerializedPlayer>>> origins = new ArrayList<>(byOrigin.entrySet());
        while (batch.size() < maxPlayers) {
            boolean progressed = false;
            for (Map.Entry<String, Deque<SerializedPlayer>> originEntry : origins) {
                Deque<SerializedPlayer> queue = originEntry.getValue();
                SerializedPlayer next = null;
                synchronized (queue) {
                    if (!queue.isEmpty()) {
                        next = queue.removeFirst();
                    }
                }

                if (next != null) {
                    batch.add(new QueuedEntry(originEntry.getKey(), next));
                    progressed = true;
                    if (batch.size() >= maxPlayers) {
                        break;
                    }
                }
            }

            if (!progressed) {
                break;
            }
        }

        return batch;
    }

    private void requeueEntries(Map<String, Deque<SerializedPlayer>> byOrigin, List<QueuedEntry> entries) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            QueuedEntry entry = entries.get(i);
            Deque<SerializedPlayer> queue = byOrigin.computeIfAbsent(entry.origin(), k -> new ArrayDeque<>());
            synchronized (queue) {
                queue.addFirst(entry.player());
            }
        }
    }

    private String sharedOrigin(List<QueuedEntry> entries) {
        String shared = null;
        for (QueuedEntry entry : entries) {
            String origin = entry.origin();
            if (origin == null || origin.isEmpty()) {
                return "";
            }

            if (shared == null) {
                shared = origin;
            } else if (!shared.equals(origin)) {
                return "";
            }
        }

        return shared == null ? "" : shared;
    }

    boolean isLocallyQueued(UUID playerId) {
        return this.localQueued.contains(playerId);
    }

    boolean addLocalQueue(UUID playerId) {
        boolean added = this.localQueued.add(playerId);
        if (added) {
            this.queueStartTimes.put(playerId, System.currentTimeMillis());
        }
        return added;
    }

    boolean removeLocalQueue(UUID playerId) {
        boolean removed = this.localQueued.remove(playerId);
        if (removed) {
            this.queueStartTimes.remove(playerId);
        }
        return removed;
    }

    @Override
    public boolean leaveQueue(Arena arena, Player player) {
        if (arena == null || player == null || !arena.isModuleEnabled(ID)) {
            return false;
        }

        BattleArena plugin = arena.getPlugin();
        if (!plugin.getMainConfig().isProxySupport()) {
            return false;
        }

        UUID playerId = player.getUniqueId();
        Long previousStart = this.queueStartTimes.get(playerId);
        if (!this.removeLocalQueue(playerId)) {
            return false;
        }

        plugin.removePendingProxyJoin(playerId);

        if (plugin.getMainConfig().isProxyHost()) {
            removeFromQueues(playerId.toString());
            return true;
        }

        if (plugin.getConnector() == null) {
            // Failed to notify the proxy host; revert local state.
            this.localQueued.add(playerId);
            if (previousStart != null) {
                this.queueStartTimes.put(playerId, previousStart);
            }
            return false;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("type", "queue_leave");
        payload.addProperty("uuid", playerId.toString());

        String origin = plugin.getMainConfig().getProxyServerName();
        if (origin != null && !origin.isEmpty()) {
            payload.addProperty("origin", origin);
        }

        plugin.getConnector().sendToRouter(payload.toString());
        return true;
    }

    @Override
    public Optional<Duration> getQueueDuration(UUID playerId) {
        Long queuedAt = this.queueStartTimes.get(playerId);
        if (queuedAt == null) {
            return Optional.empty();
        }

        long elapsed = System.currentTimeMillis() - queuedAt;
        if (elapsed < 0) {
            elapsed = 0;
        }

        return Optional.of(Duration.ofMillis(elapsed));
    }
}
