package org.battleplugins.arena.module.storm.config;

import org.battleplugins.arena.competition.phase.CompetitionPhaseType;
import org.battleplugins.arena.config.ArenaOption;

import java.util.ArrayList;
import java.util.List;

public class StormSettings {
    @ArenaOption(
        name = "enabled",
        description = "Whether or not the storm is active for this arena."
    )
    private boolean enabled = false;
    @ArenaOption(
        name = "start-radius",
        description = "Initial radius of the storm around the waitroom location.",
        required = true
    )
    private double startRadius = 75.0;
    @ArenaOption(
        name = "min-radius",
        description = "Minimum radius the storm will shrink to."
    )
    private double minRadius = 5.0;
    @ArenaOption(
        name = "damage-per-second",
        description = "How much damage players take per second while outside the storm."
    )
    private double damagePerSecond = 2.0;
    @ArenaOption(
        name = "waves",
        description = "Shrinking waves executed sequentially.",
        required = true
    )
    private List<StormWave> waves = new ArrayList<>();
    @ArenaOption(
        name = "active-phase",
        description = "Optional phase in which the storm should start."
    )
    private CompetitionPhaseType<?, ?> activePhase;

    @ArenaOption(
            name = "spawn-boss",
            description = "Spawns the boss in the waiting room location"
    )
    private boolean spawnBoss = true;

    public StormSettings() {
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public double getStartRadius() {
        return this.startRadius;
    }

    public double getDamagePerSecond() {
        return this.damagePerSecond;
    }

    public double getMinRadius() {
        return Math.max(1.0, this.minRadius);
    }

    public List<StormWave> getWaves() {
        return this.waves == null ? List.of() : List.copyOf(this.waves);
    }

    public CompetitionPhaseType<?, ?> getActivePhase() {
        return this.activePhase;
    }

    public boolean hasWaves() {
        return !this.getWaves().isEmpty();
    }

    public boolean isSpawnBoss() {
        return spawnBoss;
    }
}
