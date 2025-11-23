package org.battleplugins.arena.module.ranked;

/**
 * Simple Elo calculator for head-to-head matches.
 * <p>
 * Uses the standard expected-score formula:
 * expected(scoreA) = 1 / (1 + 10 ^ ((ratingB - ratingA) / 400))
 * and updates each rating with: new = old + K * (actual - expected)
 */
public record EloCalculator(RankedConfig config) {

    public RankedEloUpdate calculate(double winnerElo, double loserElo) {
        return calculateWithFactors(winnerElo, loserElo, RankedMatchFactors.empty());
    }

    /**
     * Calculates ELO changes using both rating expectations and
     * performance-based factors.
     */
    public RankedEloUpdate calculateWithFactors(double winnerElo,
                                                double loserElo,
                                                RankedMatchFactors factors) {
        double expectedWinner = expectedScore(winnerElo, loserElo);
        double expectedLoser = expectedScore(loserElo, winnerElo);

        double k = applyPerformanceMultiplier(config.getKFactor(), winnerElo, loserElo, factors);

        double winnerNew = clamp(winnerElo + k * (1.0 - expectedWinner));
        double loserNew = clamp(loserElo + k * (0.0 - expectedLoser));

        return new RankedEloUpdate(
                winnerElo,
                loserElo,
                winnerNew,
                loserNew,
                winnerNew - winnerElo,
                loserNew - loserElo
        );
    }

    public RankedEloUpdate calculateDraw(double playerOneElo, double playerTwoElo) {
        return calculateDrawWithFactors(playerOneElo, playerTwoElo, RankedMatchFactors.empty(), RankedMatchFactors.empty());
    }

    public RankedEloUpdate calculateDrawWithFactors(double playerOneElo,
                                                    double playerTwoElo,
                                                    RankedMatchFactors playerOneFactors,
                                                    RankedMatchFactors playerTwoFactors) {
        double expectedPlayerOne = expectedScore(playerOneElo, playerTwoElo);
        double expectedPlayerTwo = expectedScore(playerTwoElo, playerOneElo);

        double playerOneK = applyPerformanceMultiplier(config.getKFactor(), playerOneElo, playerTwoElo, playerOneFactors);
        double playerTwoK = applyPerformanceMultiplier(config.getKFactor(), playerTwoElo, playerOneElo, playerTwoFactors);
        double k = (playerOneK + playerTwoK) / 2.0;

        double playerOneNew = clamp(playerOneElo + k * (0.5 - expectedPlayerOne));
        double playerTwoNew = clamp(playerTwoElo + k * (0.5 - expectedPlayerTwo));

        return new RankedEloUpdate(
                playerOneElo,
                playerTwoElo,
                playerOneNew,
                playerTwoNew,
                playerOneNew - playerOneElo,
                playerTwoNew - playerTwoElo
        );
    }

    public double clamp(double elo) {
        return Math.max(config.getMinElo(), Math.min(config.getMaxElo(), elo));
    }

    private double expectedScore(double ratingA, double ratingB) {
        double exponent = (ratingB - ratingA) / 400.0;
        return 1.0 / (1.0 + Math.pow(10.0, exponent));
    }

    private double applyPerformanceMultiplier(double baseK,
                                              double winnerElo,
                                              double loserElo,
                                              RankedMatchFactors factors) {
        double weightDamage = config.getWeightDamageEfficiency();
        double weightHealth = config.getWeightHealthLeft();
        double weightTime = config.getWeightTimeLeft();
        double weightEloGap = config.getWeightEloGap(); // biggest factor
        double totalWeight = weightDamage + weightHealth + weightTime + weightEloGap;
        if (totalWeight <= 0) {
            return baseK;
        }

        // Damage efficiency: lower damage taken per damage dealt is better.
        double damageEfficiency = 0.0;
        if (factors != null && factors.winnerDamageGiven() > 0) {
            double ratio = factors.winnerDamageTaken() / factors.winnerDamageGiven();
            damageEfficiency = clamp01(1.0 - ratio); // 1 = no damage taken, 0 = took equal or more than given
        }

        // Health remaining scaled.
        double healthScore = 0.0;
        if (factors != null && config.getMaxHealth() > 0) {
            healthScore = clamp01(factors.winnerHealthLeft() / config.getMaxHealth());
        }

        // Time left scaled.
        double timeScore = 0.0;
        if (factors != null && config.getMaxDuelTimeSeconds() > 0) {
            timeScore = clamp01(factors.duelTimeLeftSeconds() / config.getMaxDuelTimeSeconds());
        }

        // ELO gap: underdog victories get higher score, favorite wins get lower.
        double eloGapScore = 0.0;
        if (winnerElo > 0 && loserElo > 0) {
            double ratio = loserElo / winnerElo; // >1 means winner was lower rated
            eloGapScore = clamp01(ratio);
        }

        double weightedScore = (
                damageEfficiency * weightDamage +
                        healthScore * weightHealth +
                        timeScore * weightTime +
                        eloGapScore * weightEloGap
            ) / totalWeight;

            // Stretch the influence on K: baseline 0.5x .. 1.5x by default.
            double multiplier = 0.5 + (weightedScore * config.getPerformanceKMultiplier());
            return baseK * multiplier;
        }

        private double clamp01(double value) {
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }
}
