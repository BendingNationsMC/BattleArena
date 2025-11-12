package org.battleplugins.arena.competition.map.options;

import org.battleplugins.arena.config.ArenaOption;
import org.battleplugins.arena.util.PositionWithRotation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the spawn options for a team.
 */
public class TeamSpawns {

    @ArenaOption(name = "spawns", description = "The spawns for this team.")
    private List<PositionWithRotation> spawns;

    public TeamSpawns() {
    }

    public TeamSpawns(@Nullable List<PositionWithRotation> spawns) {
        this.spawns = spawns;
    }

    @Nullable
    public final List<PositionWithRotation> getSpawns() {
        return this.spawns;
    }

    public TeamSpawns shift(double dx, double dy, double dz) {
        if (this.spawns == null) return new TeamSpawns(null);

        List<PositionWithRotation> shifted = new ArrayList<>(spawns.size());
        for (PositionWithRotation pos : spawns) {
            shifted.add(pos.shifted(dx, dy, dz));
        }

        return new TeamSpawns(shifted);
    }
}
