package org.battleplugins.arena.module.ranked;

import org.battleplugins.arena.ArenaPlayer;
import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.competition.Competition;
import org.battleplugins.arena.competition.LiveCompetition;
import org.battleplugins.arena.event.arena.ArenaDrawEvent;
import org.battleplugins.arena.event.arena.ArenaLoseEvent;
import org.battleplugins.arena.event.arena.ArenaPhaseStartEvent;
import org.battleplugins.arena.event.arena.ArenaVictoryEvent;
import org.battleplugins.arena.proxy.Elements;
import org.battleplugins.arena.proxy.SerializedPlayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Listens for arena results on the proxy host and forwards
 * outcome data to the ranked service.
 */
public class RankedMatchListener implements Listener {
    private static final Logger log = LoggerFactory.getLogger(RankedMatchListener.class);
    private final BattleArena plugin;
    private final RankedService service;
    private final Map<Competition<?>, Map<UUID, Player>> recentLosers = new ConcurrentHashMap<>();
    private final Map<Competition<?>, Map<UUID, Player>> participantSnapshots = new ConcurrentHashMap<>();
    private final Map<UUID, Double> damageGiven = new ConcurrentHashMap<>();
    private final Map<UUID, Double> damageTaken = new ConcurrentHashMap<>();
    private final Map<Competition<?>, Long> competitionStartTime = new ConcurrentHashMap<>();
    private final Set<Competition<?>> processed = ConcurrentHashMap.newKeySet();

    public RankedMatchListener(BattleArena plugin, RankedService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @EventHandler
    public void onArenaDraw(ArenaDrawEvent event) {
        if (!plugin.getMainConfig().isProxyHost() || !plugin.getMainConfig().isProxySupport()) {
            return;
        }

        if (!event.getArena().isModuleEnabled(RankedModule.ID)) {
            return;
        }

        Competition<?> competition = event.getCompetition();
        if (!processed.add(competition)) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> processDraw(competition));
    }

    @EventHandler
    public void onArenaLose(ArenaLoseEvent event) {
        if (!event.getArena().isModuleEnabled(RankedModule.ID)) {
            return;
        }

        Map<UUID, Player> losers = collectPlayers(event.getLosers());
        if (!losers.isEmpty()) {
            debug("Recorded {} losers for competition {}", losers.size(), describeCompetition(event.getCompetition()));
            recentLosers.put(event.getCompetition(), losers);
        }
    }

    @EventHandler
    public void onArenaVictory(ArenaVictoryEvent event) {
        if (!plugin.getMainConfig().isProxyHost() || !plugin.getMainConfig().isProxySupport()) {
            return;
        }

        if (!event.getArena().isModuleEnabled(RankedModule.ID)) {
            return;
        }

        Competition<?> competition = event.getCompetition();
        if (!processed.add(competition)) {
            return; // already handled
        }

        // Run on next tick to allow ArenaLoseEvent to fire first.
        Bukkit.getScheduler().runTask(plugin, () -> processVictory(event, competition));
    }

