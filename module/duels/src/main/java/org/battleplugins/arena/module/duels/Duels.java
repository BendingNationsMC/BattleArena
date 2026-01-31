package org.battleplugins.arena.module.duels;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.battleplugins.arena.Arena;
import org.battleplugins.arena.ArenaPlayer;
import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.competition.Competition;
import org.battleplugins.arena.competition.JoinResult;
import org.battleplugins.arena.competition.LiveCompetition;
import org.battleplugins.arena.competition.PlayerRole;
import org.battleplugins.arena.competition.map.LiveCompetitionMap;
import org.battleplugins.arena.competition.map.MapType;
import org.battleplugins.arena.competition.map.options.Spawns;
import org.battleplugins.arena.competition.map.options.TeamSpawns;
import org.battleplugins.arena.competition.team.TeamManager;
import org.battleplugins.arena.stat.ArenaStats;
import org.battleplugins.arena.util.PositionWithRotation;
import org.battleplugins.arena.event.arena.ArenaCreateExecutorEvent;
import org.battleplugins.arena.event.arena.ArenaDrawEvent;
import org.battleplugins.arena.event.arena.ArenaLoseEvent;
import org.battleplugins.arena.event.arena.ArenaVictoryEvent;
import org.battleplugins.arena.event.player.ArenaDeathEvent;
import org.battleplugins.arena.event.player.ArenaLeaveEvent;
import org.battleplugins.arena.event.player.ArenaPreJoinEvent;
import org.battleplugins.arena.feature.party.Parties;
import org.battleplugins.arena.feature.party.Party;
import org.battleplugins.arena.feature.party.PartyMember;
import org.battleplugins.arena.duel.DuelSeriesProvider;
import org.battleplugins.arena.messages.Messages;
import org.battleplugins.arena.competition.phase.CompetitionPhaseType;
import org.battleplugins.arena.competition.phase.LiveCompetitionPhase;
import org.battleplugins.arena.competition.phase.phases.VictoryPhase;
import org.battleplugins.arena.module.ArenaModule;
import org.battleplugins.arena.module.ArenaModuleInitializer;
import org.battleplugins.arena.proxy.ProxyDuelRequestEvent;
import org.battleplugins.arena.proxy.SerializedPlayer;
import org.battleplugins.arena.team.ArenaTeam;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.Nullable;

import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A module that adds duels to BattleArena.
 */
@ArenaModule(id = Duels.ID, name = "Duels", description = "Adds duels to BattleArena.", authors = "BattlePlugins")
public class Duels implements ArenaModuleInitializer, DuelSeriesProvider {
    public static final String ID = "duels";
    public static final JoinResult PENDING_REQUEST = new JoinResult(false, DuelsMessages.PENDING_DUEL_REQUEST);

    private final Map<UUID, DuelRequest> duelRequestsByRequester = new HashMap<>();
    private final Map<UUID, DuelRequest> duelRequestsByTarget = new HashMap<>();
    private final Map<UUID, String> duelOrigins = new ConcurrentHashMap<>();
    private final Set<Competition<?>> duelCompetitions = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<Competition<?>, Map<UUID, String>> duelParticipants = new ConcurrentHashMap<>();
    private final Map<Competition<?>, Map<UUID, String>> duelTeamsByCompetition = new ConcurrentHashMap<>();
    private final Map<Competition<?>, Map<UUID, String>> duelLosers = new ConcurrentHashMap<>();
    private final Map<Competition<?>, Set<String>> duelOriginsByCompetition = new ConcurrentHashMap<>();
    private final Set<Competition<?>> announcedDuels = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<Competition<?>, DuelSeries> duelSeriesByCompetition = new ConcurrentHashMap<>();

    static final class DuelRequest {
        private final UUID requester;
        private final UUID target;
        private final List<UUID> requesterParty;
        private final List<UUID> targetParty;
        private final @Nullable String preferredMap;
        private final int rounds;

        private DuelRequest(UUID requester,
                            UUID target,
                            List<UUID> requesterParty,
                            List<UUID> targetParty,
                            @Nullable String preferredMap,
                            int rounds) {
            this.requester = requester;
            this.target = target;
            this.requesterParty = requesterParty;
            this.targetParty = targetParty;
            this.preferredMap = preferredMap;
            this.rounds = normalizeRounds(rounds);
        }

        UUID getRequester() {
            return this.requester;
        }

        UUID getTarget() {
            return this.target;
        }

        List<UUID> getRequesterParty() {
            return this.requesterParty;
        }

        List<UUID> getTargetParty() {
            return this.targetParty;
        }

        @Nullable
        String getPreferredMap() {
            return this.preferredMap;
        }

        int getRounds() {
            return this.rounds;
        }
    }

    static final class DuelSeries {
        private final UUID requester;
        private final UUID target;
        private final List<UUID> requesterParty;
        private final List<UUID> targetParty;
        private final @Nullable String preferredMapName;
        private final int totalRounds;
        private final int winsNeeded;
        private int requesterWins;
        private int targetWins;
        private int roundsPlayed;
        private @Nullable String lockedMapName;

        private DuelSeries(UUID requester,
                           UUID target,
                           List<UUID> requesterParty,
                           List<UUID> targetParty,
                           @Nullable String preferredMapName,
                           int totalRounds) {
            this.requester = requester;
            this.target = target;
            this.requesterParty = List.copyOf(requesterParty);
            this.targetParty = List.copyOf(targetParty);
            this.preferredMapName = preferredMapName;
            this.totalRounds = normalizeRounds(totalRounds);
            this.winsNeeded = (this.totalRounds / 2) + 1;
        }

        private List<UUID> getRequesterParty() {
            return this.requesterParty;
        }

        private List<UUID> getTargetParty() {
            return this.targetParty;
        }

        private void recordWin(UUID winner) {
            this.roundsPlayed++;
            if (this.requesterParty.contains(winner)) {
                this.requesterWins++;
            } else if (this.targetParty.contains(winner)) {
                this.targetWins++;
            }
        }

        private void recordDraw() {
            this.roundsPlayed++;
        }

        private boolean isComplete() {
            return this.requesterWins >= this.winsNeeded || this.targetWins >= this.winsNeeded;
        }

        private @Nullable String getMapName() {
            return this.preferredMapName != null ? this.preferredMapName : this.lockedMapName;
        }

