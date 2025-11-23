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
import org.battleplugins.arena.competition.team.TeamManager;
import org.battleplugins.arena.event.arena.ArenaCreateExecutorEvent;
import org.battleplugins.arena.event.player.ArenaPreJoinEvent;
import org.battleplugins.arena.event.player.ArenaLeaveEvent;
import org.battleplugins.arena.feature.party.Parties;
import org.battleplugins.arena.feature.party.Party;
import org.battleplugins.arena.feature.party.PartyMember;
import org.battleplugins.arena.messages.Messages;
import org.battleplugins.arena.module.ArenaModule;
import org.battleplugins.arena.module.ArenaModuleInitializer;
import org.battleplugins.arena.proxy.ProxyDuelRequestEvent;
import org.battleplugins.arena.team.ArenaTeam;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A module that adds duels to BattleArena.
 */
@ArenaModule(id = Duels.ID, name = "Duels", description = "Adds duels to BattleArena.", authors = "BattlePlugins")
public class Duels implements ArenaModuleInitializer {
    public static final String ID = "duels";
    public static final JoinResult PENDING_REQUEST = new JoinResult(false, DuelsMessages.PENDING_DUEL_REQUEST);

    private final Map<UUID, DuelRequest> duelRequestsByRequester = new HashMap<>();
    private final Map<UUID, DuelRequest> duelRequestsByTarget = new HashMap<>();
    private final Map<UUID, String> duelOrigins = new ConcurrentHashMap<>();

    static final class DuelRequest {
        private final UUID requester;
        private final UUID target;
        private final List<UUID> requesterParty;
        private final List<UUID> targetParty;

        private DuelRequest(UUID requester,
                            UUID target,
                            List<UUID> requesterParty,
                            List<UUID> targetParty) {
            this.requester = requester;
            this.target = target;
            this.requesterParty = requesterParty;
            this.targetParty = targetParty;
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
    }

    private static final class ProxyDuel {
        private final Arena arena;
        private final UUID requester;
        private final UUID target;
        private final List<UUID> requesterParty;
        private final List<UUID> targetParty;

        private ProxyDuel(Arena arena,
                          UUID requester,
                          UUID target,
                          Collection<UUID> requesterParty,
                          Collection<UUID> targetParty) {
            this.arena = arena;
            this.requester = requester;
            this.target = target;
            this.requesterParty = normalizeRoster(requester, requesterParty);
            this.targetParty = normalizeRoster(target, targetParty);
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

        private Collection<UUID> allParticipants() {
            LinkedHashSet<UUID> all = new LinkedHashSet<>(requesterParty);
            all.addAll(targetParty);
            return all;
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
        DuelRequest request = new DuelRequest(
                requester.getUniqueId(),
                target.getUniqueId(),
                this.capturePartyRoster(requester),
                this.capturePartyRoster(target)
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
        this.acceptDuel(arena, requester, opponent, this.capturePartyRoster(requester), this.capturePartyRoster(opponent));
    }

    public void acceptDuel(Arena arena,
                           Player requester,
                           Player opponent,
                           @Nullable Collection<UUID> requesterRoster,
                           @Nullable Collection<UUID> opponentRoster) {
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

        // If this server is not the proxy host but proxy support is enabled, we should
        // forward the duel to the host without requiring a local competition.
        if (proxySupport && !proxyHost) {
            for (Player participant : allParticipants) {
                if (plugin.isPendingProxyJoin(participant.getUniqueId())) {
                    Messages.ARENA_ERROR.send(participant, "A proxy map is currently loading for you. Please wait.");
                    return;
                }
            }

            // Choose a dynamic map name for the duel; this only needs to match a configured
            // map on the proxy host, not an active competition on this server.
            List<LiveCompetitionMap> dynamicMaps = plugin.getMaps(arena)
                    .stream()
                    .filter(map -> map.getType() == MapType.DYNAMIC)
                    .toList();

            if (dynamicMaps.isEmpty()) {
                allParticipants.forEach(Messages.NO_OPEN_ARENAS::send);
                return;
            }

            LiveCompetitionMap map = dynamicMaps.iterator().next();

            this.sendProxyDuelRequest(plugin, arena, map.getName(), requester, opponent, requesterParty, opponentParty, allParticipants);

            return;
        }

        // Local or proxy host: find or create an open competition and join immediately.
        LiveCompetition<?> competition = findOrJoinCompetition(arena);
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
    }

    public void handleProxyDuelRequest(ProxyDuelRequestEvent event) {
        ProxyDuel duel = new ProxyDuel(event.getArena(), event.getRequesterUuid(), event.getTargetUuid(), event.getRequesterPartyMembers(), event.getTargetPartyMembers());
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

        this.acceptDuel(duel.arena, requesterPlayer, targetPlayer, duel.getRequesterParty(), duel.getTargetParty());
    }

    private void sendProxyDuelRequest(BattleArena plugin,
                                      Arena arena,
                                      String mapName,
                                      Player requester,
                                      Player opponent,
                                      Set<Player> requesterParty,
                                      Set<Player> opponentParty,
                                      Set<Player> allParticipants) {
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
        joinPayload.addProperty("map", mapName);
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
        duelPayload.addProperty("map", mapName);
        duelPayload.add("requester", this.serializePlayer(requester));
        duelPayload.add("target", this.serializePlayer(opponent));
        duelPayload.add("requesterParty", this.serializeRoster(requesterParty));
        duelPayload.add("targetParty", this.serializeRoster(opponentParty));

        if (origin != null && !origin.isEmpty()) {
            duelPayload.addProperty("origin", origin);
        }

        plugin.getConnector().sendToRouter(duelPayload.toString());

        for (Player participant : allParticipants) {
            plugin.addPendingProxyJoin(participant.getUniqueId());
            Messages.ARENA_ERROR.send(participant, "Preparing duel map on proxy host. Please wait...");
        }
    }

    private JsonArray serializeRoster(Collection<Player> players) {
        JsonArray array = new JsonArray();
        players.forEach(player -> array.add(player.getUniqueId().toString()));
        return array;
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
                if (online == null) {
                    return null;
                }

                participants.add(online);
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
        return playerObject;
    }
}