    private void processVictory(ArenaVictoryEvent event, Competition<?> competition) {
        Map<UUID, Player> winners = collectPlayers(event.getVictors());
        if (winners.isEmpty()) {
            debug("Victory ignored for competition {} - no winner players resolved", describeCompetition(competition));
            return;
        }

        Set<UUID> winnerIds = winners.keySet();
        Map<UUID, Player> participants = participantSnapshots.getOrDefault(competition, Collections.emptyMap());
        Map<UUID, Player> losers = recentLosers.remove(competition);
        if (losers == null || losers.isEmpty()) {
            if (!participants.isEmpty()) {
                losers = new HashMap<>(participants);
                losers.keySet().removeAll(winnerIds);
                debug("Derived {} losers from participant snapshot for competition {}", losers.size(), describeCompetition(competition));
            } else {
                losers = deriveLosersFromCompetition(competition, winnerIds);
                debug("Recent losers missing for competition {}, derived {} losers from roster", describeCompetition(competition), losers.size());
            }
        } else {
            debug("Using {} tracked losers for competition {}", losers.size(), describeCompetition(competition));
        }

        if (losers.isEmpty()) {
            debug("Cannot process ranked victory for competition {} - no losers resolved", describeCompetition(competition));
            cleanupParticipants(winnerIds);
            finalizeCompetition(competition);
            return;
        }

        double timeLeft = computeTimeLeft(competition);
        debug("Processing ranked victory for competition {} (winners={}, losers={}, timeLeft={}s)",
                describeCompetition(competition),
                winners.keySet(),
                losers.keySet(),
                timeLeft);

        for (Map.Entry<UUID, Player> winnerEntry : winners.entrySet()) {
            UUID winnerId = winnerEntry.getKey();
            Player winnerPlayer = winnerEntry.getValue();
            if (winnerPlayer == null) {
                log.warn("Unable to resolve Bukkit player for winner {}", winnerId);
                continue;
            }

            Elements winnerElement = resolveElement(winnerPlayer);
            RankedMatchFactors winnerFactors = buildMatchFactors(winnerId, winnerPlayer, timeLeft);

            for (Map.Entry<UUID, Player> loserEntry : losers.entrySet()) {
                UUID loserId = loserEntry.getKey();
                Player loserPlayer = loserEntry.getValue();
                if (loserPlayer == null) {
                    log.warn("Unable to resolve Bukkit player for loser {}", loserId);
                    continue;
                }

                Elements loserElement = resolveElement(loserPlayer);
                RankedMatchFactors loserFactors = buildMatchFactors(loserId, loserPlayer, timeLeft);

                debug("Updating ranked match result winner={} ({}) vs loser={} ({}) | winnerDamage={} loserDamage={}",
                        winnerPlayer.getName(),
                        winnerElement,
                        loserPlayer.getName(),
                        loserElement,
                        winnerFactors.winnerDamageGiven(),
                        loserFactors.winnerDamageGiven());
                service.updateAfterMatch(winnerId, loserId, winnerElement, loserElement, winnerFactors, loserFactors);
            }
        }

        // Clean tracked data after processing all participants.
        cleanupParticipants(winnerIds);
        cleanupParticipants(losers.keySet());
        finalizeCompetition(competition);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    private Map<UUID, Player> deriveLosersFromCompetition(Competition<?> competition, Set<UUID> winnerIds) {
        if (competition instanceof LiveCompetition<?> live) {
            Map<UUID, Player> losers = new HashMap<>();
            for (ArenaPlayer arenaPlayer : live.getPlayers()) {
                Player player = arenaPlayer.getPlayer();
                if (player == null) {
                    continue;
                }
                UUID id = player.getUniqueId();
                if (winnerIds.contains(id)) {
                    continue;
                }
                losers.put(id, player);
            }
            return losers;
        }
        return Collections.emptyMap();
    }

    // Looking at the player element
    private Elements resolveElement(Player player) {
        try {
            var serialized = SerializedPlayer.toSerializedPlayer(player);
            if (!serialized.getElements().isEmpty()) {
                return serialized.getElements().getFirst();
            }
        } catch (Throwable ignored) {
        }

        log.warn("Unable to resolve element: {}", player.getName());
        return Elements.AIR;
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player damaged) || !(event.getDamager() instanceof Player damager)) {
            return;
        }

        ArenaPlayer damagedArena = ArenaPlayer.getArenaPlayer(damaged);
        ArenaPlayer damagerArena = ArenaPlayer.getArenaPlayer(damager);
        if (damagedArena == null || damagerArena == null) {
            return;
        }

        if (!damagedArena.getArena().isModuleEnabled(RankedModule.ID)) {
            return;
        }

        if (!damagedArena.getCompetition().equals(damagerArena.getCompetition())) {
            return;
        }

        double dmg = event.getFinalDamage();
        if (dmg <= 0) {
            return;
        }