        private void lockMapName(String mapName) {
            if (this.preferredMapName == null && this.lockedMapName == null) {
                this.lockedMapName = mapName;
            }
        }
    }

    private static final class ProxyDuel {
        private final Arena arena;
        private final UUID requester;
        private final UUID target;
        private final List<UUID> requesterParty;
        private final List<UUID> targetParty;
        private final Map<UUID, SerializedPlayer> serializedPlayers;
        private final @Nullable String mapName;
        private final int rounds;

        private ProxyDuel(Arena arena,
                          UUID requester,
                          UUID target,
                          Collection<UUID> requesterParty,
                          Collection<UUID> targetParty,
                          Collection<SerializedPlayer> serializedPlayers,
                          @Nullable String mapName,
                          int rounds) {
            this.arena = arena;
            this.requester = requester;
            this.target = target;
            this.requesterParty = normalizeRoster(requester, requesterParty);
            this.targetParty = normalizeRoster(target, targetParty);
            this.mapName = mapName;
            this.rounds = normalizeRounds(rounds);
            if (serializedPlayers != null && !serializedPlayers.isEmpty()) {
                Map<UUID, SerializedPlayer> players = new LinkedHashMap<>();
                for (SerializedPlayer serializedPlayer : serializedPlayers) {
                    if (serializedPlayer == null) {
                        continue;
                    }

                    try {
                        UUID id = UUID.fromString(serializedPlayer.getUuid());
                        players.put(id, serializedPlayer);
                    } catch (IllegalArgumentException ignored) {
                    }
                }

                this.serializedPlayers = Map.copyOf(players);
            } else {
                this.serializedPlayers = Collections.emptyMap();
            }
        }

        private static List<UUID> normalizeRoster(UUID leader, Collection<UUID> roster) {
            LinkedHashSet<UUID> ordered = new LinkedHashSet<>();
            ordered.add(leader);
            if (roster != null) {
                ordered.addAll(roster);
            }

            return List.copyOf(ordered);
        }

        private List<UUID> getRequesterParty() {
            return requesterParty;
        }

        private List<UUID> getTargetParty() {
            return targetParty;
        }

        private Collection<SerializedPlayer> getSerializedPlayers() {
            return serializedPlayers.values();
        }

        private Collection<UUID> allParticipants() {
            LinkedHashSet<UUID> all = new LinkedHashSet<>(requesterParty);
            all.addAll(targetParty);
            return all;
        }

        private @Nullable String getMapName() {
            return this.mapName;
        }

