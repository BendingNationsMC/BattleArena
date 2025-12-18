package org.battleplugins.arena.module.storm;

import org.battleplugins.arena.Arena;
import org.battleplugins.arena.competition.Competition;
import org.battleplugins.arena.competition.LiveCompetition;
import org.battleplugins.arena.competition.phase.CompetitionPhaseType;
import org.battleplugins.arena.event.ArenaEventHandler;
import org.battleplugins.arena.event.ArenaListener;
import org.battleplugins.arena.event.arena.ArenaPhaseCompleteEvent;
import org.battleplugins.arena.event.arena.ArenaPhaseStartEvent;
import org.battleplugins.arena.event.arena.ArenaRemoveCompetitionEvent;
import org.battleplugins.arena.module.storm.config.StormSettings;
import org.battleplugins.arena.module.storm.wave.StormController;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles the storm lifecycle for a single arena.
 */
final class StormArenaHandler implements ArenaListener {

    private final StormModule module;
    private final Arena arena;
    private final StormSettings settings;
    private final Map<Competition<?>, StormController> controllers = new HashMap<>();

    StormArenaHandler(StormModule module, Arena arena, StormSettings settings) {
        this.module = module;
        this.arena = arena;
        this.settings = settings;
    }

    @ArenaEventHandler
    public void onPhaseStart(ArenaPhaseStartEvent event) {
        if (!this.shouldActivateForPhase(event.getPhase().getType())) {
            return;
        }

        this.startStorm(event.getCompetition());
    }

    @ArenaEventHandler
    public void onPhaseComplete(ArenaPhaseCompleteEvent event) {
        if (!this.shouldActivateForPhase(event.getPhase().getType())) {
            return;
        }

        this.stopStorm(event.getCompetition());
    }

    @ArenaEventHandler
    public void onRemoveCompetition(ArenaRemoveCompetitionEvent event) {
        this.stopStorm(event.getCompetition());
    }

    void shutdown() {
        this.controllers.values().forEach(StormController::stop);
        this.controllers.clear();
    }

    private boolean shouldActivateForPhase(CompetitionPhaseType<?, ?> phaseType) {
        CompetitionPhaseType<?, ?> activePhase = this.settings.getActivePhase();
        return activePhase == null || activePhase.equals(phaseType);
    }

    private void startStorm(Competition<?> competition) {
        if (!(competition instanceof LiveCompetition<?> liveCompetition)) {
            return;
        }

        StormController existing = this.controllers.get(competition);
        if (existing != null) {
            return;
        }

        StormController controller = new StormController(this.arena, liveCompetition, this.settings);
        if (!controller.initialize()) {
            return;
        }

        controller.start();
        this.controllers.put(competition, controller);
    }

    private void stopStorm(Competition<?> competition) {
        StormController controller = this.controllers.remove(competition);
        if (controller != null) {
            controller.stop();
        }
    }
}
