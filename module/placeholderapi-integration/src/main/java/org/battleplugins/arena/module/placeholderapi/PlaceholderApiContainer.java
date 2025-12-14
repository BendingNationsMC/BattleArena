package org.battleplugins.arena.module.placeholderapi;

import org.battleplugins.arena.BattleArena;

import java.util.List;

public class PlaceholderApiContainer {
    private final List<BattleArenaExpansion> expansions;

    public PlaceholderApiContainer(BattleArena plugin) {
        this.expansions = List.of(
                new BattleArenaExpansion(plugin, "ba"),
                new BattleArenaExpansion(plugin, "battlearena")
        );
        this.expansions.forEach(expansion -> expansion.register());
    }

    public void disable() {
        this.expansions.forEach(expansion -> expansion.unregister());
    }
}