        damageGiven.merge(damager.getUniqueId(), dmg, Double::sum);
        damageTaken.merge(damaged.getUniqueId(), dmg, Double::sum);
    }

    @EventHandler
    public void onPhaseStart(ArenaPhaseStartEvent event) {
        Competition<?> competition = event.getCompetition();
        if (competition == null) {
            return;
        }

        if (!event.getArena().isModuleEnabled(RankedModule.ID)) {
            return;
        }

        if (org.battleplugins.arena.competition.phase.CompetitionPhaseType.INGAME.equals(event.getPhase().getType())) {
            competitionStartTime.put(competition, System.currentTimeMillis());
            Map<UUID, Player> snapshot = snapshotCompetitionPlayers(competition);
            if (!snapshot.isEmpty()) {
                participantSnapshots.put(competition, snapshot);
                debug("Snapshot {} participants for competition {}", snapshot.size(), describeCompetition(competition));
            } else {
                participantSnapshots.remove(competition);
                debug("Unable to snapshot participants for competition {}", describeCompetition(competition));
            }
        }
    }

    private void processDraw(Competition<?> competition) {
        Map<UUID, Player> participants = participantSnapshots.get(competition);
        if (participants == null || participants.size() < 2) {
            debug("Skipping draw processing for competition {} - participants snapshot missing or too small", describeCompetition(competition));
            finalizeCompetition(competition);
            return;
        }

        double timeLeft = computeTimeLeft(competition);
        List<Map.Entry<UUID, Player>> participantEntries = new ArrayList<>(participants.entrySet());
        List<UUID> participantIds = participantEntries.stream()
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        for (int i = 0; i < participantEntries.size(); i++) {
            Map.Entry<UUID, Player> firstEntry = participantEntries.get(i);
            Player firstPlayer = firstEntry.getValue();
            UUID firstId = firstEntry.getKey();
            Elements firstElement = resolveElement(firstPlayer);
            RankedMatchFactors firstFactors = buildMatchFactors(firstId, firstPlayer, timeLeft);

            for (int j = i + 1; j < participantEntries.size(); j++) {
                Map.Entry<UUID, Player> secondEntry = participantEntries.get(j);
                Player secondPlayer = secondEntry.getValue();
                UUID secondId = secondEntry.getKey();
                Elements secondElement = resolveElement(secondPlayer);
                RankedMatchFactors secondFactors = buildMatchFactors(secondId, secondPlayer, timeLeft);

                    debug("Updating ranked draw between {} ({}) and {} ({})",
                            firstPlayer.getName(),
                            firstElement,
                            secondPlayer.getName(),
                            secondElement);
                    service.updateAfterDraw(firstId, secondId, firstElement, secondElement, firstFactors, secondFactors);
                }
            }

        cleanupParticipants(participantIds);
        recentLosers.remove(competition);
        finalizeCompetition(competition);
    }

    private double computeTimeLeft(Competition<?> competition) {
        double maxTime = service.getConfig().getMaxDuelTimeSeconds();
        long now = System.currentTimeMillis();
        long start = competitionStartTime.getOrDefault(competition, now);
        double elapsedSeconds = Math.max(0.0, (now - start) / 1000.0);
        return Math.max(0.0, maxTime - elapsedSeconds);
    }

    private RankedMatchFactors buildMatchFactors(UUID playerId, Player player, double timeLeft) {
        double damageDone = damageGiven.getOrDefault(playerId, 0.0);
        double damageSustained = damageTaken.getOrDefault(playerId, 0.0);
        double health = player != null ? player.getHealth() : 0.0;
        return new RankedMatchFactors(damageDone, damageSustained, health, timeLeft);
    }

    private void cleanupParticipants(Iterable<UUID> participantIds) {
        for (UUID id : participantIds) {
            damageGiven.remove(id);
            damageTaken.remove(id);
        }
    }

    private void finalizeCompetition(Competition<?> competition) {
        competitionStartTime.remove(competition);
        participantSnapshots.remove(competition);
        processed.remove(competition);
    }

    private Map<UUID, Player> collectPlayers(Set<ArenaPlayer> arenaPlayers) {
        Map<UUID, Player> players = new HashMap<>();
        for (ArenaPlayer arenaPlayer : arenaPlayers) {
            Player player = arenaPlayer.getPlayer();
            if (player == null) {
                debug("Skipping ArenaPlayer {} because Bukkit player handle is null", arenaPlayer.describe());
                continue;
            }
            players.put(player.getUniqueId(), player);
        }
        if (!players.isEmpty()) {
            debug("Collected {} players from event payload", players.size());
        }
        return players;
    }

    private Map<UUID, Player> snapshotCompetitionPlayers(Competition<?> competition) {
        if (competition instanceof LiveCompetition<?> liveCompetition) {
            Map<UUID, Player> snapshot = new HashMap<>();
            for (ArenaPlayer arenaPlayer : liveCompetition.getPlayers()) {
                Player player = arenaPlayer.getPlayer();
                if (player != null) {
                    snapshot.put(player.getUniqueId(), player);
                }
            }
            return snapshot;
        }
        return Collections.emptyMap();
    }

    private String describeCompetition(Competition<?> competition) {
        if (competition == null) {
            return "unknown";
        }
        return competition.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(competition));
    }

    private void debug(String message, Object... args) {
        plugin.debug("[Ranked] " + message, args);
    }
}
