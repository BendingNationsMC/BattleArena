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

import java.util.Collections;
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
    private final Map<Competition<?>, Set<UUID>> recentLosers = new ConcurrentHashMap<>();
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

        Set<UUID> loserIds = event.getLosers()
                .stream()
                .map(ArenaPlayer::getPlayer)
                .map(Player::getUniqueId)
                .collect(Collectors.toSet());
        recentLosers.put(event.getCompetition(), loserIds);
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
        Set<UUID> winners = event.getVictors()
                .stream()
                .map(ArenaPlayer::getPlayer)
                .map(Player::getUniqueId)
                .collect(Collectors.toSet());

        Set<UUID> losers = recentLosers.remove(competition);
        if (losers == null || losers.isEmpty()) {
            losers = deriveLosersFromCompetition(competition, winners);
        }

        if (losers.isEmpty()) {
            return;
        }

        double timeLeft = computeTimeLeft(competition);

        for (UUID winnerId : winners) {
            Player winnerPlayer = plugin.getServer().getPlayer(winnerId);
            if (winnerPlayer == null) {
                continue;
            }

            Elements winnerElement = resolveElement(winnerPlayer);
            RankedMatchFactors winnerFactors = buildMatchFactors(winnerId, winnerPlayer, timeLeft);

            for (UUID loserId : losers) {
                Player loserPlayer = plugin.getServer().getPlayer(loserId);
                Elements loserElement = loserPlayer != null ? resolveElement(loserPlayer) : Elements.AIR;
                RankedMatchFactors loserFactors = buildMatchFactors(loserId, loserPlayer, timeLeft);

                service.updateAfterMatch(winnerId, loserId, winnerElement, loserElement, winnerFactors, loserFactors);
            }
        }

        // Clean tracked data after processing all participants.
        cleanupParticipants(winners);
        cleanupParticipants(losers);
        finalizeCompetition(competition);
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    private Set<UUID> deriveLosersFromCompetition(Competition<?> competition, Set<UUID> winnerIds) {
        if (competition instanceof LiveCompetition<?> live) {
            return live.getPlayers().stream()
                    .map(ArenaPlayer::getPlayer)
                    .map(Player::getUniqueId)
                    .filter(id -> !winnerIds.contains(id))
                    .collect(Collectors.toSet());
        }
        return Collections.emptySet();
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
        }
    }

    private void processDraw(Competition<?> competition) {
        if (!(competition instanceof LiveCompetition<?> liveCompetition)) {
            finalizeCompetition(competition);
            return;
        }

        Set<ArenaPlayer> arenaPlayers = liveCompetition.getPlayers();
        if (arenaPlayers.size() < 2) {
            finalizeCompetition(competition);
            return;
        }

        double timeLeft = computeTimeLeft(competition);
        List<UUID> participants = arenaPlayers.stream()
                .map(ArenaPlayer::getPlayer)
                .map(Player::getUniqueId)
                .collect(Collectors.toList());

        for (int i = 0; i < participants.size(); i++) {
            UUID firstId = participants.get(i);
            Player firstPlayer = plugin.getServer().getPlayer(firstId);
            Elements firstElement = firstPlayer != null ? resolveElement(firstPlayer) : Elements.AIR;
            RankedMatchFactors firstFactors = buildMatchFactors(firstId, firstPlayer, timeLeft);

            for (int j = i + 1; j < participants.size(); j++) {
                UUID secondId = participants.get(j);
                Player secondPlayer = plugin.getServer().getPlayer(secondId);
                Elements secondElement = secondPlayer != null ? resolveElement(secondPlayer) : Elements.AIR;
                RankedMatchFactors secondFactors = buildMatchFactors(secondId, secondPlayer, timeLeft);

                service.updateAfterDraw(firstId, secondId, firstElement, secondElement, firstFactors, secondFactors);
            }
        }

        cleanupParticipants(participants);
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
        processed.remove(competition);
    }
}
