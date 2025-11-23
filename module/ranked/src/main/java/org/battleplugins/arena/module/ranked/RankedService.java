package org.battleplugins.arena.module.ranked;

import org.battleplugins.arena.proxy.Elements;

import java.util.Map;
import java.util.UUID;

/**
 * Public-facing ranked API for other modules to query/update ELO.
 * <p>
 * All reads/writes are routed through {@link RankedRedisClient} to
 * ensure Redis remains the single source of truth.
 */
public class RankedService {
    private final RankedRedisClient redis;
    private final EloCalculator calculator;
    private final RankedConfig config;

    public RankedService(RankedRedisClient redis, RankedConfig config) {
        this.redis = redis;
        this.calculator = new EloCalculator(config);
        this.config = config;
    }

    public double getElo(UUID playerId, Elements element) {
        return redis.getElo(playerId, element, config.getDefaultElo());
    }

    public Map<Elements, Double> getAllElo(UUID playerId) {
        return redis.getAllElo(playerId, config.getDefaultElo());
    }

    public double getAverageElo(UUID playerId) {
        Map<Elements, Double> all = getAllElo(playerId);
        if (all.isEmpty()) {
            return config.getDefaultElo();
        }

        double total = 0.0;
        for (double value : all.values()) {
            total += value;
        }
        return total / all.size();
    }

    public void setElo(UUID playerId, Elements element, double value) {
        double clamped = calculator.clamp(value);
        redis.setElo(playerId, element, clamped);
        if (config.isUseGlobalAverage()) {
            redis.updateGlobalLeaderboard(playerId, getAverageElo(playerId));
        }
    }

    public RankedEloUpdate updateAfterMatch(UUID winnerId, UUID loserId, Elements element) {
        return updateAfterMatch(winnerId, loserId, element, RankedMatchFactors.empty());
    }

    public RankedEloUpdate updateAfterMatch(UUID winnerId,
                                            UUID loserId,
                                            Elements element,
                                            RankedMatchFactors factors) {
        return updateAfterMatch(winnerId, loserId, element, element, factors, factors);
    }

    public RankedEloUpdate updateAfterMatch(UUID winnerId,
                                            UUID loserId,
                                            Elements winnerElement,
                                            Elements loserElement,
                                            RankedMatchFactors winnerFactors,
                                            RankedMatchFactors loserFactors) {
        double winnerElo = getElo(winnerId, winnerElement);
        double loserElo = getElo(loserId, loserElement);

        RankedEloUpdate update = calculator.calculateWithFactors(winnerElo, loserElo, winnerFactors);
        redis.setElo(winnerId, winnerElement, update.winnerNew());
        redis.setElo(loserId, loserElement, update.loserNew());

        if (config.isUseGlobalAverage()) {
            redis.updateGlobalLeaderboard(winnerId, getAverageElo(winnerId));
            redis.updateGlobalLeaderboard(loserId, getAverageElo(loserId));
        }

        return update;
    }

    public RankedEloUpdate updateAfterDraw(UUID firstPlayerId, UUID secondPlayerId, Elements element) {
        return updateAfterDraw(firstPlayerId, secondPlayerId, element, element);
    }

    public RankedEloUpdate updateAfterDraw(UUID firstPlayerId,
                                           UUID secondPlayerId,
                                           Elements firstElement,
                                           Elements secondElement) {
        return updateAfterDraw(firstPlayerId, secondPlayerId, firstElement, secondElement,
                RankedMatchFactors.empty(), RankedMatchFactors.empty());
    }

    public RankedEloUpdate updateAfterDraw(UUID firstPlayerId,
                                           UUID secondPlayerId,
                                           Elements firstElement,
                                           Elements secondElement,
                                           RankedMatchFactors firstFactors,
                                           RankedMatchFactors secondFactors) {
        double firstElo = getElo(firstPlayerId, firstElement);
        double secondElo = getElo(secondPlayerId, secondElement);

        RankedEloUpdate update = calculator.calculateDrawWithFactors(firstElo, secondElo, firstFactors, secondFactors);
        redis.setElo(firstPlayerId, firstElement, update.winnerNew());
        redis.setElo(secondPlayerId, secondElement, update.loserNew());

        if (config.isUseGlobalAverage()) {
            redis.updateGlobalLeaderboard(firstPlayerId, getAverageElo(firstPlayerId));
            redis.updateGlobalLeaderboard(secondPlayerId, getAverageElo(secondPlayerId));
        }

        return update;
    }

    public Long getRank(UUID playerId, Elements element) {
        return redis.getRank(playerId, element);
    }

    public Long getGlobalRank(UUID playerId) {
        return redis.getGlobalRank(playerId);
    }

    public RankedConfig getConfig() {
        return config;
    }
}
