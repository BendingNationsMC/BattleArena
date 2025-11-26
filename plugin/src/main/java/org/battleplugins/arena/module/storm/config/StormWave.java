package org.battleplugins.arena.module.storm.config;

import org.battleplugins.arena.config.ArenaOption;

import java.time.Duration;

public class StormWave {
    @ArenaOption(
        name = "duration",
        description = "How long the border stays idle before shrinking to the next radius.",
        required = true
    )
    private Duration duration = Duration.ofSeconds(30L);
    @ArenaOption(
        name = "rush-duration",
        description = "How long the border takes to reach the next radius once it starts moving."
    )
    private Duration rushDuration = Duration.ofSeconds(5L);

    public StormWave() {
    }

    public Duration getDuration() {
        return this.duration == null ? Duration.ZERO : this.duration;
    }

    public Duration getRushDuration() {
        Duration configured = this.rushDuration;
        return configured == null ? Duration.ZERO : configured;
    }
}
