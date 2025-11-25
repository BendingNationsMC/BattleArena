package org.battleplugins.arena.options;

import org.battleplugins.arena.config.ArenaOption;
import org.battleplugins.arena.config.DocumentationSource;

import java.time.Duration;

@DocumentationSource("https://docs.battleplugins.org/books/user-guide/chapter/configuration")
public class Lives {

    @ArenaOption(name = "enabled", description = "Whether or not lives are enabled.")
    private boolean enabled = false;

    @ArenaOption(name = "amount", description = "The amount of lives each player has.")
    private int lives = 1;

    @ArenaOption(name = "respawn-timeout", description = "How long (in time format, e.g. 5s) players must wait before respawning.")
    private Duration respawnTimeout = Duration.ZERO;

    public boolean isEnabled() {
        return this.enabled;
    }

    public int getLives() {
        return this.lives;
    }

    public Duration getRespawnTimeout() {
        return this.respawnTimeout == null ? Duration.ZERO : this.respawnTimeout;
    }
}