        private int getRounds() {
            return this.rounds;
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
        UUID playerId = event.getPlayer().getUniqueId();

        DuelRequest outgoing = this.duelRequestsByRequester.remove(playerId);
        if (outgoing != null) {
            this.duelRequestsByTarget.remove(outgoing.getTarget());

            Player targetPlayer = Bukkit.getPlayer(outgoing.getTarget());
            if (targetPlayer != null) {
                DuelsMessages.DUEL_REQUESTED_CANCELLED_QUIT.send(targetPlayer, event.getPlayer().getName());
            }
        }

        DuelRequest incoming = this.duelRequestsByTarget.remove(playerId);
        if (incoming != null) {
            this.duelRequestsByRequester.remove(incoming.getRequester());

            Player requesterPlayer = Bukkit.getPlayer(incoming.getRequester());
            if (requesterPlayer != null) {
                DuelsMessages.DUEL_TARGET_CANCELLED_QUIT.send(requesterPlayer, event.getPlayer().getName());
            }
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
        if (this.duelRequestsByRequester.containsKey(event.getPlayer().getUniqueId())) {
            event.setResult(PENDING_REQUEST);
        }
    }

    @EventHandler
    public void onArenaLeave(ArenaLeaveEvent event) {
        BattleArena plugin = event.getArena().getPlugin();
        if (!plugin.getMainConfig().isProxySupport() || !plugin.getMainConfig().isProxyHost()) {
            return;
        }

        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        String origin = this.duelOrigins.remove(player.getUniqueId());
        if (origin != null && !origin.isEmpty()) {
            plugin.sendPlayerToServer(player, origin);
        }
    }

    @EventHandler
    public void onArenaLose(ArenaLoseEvent event) {
        Competition<?> competition = event.getCompetition();
        if (!this.isTrackedDuel(competition)) {
            return;
        }

        Map<UUID, String> losers = new LinkedHashMap<>();
        for (ArenaPlayer arenaPlayer : event.getLosers()) {
            Player player = arenaPlayer.getPlayer();
            if (player != null) {
                losers.put(player.getUniqueId(), player.getName());
            }
        }

        if (!losers.isEmpty()) {
            this.duelLosers.put(competition, losers);
        }
    }

    @EventHandler
    public void onArenaVictory(ArenaVictoryEvent event) {
        Competition<?> competition = event.getCompetition();
        if (!this.isTrackedDuel(competition)) {
            return;
        }

        Player winner = event.getVictors()
                .stream()
                .map(ArenaPlayer::getPlayer)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (winner == null) {
            return;
        }

        DuelSeries series = this.duelSeriesByCompetition.get(competition);
        if (series != null) {
            series.recordWin(winner.getUniqueId());
            this.sendSeriesScore(competition, series);
            event.getArena().getPlugin().info(
                    "Duel series victory: winner={}, winsNeeded={}, requesterWins={}, targetWins={}",
                    winner.getName(),
                    series.winsNeeded,
                    series.requesterWins,
                    series.targetWins
            );
            if (!series.isComplete()) {
                this.restartSeriesRound(event.getArena(), competition);
                return;
            }
        }

        if (!this.announcedDuels.add(competition)) {
            return;
        }

        String loserName = this.resolveLoserName(competition, winner.getUniqueId());
        String message = DuelsMessages.DUEL_RESULT_BROADCAST
                .withContext(winner.getName(), loserName, this.formatHeartsRemaining(winner.getHealth()))
                .asMiniMessage();

        this.broadcastDuelResult(event.getArena(), competition, message);
        this.cleanupDuelTracking(competition);
    }

    @EventHandler
    public void onArenaDraw(ArenaDrawEvent event) {
        Competition<?> competition = event.getCompetition();
        if (!this.isTrackedDuel(competition)) {
            return;
        }

        DuelSeries series = this.duelSeriesByCompetition.get(competition);      
        if (series != null && !series.isComplete()) {
            series.recordDraw();
            this.sendSeriesScore(competition, series);
            this.restartSeriesRound(event.getArena(), competition);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArenaDeath(ArenaDeathEvent event) {
        Competition<?> competition = event.getCompetition();
        if (!this.isTrackedDuel(competition)) {
            return;
        }

        DuelSeries series = this.duelSeriesByCompetition.get(competition);
        if (series == null || series.isComplete()) {
            return;
        }

        if (!(competition instanceof LiveCompetition<?> liveCompetition)) {
            return;
        }

        AliveSnapshot snapshot = this.snapshotAlive(liveCompetition);
        int victors = liveCompetition.getVictoryManager().identifyPotentialVictors().size();
        String phaseName = liveCompetition.getPhaseManager().getCurrentPhase().getType().getName();
        ArenaPlayer deadPlayer = event.getArenaPlayer();
        event.getArena().getPlugin().info(
                "Duel series death: phase={}, aliveTeams={}, alivePlayers={}, totalPlayers={}, victors={}, deadRole={}",
                phaseName,
                snapshot.aliveTeams,
                snapshot.alivePlayers,
                liveCompetition.getPlayers().size(),
                victors,
                deadPlayer.getRole()
        );

        if (snapshot.aliveTeams <= 1) {
            this.ensureSeriesVictory(event.getArena(), liveCompetition);    
        }
    }


    @EventHandler
    public void onProxyDuelRequest(ProxyDuelRequestEvent event) {
        if (!event.getArena().isModuleEnabled(ID)) {
            return;
        }

        this.handleProxyDuelRequest(event);
    }

    public boolean hasOutgoingRequest(UUID player) {
        return this.duelRequestsByRequester.containsKey(player);
    }

    public boolean hasIncomingRequest(UUID player) {
        return this.duelRequestsByTarget.containsKey(player);
    }

    public @Nullable DuelRequest getOutgoingRequest(UUID requester) {
        return this.duelRequestsByRequester.get(requester);
    }

    public @Nullable DuelRequest getIncomingRequest(UUID target) {
        return this.duelRequestsByTarget.get(target);
    }

    public void addDuelRequest(Player requester, Player target) {
        this.addDuelRequest(requester, target, null);
    }

    public void addDuelRequest(Player requester, Player target, @Nullable String preferredMap) {
        this.addDuelRequest(requester, target, preferredMap, 1);
    }

    public void addDuelRequest(Player requester, Player target, @Nullable String preferredMap, int rounds) {
        DuelRequest request = new DuelRequest(
                requester.getUniqueId(),
                target.getUniqueId(),
                this.capturePartyRoster(requester),
                this.capturePartyRoster(target),
                preferredMap,
                rounds
        );

        this.duelRequestsByRequester.put(request.getRequester(), request);
        this.duelRequestsByTarget.put(request.getTarget(), request);
    }

    public void removeDuelRequest(@Nullable DuelRequest request) {
        if (request == null) {
            return;
        }

        this.duelRequestsByRequester.remove(request.getRequester());
        this.duelRequestsByTarget.remove(request.getTarget());
    }

    public void acceptDuel(Arena arena, Player requester, Player opponent) {
        this.acceptDuel(arena, requester, opponent, this.capturePartyRoster(requester), this.capturePartyRoster(opponent), null);
    }

    public void acceptDuel(Arena arena,
                           Player requester,
                           Player opponent,
                           @Nullable Collection<UUID> requesterRoster,
                           @Nullable Collection<UUID> opponentRoster) {
        this.acceptDuel(arena, requester, opponent, requesterRoster, opponentRoster, null);
    }

    public void acceptDuel(Arena arena,
                           Player requester,
                           Player opponent,
                           @Nullable Collection<UUID> requesterRoster,
                           @Nullable Collection<UUID> opponentRoster,
                           @Nullable String preferredMapName) {
        this.acceptDuel(arena, requester, opponent, requesterRoster, opponentRoster, preferredMapName, 1);
    }

    public void acceptDuel(Arena arena,
                           Player requester,
                           Player opponent,
                           @Nullable Collection<UUID> requesterRoster,
                           @Nullable Collection<UUID> opponentRoster,
                           @Nullable String preferredMapName,
                           int rounds) {
        DuelSeries series = null;
        if (rounds > 1) {
            List<UUID> requesterParty = normalizeRoster(requester.getUniqueId(), requesterRoster);
            List<UUID> opponentParty = normalizeRoster(opponent.getUniqueId(), opponentRoster);
            series = new DuelSeries(requester.getUniqueId(), opponent.getUniqueId(), requesterParty, opponentParty, preferredMapName, rounds);
            requesterRoster = requesterParty;
            opponentRoster = opponentParty;
        }

        this.acceptDuel(arena, requester, opponent, requesterRoster, opponentRoster, preferredMapName, series);
    }

    private void acceptDuel(Arena arena,
                            Player requester,
                            Player opponent,
                            @Nullable Collection<UUID> requesterRoster,
                            @Nullable Collection<UUID> opponentRoster,
                            @Nullable String preferredMapName,
                            @Nullable DuelSeries series) {
        BattleArena plugin = arena.getPlugin();
        boolean proxySupport = plugin.getMainConfig().isProxySupport();
        boolean proxyHost = plugin.getMainConfig().isProxyHost();

        Set<Player> requesterParty = this.collectPartyParticipants(plugin, requester, requesterRoster);
        if (requesterParty == null) {
            return;
        }

        Set<Player> opponentParty = this.collectPartyParticipants(plugin, opponent, opponentRoster);
        if (opponentParty == null) {
            return;
        }

        Set<Player> allParticipants = new LinkedHashSet<>();
        allParticipants.addAll(requesterParty);
        allParticipants.addAll(opponentParty);

        LiveCompetitionMap preferredMap = null;
        if (preferredMapName != null) {
            preferredMap = plugin.getMap(arena, preferredMapName);
            if (preferredMap == null || preferredMap.getType() != MapType.DYNAMIC) {
                allParticipants.forEach(player -> Messages.ARENA_ERROR.send(player, "Selected duel map is no longer available."));
                return;
            }

            if (proxySupport && proxyHost && !preferredMap.isRemote()) {
                allParticipants.forEach(player -> Messages.ARENA_ERROR.send(player, "Selected duel map is not available on the proxy host."));
                return;
            }
        }

        // If this server is not the proxy host but proxy support is enabled, we should
        // forward the duel to the host without requiring a local competition.
        if (proxySupport && !proxyHost) {
            for (Player participant : allParticipants) {
                if (plugin.isPendingProxyJoin(participant.getUniqueId())) {
                    Messages.LOADING_MAP.send(participant);
                    return;
                }
            }

            LiveCompetitionMap targetMap = preferredMap != null ? preferredMap : this.selectFallbackMap(plugin, arena, false);
            if (targetMap == null) {
                allParticipants.forEach(Messages.NO_OPEN_ARENAS::send);
                return;
            }

            int rounds = series != null ? series.totalRounds : 1;
            this.sendProxyDuelRequest(plugin, arena, targetMap, requester, opponent, requesterParty, opponentParty, allParticipants, rounds);

            return;
        }

        // Local or proxy host: find or create an open competition and join immediately.
        LiveCompetition<?> competition = findOrJoinCompetition(arena, preferredMap);
        if (competition == null) {
            allParticipants.forEach(Messages.NO_OPEN_ARENAS::send);
            return;
        }

        TeamManager teamManager = competition.getTeamManager();
        if (teamManager.getTeams().size() < 2) {
            allParticipants.forEach(player -> DuelsMessages.NOT_ENOUGH_TEAM_SPAWNS.send(player, arena.getName()));
            return;
        }

        Set<ArenaTeam> reservedTeams = new HashSet<>();
        ArenaTeam requesterTeam = this.findTeamForParty(teamManager, requesterParty, reservedTeams);
        if (requesterTeam == null) {
            allParticipants.forEach(player -> DuelsMessages.PARTY_TOO_LARGE_FOR_TEAM.send(player, requester.getName(), String.valueOf(requesterParty.size())));
            return;
        }
        reservedTeams.add(requesterTeam);

        ArenaTeam opponentTeam = this.findTeamForParty(teamManager, opponentParty, reservedTeams);
        if (opponentTeam == null) {
            allParticipants.forEach(player -> DuelsMessages.PARTY_TOO_LARGE_FOR_TEAM.send(player, opponent.getName(), String.valueOf(opponentParty.size())));
            return;
        }

        competition.join(requesterParty, PlayerRole.PLAYING, requesterTeam);
        competition.join(opponentParty, PlayerRole.PLAYING, opponentTeam);

        this.ensurePartyOnTeam(teamManager, requesterParty, requesterTeam);
        this.ensurePartyOnTeam(teamManager, opponentParty, opponentTeam);
        this.trackDuel(competition, allParticipants, series);
    }

    public void handleProxyDuelRequest(ProxyDuelRequestEvent event) {
        ProxyDuel duel = new ProxyDuel(event.getArena(), event.getRequesterUuid(), event.getTargetUuid(), event.getRequesterPartyMembers(), event.getTargetPartyMembers(), event.getPlayers(), event.getMapName(), event.getRounds());
        duel.allParticipants().forEach(id -> this.proxyDuels.put(id, duel));
        String origin = event.getOriginServer();
        if (origin != null && !origin.isEmpty()) {
            duel.allParticipants().forEach(id -> this.duelOrigins.put(id, origin));
        }

        this.tryStartProxyDuel(duel);
    }

    private void tryStartProxyDuel(ProxyDuel duel) {
        Player requesterPlayer = Bukkit.getPlayer(duel.requester);
        Player targetPlayer = Bukkit.getPlayer(duel.target);
        if (requesterPlayer == null || targetPlayer == null) {
            return;
        }

        for (UUID participant : duel.allParticipants()) {
            if (Bukkit.getPlayer(participant) == null) {
                return;
            }
        }

        // Both players are now present on the proxy host server.
        duel.allParticipants().forEach(this.proxyDuels::remove);
        duel.getSerializedPlayers().forEach(serialized -> {
            Player player = Bukkit.getPlayer(UUID.fromString(serialized.getUuid()));
            if (player != null) {
                serialized.start(player);
            }
        });

        this.acceptDuel(duel.arena, requesterPlayer, targetPlayer, duel.getRequesterParty(), duel.getTargetParty(), duel.getMapName(), duel.getRounds());
    }

    private void sendProxyDuelRequest(BattleArena plugin,
                                      Arena arena,
                                      LiveCompetitionMap map,
                                      Player requester,
                                      Player opponent,
                                      Set<Player> requesterParty,
                                      Set<Player> opponentParty,
                                      Set<Player> allParticipants) {
        this.sendProxyDuelRequest(plugin, arena, map, requester, opponent, requesterParty, opponentParty, allParticipants, 1);
    }

    private void sendProxyDuelRequest(BattleArena plugin,
                                      Arena arena,
                                      LiveCompetitionMap map,
                                      Player requester,
                                      Player opponent,
                                      Set<Player> requesterParty,
                                      Set<Player> opponentParty,
                                      Set<Player> allParticipants,
                                      int rounds) {
        if (plugin.getConnector() == null) {
            plugin.warn("Cannot proxy duel for arena {} - connector not available.", arena.getName());
            allParticipants.forEach(player ->
                    Messages.ARENA_ERROR.send(player, "Cannot proxy duel right now. Please try again later.")
            );
            return;
        }

        JsonObject joinPayload = new JsonObject();
        joinPayload.addProperty("type", "arena_join");
        joinPayload.addProperty("arena", arena.getName());
        joinPayload.addProperty("map", map.getName());
        joinPayload.addProperty("duel", true);

        JsonArray playerData = new JsonArray();
        requesterParty.forEach(participant -> playerData.add(this.serializePlayer(participant)));
        opponentParty.forEach(participant -> playerData.add(this.serializePlayer(participant)));
        joinPayload.add("players", playerData);

        String origin = plugin.getMainConfig().getProxyServerName();
        if (origin != null && !origin.isEmpty()) {
            joinPayload.addProperty("origin", origin);
        }

        plugin.getConnector().sendToRouter(joinPayload.toString());

        JsonObject duelPayload = new JsonObject();
        duelPayload.addProperty("type", "duel_req");
        duelPayload.addProperty("arena", arena.getName());
        duelPayload.addProperty("map", map.getName());
        duelPayload.add("requester", this.serializePlayer(requester));
        duelPayload.add("target", this.serializePlayer(opponent));
        duelPayload.add("requesterParty", this.serializeRoster(requesterParty));
        duelPayload.add("targetParty", this.serializeRoster(opponentParty));
        duelPayload.add("players", playerData.deepCopy());
        duelPayload.addProperty("rounds", normalizeRounds(rounds));

        if (origin != null && !origin.isEmpty()) {
            duelPayload.addProperty("origin", origin);
        }

        plugin.getConnector().sendToRouter(duelPayload.toString());

        for (Player participant : allParticipants) {
            plugin.addPendingProxyJoin(participant.getUniqueId());
            Messages.LOADING_MAP.send(participant);
        }
    }

    private JsonArray serializeRoster(Collection<Player> players) {
        JsonArray array = new JsonArray();
        players.forEach(player -> array.add(player.getUniqueId().toString()));
        return array;
    }

    private LiveCompetition<?> findOrJoinCompetition(Arena arena, @Nullable LiveCompetitionMap preferredMap) {
        BattleArena plugin = arena.getPlugin();

        List<Competition<?>> openCompetitions = plugin.getCompetitions(arena)
                .stream()
                .filter(competition -> competition instanceof LiveCompetition<?> liveCompetition
                        && liveCompetition.getPhaseManager().getCurrentPhase().canJoin()
                        && liveCompetition.getPlayers().isEmpty()
                        && (preferredMap == null || liveCompetition.getMap().getName().equalsIgnoreCase(preferredMap.getName()))
                )
                .toList();

        // Ensure we have found an open competition
        if (openCompetitions.isEmpty()) {
            LiveCompetitionMap map = preferredMap;
            if (map == null) {
                boolean requireRemote = plugin.getMainConfig().isProxySupport() && plugin.getMainConfig().isProxyHost();
                map = this.selectFallbackMap(plugin, arena, requireRemote);
            }

            if (map == null) {
                return null;
            }

            LiveCompetition<?> competition = map.createDynamicCompetition(arena);
            arena.getPlugin().addCompetition(arena, competition);
            return competition;
        } else {
            return (LiveCompetition<?>) openCompetitions.iterator().next();
        }
    }

    private @Nullable LiveCompetitionMap selectFallbackMap(BattleArena plugin, Arena arena, boolean requireRemote) {
        return plugin.getMaps(arena)
                .stream()
                .filter(map -> map.getType() == MapType.DYNAMIC)
                .filter(map -> !requireRemote || map.isRemote())
                .findFirst()
                .orElse(null);
    }

    private Set<Player> collectPartyParticipants(BattleArena plugin,
                                                 Player leader,
                                                 @Nullable Collection<UUID> predefinedRoster) {
        if (leader == null) {
            return null;
        }

        LinkedHashSet<Player> participants = new LinkedHashSet<>();

        if (predefinedRoster != null && !predefinedRoster.isEmpty()) {
            LinkedHashSet<UUID> ordered = new LinkedHashSet<>(predefinedRoster);
            ordered.add(leader.getUniqueId());
            for (UUID memberId : ordered) {
                Player online = Bukkit.getPlayer(memberId);
                if (online != null) {
                    participants.add(online);
                }
            }
        } else {
            participants.add(leader);

            Party party = Parties.getParty(leader.getUniqueId());
            if (party != null) {
                PartyMember partyLeader = party.getLeader();
                if (partyLeader != null && !partyLeader.getUniqueId().equals(leader.getUniqueId())) {
                    DuelsMessages.PARTY_LEADER_REQUIRED.send(leader);
                    return null;
                }

                if (partyLeader != null) {
                    Player leaderPlayer = Bukkit.getPlayer(partyLeader.getUniqueId());
                    if (leaderPlayer != null) {
                        participants.add(leaderPlayer);
                    }
                }

                for (PartyMember member : party.getMembers()) {
                    Player online = Bukkit.getPlayer(member.getUniqueId());
                    if (online != null) {
                        participants.add(online);
                    }
                }
            }
        }

        for (Player participant : participants) {
            if (plugin.isPendingProxyJoin(participant.getUniqueId())) {
                DuelsMessages.PARTY_MEMBER_BUSY.send(leader, participant.getName());
                return null;
            }
        }

        return participants;
    }

    private List<UUID> capturePartyRoster(Player leader) {
        LinkedHashSet<UUID> roster = new LinkedHashSet<>();
        roster.add(leader.getUniqueId());

        Party party = Parties.getParty(leader.getUniqueId());
        if (party != null) {
            PartyMember partyLeader = party.getLeader();
            if (partyLeader != null) {
                Player leaderPlayer = Bukkit.getPlayer(partyLeader.getUniqueId());
                if (leaderPlayer != null) {
                    roster.add(partyLeader.getUniqueId());
                }
            }

            for (PartyMember member : party.getMembers()) {
                Player online = Bukkit.getPlayer(member.getUniqueId());
                if (online != null) {
                    roster.add(member.getUniqueId());
                }
            }
        }

        return List.copyOf(roster);
    }

    private @Nullable ArenaTeam findTeamForParty(TeamManager teamManager,
                                                 Set<Player> partyMembers,
                                                 Set<ArenaTeam> excludedTeams) {
        ArenaTeam preferred = null;
        int preferredPopulation = Integer.MAX_VALUE;
        ArenaTeam fallback = null;
        int fallbackPopulation = Integer.MAX_VALUE;

        for (ArenaTeam team : teamManager.getTeams()) {
            if (excludedTeams.contains(team)) {
                continue;
            }

            int rosterSize = teamManager.getNumberOfPlayersOnTeam(team);
            if (teamManager.canJoinTeam(team, partyMembers.size())) {
                if (rosterSize < preferredPopulation) {
                    preferred = team;
                    preferredPopulation = rosterSize;
                }
            } else if (rosterSize < fallbackPopulation) {
                fallback = team;
                fallbackPopulation = rosterSize;
            }
        }

        return preferred != null ? preferred : fallback;
    }

    private void ensurePartyOnTeam(TeamManager teamManager, Set<Player> partyMembers, ArenaTeam team) {
        for (Player player : partyMembers) {
            ArenaPlayer arenaPlayer = ArenaPlayer.getArenaPlayer(player);
            if (arenaPlayer != null && arenaPlayer.getTeam() != team) {
                teamManager.joinTeam(arenaPlayer, team);
            }
        }
    }

    private void trackDuel(Competition<?> competition, Collection<Player> participants) {
        this.trackDuel(competition, participants, null);
    }

    private void trackDuel(Competition<?> competition, Collection<Player> participants, @Nullable DuelSeries series) {
        if (competition == null || participants == null || participants.isEmpty()) {
            return;
        }

        this.duelCompetitions.add(competition);

        Map<UUID, String> names = new LinkedHashMap<>();
        Map<UUID, String> teamAssignments = new LinkedHashMap<>();
        Set<String> origins = new LinkedHashSet<>();
        for (Player participant : participants) {
            UUID id = participant.getUniqueId();
            names.put(id, participant.getName());

            String origin = this.duelOrigins.get(id);
            if (origin != null && !origin.isEmpty()) {
                origins.add(origin);
            }

            ArenaPlayer arenaPlayer = ArenaPlayer.getArenaPlayer(participant);
            if (arenaPlayer != null && arenaPlayer.getTeam() != null) {
                teamAssignments.put(id, arenaPlayer.getTeam().getName());
            }
        }

        if (!names.isEmpty()) {
            this.duelParticipants.put(competition, names);
        }

        if (!teamAssignments.isEmpty()) {
            this.duelTeamsByCompetition.put(competition, teamAssignments);
        }

        if (!origins.isEmpty()) {
            this.duelOriginsByCompetition.put(competition, origins);
        }

        if (series != null) {
            series.lockMapName(competition.getMap().getName());
            this.duelSeriesByCompetition.put(competition, series);
        }
    }

    private boolean isTrackedDuel(@Nullable Competition<?> competition) {
        return competition != null && this.duelCompetitions.contains(competition);
    }

    private String resolveLoserName(Competition<?> competition, UUID winnerId) {
        Map<UUID, String> losers = this.duelLosers.get(competition);
        if (losers != null) {
            for (Map.Entry<UUID, String> entry : losers.entrySet()) {
                if (!entry.getKey().equals(winnerId)) {
                    return entry.getValue();
                }
            }
        }

        Map<UUID, String> participants = this.duelParticipants.get(competition);
        if (participants != null) {
            for (Map.Entry<UUID, String> entry : participants.entrySet()) {
                if (!entry.getKey().equals(winnerId)) {
                    return entry.getValue();
                }
            }
        }

        return "their opponent";
    }

    private String formatHeartsRemaining(double health) {
        double hearts = Math.max(0.0D, health / 2.0D);
        return new DecimalFormat("#.#").format(hearts);
    }

    private void broadcastDuelResult(Arena arena, Competition<?> competition, String miniMessage) {
        BattleArena plugin = arena.getPlugin();
        Set<String> origins = new LinkedHashSet<>(this.duelOriginsByCompetition.getOrDefault(competition, Collections.emptySet()));
        if (origins.isEmpty()) {
            Map<UUID, String> participants = this.duelParticipants.get(competition);
            if (participants != null) {
                for (UUID participant : participants.keySet()) {
                    String origin = this.duelOrigins.get(participant);
                    if (origin != null && !origin.isEmpty()) {
                        origins.add(origin);
                    }
                }
            }
        }

        if (plugin.getMainConfig().isProxySupport() && plugin.getConnector() != null) {
            boolean proxyHost = plugin.getMainConfig().isProxyHost();
            String selfOrigin = plugin.getMainConfig().getProxyServerName();
            if (proxyHost && selfOrigin != null && !selfOrigin.isEmpty()) {
                origins.remove(selfOrigin); // avoid bouncing back to the host
            }

            if (!origins.isEmpty()) {
                for (String origin : origins) {
                    JsonObject payload = new JsonObject();
                    payload.addProperty("type", "duel_result");
                    payload.addProperty("message", miniMessage);
                    if (origin != null && !origin.isEmpty()) {
                        payload.addProperty("origin", origin);
                    }
                    plugin.getConnector().sendToRouter(payload.toString());
                }
            }
        }

        if (plugin.getMainConfig().isProxyHost() || plugin.getConnector() == null) {
            Bukkit.getScheduler().runTask(plugin, () -> org.bukkit.Bukkit.broadcast(Messages.deserializeMiniMessage(miniMessage)));
        }
    }

    private void cleanupDuelTracking(Competition<?> competition) {
        this.duelCompetitions.remove(competition);
        this.duelParticipants.remove(competition);
        this.duelTeamsByCompetition.remove(competition);
        this.duelLosers.remove(competition);
        this.duelOriginsByCompetition.remove(competition);
        this.duelSeriesByCompetition.remove(competition);
    }

    private void restartSeriesRound(Arena arena, Competition<?> competition) {
        arena.getPlugin().info("Duel series restart: competition={}, phase={}",
                competition.getClass().getSimpleName(),
                competition instanceof LiveCompetition<?> liveCompetition
                        ? liveCompetition.getPhaseManager().getCurrentPhase().getType().getName()
                        : "unknown");
        this.announcedDuels.remove(competition);
        this.duelLosers.remove(competition);

        if (competition instanceof LiveCompetition<?> liveCompetition) {
            LiveCompetitionPhase<?> currentPhase = liveCompetition.getPhaseManager().getCurrentPhase() instanceof LiveCompetitionPhase<?> livePhase
                    ? livePhase
                    : null;
            if (currentPhase instanceof VictoryPhase<?> victoryPhase) {
                victoryPhase.cancelDurationTask();
            }

            liveCompetition.getVictoryManager().end(false);

            this.resetRoundParticipants(arena, liveCompetition);

            CompetitionPhaseType<?, ?> targetPhase = null;
            if (arena.getPhases().contains(CompetitionPhaseType.INGAME)) {
                targetPhase = CompetitionPhaseType.INGAME;
            } else if (currentPhase != null && currentPhase.getPreviousPhase() != null) {
                targetPhase = currentPhase.getPreviousPhase().getType();
            }

            if (targetPhase != null) {
                @SuppressWarnings("rawtypes")
                CompetitionPhaseType nextPhase = targetPhase;
                Runnable restart = () -> {
                    if (currentPhase != null) {
                        currentPhase.setPhase(nextPhase, false);
                    } else {
                        liveCompetition.getPhaseManager().setPhase(nextPhase, false);
                    }
                    String phaseName = liveCompetition.getPhaseManager().getCurrentPhase().getType().getName();
                    arena.getPlugin().info("Duel series restart complete: phase={}", phaseName);
                    Bukkit.getScheduler().runTaskLater(arena.getPlugin(), () ->
                            this.resetRoundParticipants(arena, liveCompetition), 1L);
                };
                if (Bukkit.isPrimaryThread()) {
                    restart.run();
                } else {
                    Bukkit.getScheduler().runTask(arena.getPlugin(), restart);
                }
            }
        }
    }

    private AliveSnapshot snapshotAlive(LiveCompetition<?> competition) {
        Arena arena = competition.getArena();
        boolean livesEnabled = arena.getLives() != null && arena.getLives().isEnabled();
        int alivePlayers = 0;
        Set<ArenaTeam> aliveTeams = new HashSet<>();
        for (ArenaPlayer player : competition.getPlayers()) {
            if (!isAlive(player, livesEnabled)) {
                continue;
            }

            alivePlayers++;
            if (!arena.getTeams().isNonTeamGame()) {
                ArenaTeam team = player.getTeam();
                if (team != null) {
                    aliveTeams.add(team);
                }
            }
        }

        int aliveTeamCount = arena.getTeams().isNonTeamGame() ? alivePlayers : aliveTeams.size();
        return new AliveSnapshot(aliveTeamCount, alivePlayers);
    }

    private void resetRoundParticipants(Arena arena, LiveCompetition<?> competition) {
        Map<UUID, String> participantNames =
                this.duelParticipants.getOrDefault(competition, Collections.emptyMap());
        if (participantNames.isEmpty()) {
            return;
        }

        Map<UUID, ArenaPlayer> participants = this.mapParticipants(competition);
        TeamManager teamManager = competition.getTeamManager();
        Map<UUID, String> teamAssignments =
                this.duelTeamsByCompetition.getOrDefault(competition, Collections.emptyMap());
        for (Map.Entry<UUID, String> entry : participantNames.entrySet()) {
            ArenaPlayer player = participants.get(entry.getKey());
            if (player == null) {
                continue;
            }

            player.setStat(ArenaStats.DEATHS, 0);
            if (arena.isLivesEnabled() && arena.getLives() != null) {
                player.setStat(ArenaStats.LIVES, arena.getLives().getLives());
            }
            if (player.getRole() != PlayerRole.PLAYING) {
                competition.changeRole(player, PlayerRole.PLAYING);
            }
            this.resetPlayerTeam(teamManager, player, teamAssignments.get(entry.getKey()), entry.getValue(), arena);
            Player bukkitPlayer = player.getPlayer();
            if (bukkitPlayer != null) {
                bukkitPlayer.setGameMode(GameMode.SURVIVAL);
                bukkitPlayer.setAllowFlight(false);
                bukkitPlayer.setFlying(false);
                this.restoreHealth(arena, bukkitPlayer);
                this.teleportToTeamSpawn(competition, player, bukkitPlayer);
            }
        }
    }

    private void resetPlayerTeam(TeamManager teamManager,
                                 ArenaPlayer player,
                                 @Nullable String teamName,
                                 String participantName,
                                 Arena arena) {
        Set<ArenaTeam> availableTeams = teamManager.getTeams();
        if (availableTeams.isEmpty()) {
            return;
        }

        ArenaTeam currentTeam = player.getTeam();
        if (currentTeam != null && availableTeams.contains(currentTeam)) {
            teamManager.joinTeam(player, currentTeam);
            return;
        }

        if (teamName != null) {
            for (ArenaTeam team : availableTeams) {
                if (teamName.equalsIgnoreCase(team.getName())) {
                    teamManager.joinTeam(player, team);
                    return;
                }
            }
        }

        arena.getPlugin().warn(
                "Duel series reset skipped team assignment for {} (team={}); player will not be re-teamed.",
                participantName,
                teamName
        );
    }

    private Map<UUID, ArenaPlayer> mapParticipants(LiveCompetition<?> competition) {
        Map<UUID, ArenaPlayer> participants = new HashMap<>();
        for (ArenaPlayer player : competition.getPlayers()) {
            participants.put(player.getPlayer().getUniqueId(), player);
        }
        for (ArenaPlayer player : competition.getSpectators()) {
            participants.put(player.getPlayer().getUniqueId(), player);
        }
        return participants;
    }

    private void restoreHealth(Arena arena, Player player) {
        if (player == null) {
            return;
        }

        Bukkit.getScheduler().runTaskLater(arena.getPlugin(), () -> {
            if (!player.isOnline() || player.isDead()) {
                return;
            }

            double maxHealth = Math.max(1.0D, player.getMaxHealth());
            player.setHealth(maxHealth);
            player.setFireTicks(0);
            player.setFlying(false);
            player.setAllowFlight(false);
        }, 5L);
    }

    private void sendSeriesScore(Competition<?> competition, DuelSeries series) {
        String requesterName = this.resolveParticipantName(competition, series.requester);
        String targetName = this.resolveParticipantName(competition, series.target);
        String message = String.format(
                "<gold>Best of %d rounds.</gold> <yellow>Round %d.</yellow> <aqua>Wins:</aqua> " +
                        "<white>%s:</white> <primary>%d</primary> <gray>|</gray> <white>%s:</white> <primary>%d</primary>",
                series.totalRounds,
                series.roundsPlayed,
                requesterName,
                series.requesterWins,
                targetName,
                series.targetWins
        );

        if (competition instanceof LiveCompetition<?> liveCompetition) {
            Map<UUID, ArenaPlayer> participants = this.mapParticipants(liveCompetition);
            Map<UUID, String> trackedParticipants =
                    this.duelParticipants.getOrDefault(competition, Collections.emptyMap());
            for (UUID participantId : trackedParticipants.keySet()) {
                ArenaPlayer arenaPlayer = participants.get(participantId);
                if (arenaPlayer != null && arenaPlayer.getPlayer() != null) {
                    arenaPlayer.getPlayer().sendMessage(Messages.deserializeMiniMessage(message));
                }
            }
            return;
        }

        Map<UUID, String> trackedParticipants =
                this.duelParticipants.getOrDefault(competition, Collections.emptyMap());
        for (UUID participantId : trackedParticipants.keySet()) {
            Player player = Bukkit.getPlayer(participantId);
            if (player != null) {
                player.sendMessage(Messages.deserializeMiniMessage(message));
            }
        }
    }

    private String resolveParticipantName(Competition<?> competition, UUID participantId) {
        Map<UUID, String> participants = this.duelParticipants.get(competition);
        if (participants != null) {
            String name = participants.get(participantId);
            if (name != null) {
                return name;
            }
        }

        Player player = Bukkit.getPlayer(participantId);
        return player != null ? player.getName() : "Unknown";
    }

    @Override
    public Optional<DuelSeriesSnapshot> getSeriesSnapshot(Competition<?> competition) {
        DuelSeries series = this.duelSeriesByCompetition.get(competition);
        if (series == null) {
            return Optional.empty();
        }

        String requesterName = this.resolveParticipantName(competition, series.requester);
        String targetName = this.resolveParticipantName(competition, series.target);
        return Optional.of(new DuelSeriesSnapshot(
                requesterName,
                targetName,
                series.requesterWins,
                series.targetWins,
                series.winsNeeded,
                series.totalRounds
        ));
    }

    private void ensureSeriesVictory(Arena arena, LiveCompetition<?> competition) {
        Bukkit.getScheduler().runTask(arena.getPlugin(), () -> {
            if (!this.isTrackedDuel(competition)) {
                return;
            }

            DuelSeries series = this.duelSeriesByCompetition.get(competition);
            if (series == null || series.isComplete()) {
                return;
            }

            LiveCompetitionPhase<?> currentPhase =
                    competition.getPhaseManager().getCurrentPhase() instanceof LiveCompetitionPhase<?> livePhase
                            ? livePhase
                            : null;
            if (currentPhase == null || CompetitionPhaseType.VICTORY.equals(currentPhase.getType())) {
                return;
            }

            @SuppressWarnings("rawtypes")
            CompetitionPhaseType nextPhase = currentPhase.getNextPhase();
            if (nextPhase == null || !CompetitionPhaseType.VICTORY.equals(nextPhase)) {
                return;
            }

            boolean livesEnabled = arena.getLives() != null && arena.getLives().isEnabled();
            Set<ArenaPlayer> victors = new HashSet<>(competition.getVictoryManager().identifyPotentialVictors());
            if (victors.isEmpty()) {
                for (ArenaPlayer player : competition.getPlayers()) {
                    if (isAlive(player, livesEnabled)) {
                        victors.add(player);
                    }
                }
            }

            currentPhase.setPhase(nextPhase);
            if (competition.getPhaseManager().getCurrentPhase() instanceof VictoryPhase<?> victoryPhase) {
                if (victors.isEmpty()) {
                    victoryPhase.onDraw();
                } else {
                    victoryPhase.onVictory(victors);
                }
            }

            competition.getVictoryManager().end(false);
            arena.getPlugin().info("Duel series fallback victory fired for round reset.");
        });
    }

    private static boolean isAlive(ArenaPlayer player, boolean livesEnabled) {
        if (player.getRole() == PlayerRole.SPECTATING) {
            return false;
        }

        int deaths = player.stat(ArenaStats.DEATHS).orElse(0);
        return (!livesEnabled || deaths < player.getArena().getLives().getLives())
                && (livesEnabled || deaths <= 0);
    }

    private static final class AliveSnapshot {
        private final int aliveTeams;
        private final int alivePlayers;

        private AliveSnapshot(int aliveTeams, int alivePlayers) {
            this.aliveTeams = aliveTeams;
            this.alivePlayers = alivePlayers;
        }
    }

    private void teleportToTeamSpawn(LiveCompetition<?> competition, ArenaPlayer player, Player bukkitPlayer) {
        Spawns spawns = competition.getMap().getSpawns();
        if (spawns == null || spawns.getTeamSpawns() == null) {
            return;
        }

        ArenaTeam team = player.getTeam();
        if (team == null) {
            return;
        }

        TeamSpawns teamSpawns = spawns.getTeamSpawns().get(team.getName());
        if (teamSpawns == null || teamSpawns.getSpawns() == null || teamSpawns.getSpawns().isEmpty()) {
            return;
        }

        World world = competition.getMap().getWorld();
        if (world == null) {
            return;
        }

        List<PositionWithRotation> options = teamSpawns.getSpawns();
        PositionWithRotation choice = options.get(ThreadLocalRandom.current().nextInt(options.size()));
        Location location = choice.toLocation(world);
        bukkitPlayer.teleport(location);
    }

    private static int normalizeRounds(int rounds) {
        return Math.max(1, rounds);
    }

    private static List<UUID> normalizeRoster(UUID leader, @Nullable Collection<UUID> roster) {
        LinkedHashSet<UUID> ordered = new LinkedHashSet<>();
        ordered.add(leader);
        if (roster != null) {
            ordered.addAll(roster);
        }

        return List.copyOf(ordered);
    }

    private JsonObject serializePlayer(Player player) {
        org.battleplugins.arena.proxy.SerializedPlayer serialized =
                org.battleplugins.arena.proxy.SerializedPlayer.toSerializedPlayer(player);
        JsonObject playerObject = new JsonObject();
        playerObject.addProperty("uuid", serialized.getUuid());
        if (!serialized.getElements().isEmpty()) {
            JsonArray elementsArray = new JsonArray();
            serialized.getElements().forEach(element -> elementsArray.add(element.name()));
            playerObject.add("elements", elementsArray);
        }
        if (!serialized.getAbilities().isEmpty()) {
            JsonObject abilitiesObject = new JsonObject();
            serialized.getAbilities().forEach((slot, ability) ->
                    abilitiesObject.addProperty(String.valueOf(slot), ability));
            playerObject.add("abilities", abilitiesObject);
        }
        if (serialized.getOrigin() != null && !serialized.getOrigin().isEmpty()) {
            playerObject.addProperty("origin", serialized.getOrigin());
        }
        return playerObject;
    }
}


