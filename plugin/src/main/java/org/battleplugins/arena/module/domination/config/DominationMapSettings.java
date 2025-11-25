package org.battleplugins.arena.module.domination.config;

import org.battleplugins.arena.config.ArenaOption;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Map-scoped domination settings (areas defined on a map).
 */
public class DominationMapSettings {

    @ArenaOption(name = "areas", description = "The domination areas configured for this map.", required = true)
    private Map<String, DominationAreaDefinition> areas;

    public DominationMapSettings() {
    }

    public DominationMapSettings(Map<String, DominationAreaDefinition> areas) {
        this.areas = new LinkedHashMap<>(areas);
    }

    public Map<String, DominationAreaDefinition> getAreas() {
        return this.areas == null ? Map.of() : Map.copyOf(this.areas);
    }

    public DominationMapSettings shift(double dx, double dy, double dz) {
        if (this.areas == null || this.areas.isEmpty()) {
            return new DominationMapSettings(Map.of());
        }

        Map<String, DominationAreaDefinition> shifted = new LinkedHashMap<>();
        this.areas.forEach((key, definition) -> shifted.put(key, definition.shifted(dx, dy, dz)));
        return new DominationMapSettings(shifted);
    }
}
