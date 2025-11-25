package org.battleplugins.arena.module.domination.config;

import org.battleplugins.arena.competition.phase.CompetitionPhaseType;
import org.battleplugins.arena.config.ArenaOption;

/**
 * Configuration wrapper for arena-level domination options.
 */
public class DominationArenaSettings {

    @ArenaOption(name = "active-phase", description = "Competition phase where domination tracking is active.")
    private CompetitionPhaseType<?, ?> activePhase = CompetitionPhaseType.INGAME;

    public CompetitionPhaseType<?, ?> getActivePhase() {
        return this.activePhase == null ? CompetitionPhaseType.INGAME : this.activePhase;
    }
}
