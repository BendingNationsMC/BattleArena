package org.battleplugins.arena.module.domination;

import org.battleplugins.arena.Arena;
import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.competition.LiveCompetition;
import org.battleplugins.arena.module.domination.config.DominationAreaDefinition;
import org.battleplugins.arena.module.domination.config.DominationMapSettings;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages the runtime capture loop for a single competition instance.
 */
final class DominationRound {

    private final DominationModule module;
    private final Arena arena;
    private final LiveCompetition<?> competition;
    private final Map<String, DominationAreaTracker> trackers = new LinkedHashMap<>();

    private BukkitTask tickTask;

    DominationRound(DominationModule module, Arena arena, LiveCompetition<?> competition, DominationMapSettings settings) {
        this.module = module;
        this.arena = arena;
        this.competition = competition;

        World world = competition.getMap().getWorld();
        if (world == null) {
            BattleArena.getInstance().warn("Arena {} map does not have an associated world for domination tracking.", arena.getName());
            return;
        }

        DominationAreaDefinition.randomizedRewards(settings.getAreas()).forEach((id, definition) -> {
            if (definition.getLocation() == null) {
                BattleArena.getInstance().warn("Domination area {} in arena {} is missing a location.", id, arena.getName());
                return;
            }

            Location center = definition.getLocation().toLocation(world);
            DominationAreaTracker tracker = new DominationAreaTracker(id, definition, center);
            tracker.spawnHologram(this.competition);
            this.trackers.put(id, tracker);
        });
    }

    boolean hasTrackers() {
        return !this.trackers.isEmpty();
    }

    void start() {
        if (this.tickTask != null || this.trackers.isEmpty()) {
            return;
        }

        this.tickTask = Bukkit.getScheduler().runTaskTimer(BattleArena.getInstance(), this::tick, 1L, 1L);
    }

    void stop() {
        if (this.tickTask != null) {
            this.tickTask.cancel();
            this.tickTask = null;
        }

        this.trackers.values().forEach(DominationAreaTracker::removeHologram);
    }

    private void tick() {
        if (this.trackers.isEmpty()) {
            return;
        }

        var players = this.competition.getPlayers();
        this.trackers.values().forEach(tracker -> {
            DominationAreaTracker.CaptureNotification notification = tracker.tick(players);

            if (notification.startedTeam() != null) {
                this.module.handleAreaCaptureStart(
                        this.arena,
                        this.competition,
                        tracker,
                        notification.startedTeam()
                );
            }

            notification.result().ifPresent(result -> this.module.handleAreaCapture(
                    this.arena,
                    this.competition,
                    tracker,
                    result
            ));
        });
    }
}
