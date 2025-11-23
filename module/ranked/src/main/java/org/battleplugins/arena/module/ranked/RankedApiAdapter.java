package org.battleplugins.arena.module.ranked;

import org.battleplugins.arena.proxy.Elements;
import org.battleplugins.arena.ranked.RankedApi;

import java.util.Map;
import java.util.UUID;

/**
 * Adapter to expose RankedService as the core RankedApi.
 */
public class RankedApiAdapter implements RankedApi {
    private final RankedService service;

    public RankedApiAdapter(RankedService service) {
        this.service = service;
    }

    @Override
    public Map<Elements, Double> getAllElo(UUID playerId) {
        return service.getAllElo(playerId);
    }

    @Override
    public double getAverageElo(UUID playerId) {
        return service.getAverageElo(playerId);
    }

    @Override
    public Long getRank(UUID playerId, Elements element) {
        return service.getRank(playerId, element);
    }

    @Override
    public Long getGlobalRank(UUID playerId) {
        return service.getGlobalRank(playerId);
    }
}
