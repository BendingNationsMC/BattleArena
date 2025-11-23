package org.battleplugins.arena.module.ranked;

import org.battleplugins.arena.config.ArenaOption;
import org.battleplugins.arena.config.DocumentationSource;

/**
 * Configuration for the ranked/ELO system.
 * <p>
 * These values control the default ratings as well as the tuning
 * constants for the ELO calculations.
 */
@DocumentationSource("https://docs.battleplugins.org")
public class RankedConfig {

    @ArenaOption(name = "default-elo", description = "Default ELO assigned to a player for any element.", required = true)
    private double defaultElo = 1000.0;

    @ArenaOption(name = "k-factor", description = "K-factor used when adjusting ELO after a match.", required = true)
    private double kFactor = 32.0;

    @ArenaOption(name = "min-elo", description = "Minimum allowed ELO.", required = true)
    private double minElo = 0.0;

    @ArenaOption(name = "max-elo", description = "Maximum allowed ELO.", required = true)
    private double maxElo = 3000.0;

    @ArenaOption(name = "use-global-average", description = "Whether to compute and expose a global/average ELO placeholder.", required = true)
    private boolean useGlobalAverage = true;

    @ArenaOption(name = "maintain-leaderboards", description = "Whether to maintain Redis sorted-set leaderboards for ranking placeholders.", required = true)
    private boolean maintainLeaderboards = true;

    @ArenaOption(name = "redis-prefix", description = "Redis key prefix used to namespace ranked data.", required = true)
    private String redisPrefix = "ranked";

    @ArenaOption(name = "max-health", description = "Maximum health considered when scaling health-based performance.", required = true)
    private double maxHealth = 20.0;

    @ArenaOption(name = "max-duel-time-seconds", description = "Maximum duel time considered when scaling time-left performance.", required = true)
    private double maxDuelTimeSeconds = 300.0;

    @ArenaOption(name = "weight-damage-efficiency", description = "Weight for damage taken/given ratio when scaling ELO changes.", required = true)
    private double weightDamageEfficiency = 0.2;

    @ArenaOption(name = "weight-health-left", description = "Weight for remaining health when scaling ELO changes.", required = true)
    private double weightHealthLeft = 0.2;

    @ArenaOption(name = "weight-time-left", description = "Weight for duel time left when scaling ELO changes.", required = true)
    private double weightTimeLeft = 0.1;

    @ArenaOption(name = "weight-elo-gap", description = "Weight for winner/loser ELO gap (largest factor).", required = true)
    private double weightEloGap = 0.5;

    @ArenaOption(name = "performance-k-multiplier", description = "Multiplier applied to the performance score to stretch/shrink K-factor influence.", required = true)
    private double performanceKMultiplier = 1.0;

    public double getDefaultElo() {
        return defaultElo;
    }

    public double getKFactor() {
        return kFactor;
    }

    public double getMinElo() {
        return minElo;
    }

    public double getMaxElo() {
        return maxElo;
    }

    public boolean isUseGlobalAverage() {
        return useGlobalAverage;
    }

    public boolean isMaintainLeaderboards() {
        return maintainLeaderboards;
    }

    public String getRedisPrefix() {
        return redisPrefix;
    }

    public double getMaxHealth() {
        return maxHealth;
    }

    public double getMaxDuelTimeSeconds() {
        return maxDuelTimeSeconds;
    }

    public double getWeightDamageEfficiency() {
        return weightDamageEfficiency;
    }

    public double getWeightHealthLeft() {
        return weightHealthLeft;
    }

    public double getWeightTimeLeft() {
        return weightTimeLeft;
    }

    public double getWeightEloGap() {
        return weightEloGap;
    }

    public double getPerformanceKMultiplier() {
        return performanceKMultiplier;
    }
}
