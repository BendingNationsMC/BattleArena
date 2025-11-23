package org.battleplugins.arena.ranked;

import org.battleplugins.arena.proxy.Elements;

import java.util.Map;
import java.util.UUID;

/**
 * Minimal ranked API exposed by the core plugin so modules
 * can register implementations without reflection.
 */
public interface RankedApi {
    Map<Elements, Double> getAllElo(UUID playerId);

    double getAverageElo(UUID playerId);

    Long getRank(UUID playerId, Elements element);

    Long getGlobalRank(UUID playerId);
}
