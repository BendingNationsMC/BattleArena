package org.battleplugins.arena.module.ranked;

/**
 * Optional performance factors that influence ELO adjustments.
 *
 * @param winnerDamageGiven   total damage dealt by the winner
 * @param winnerDamageTaken   total damage taken by the winner
 * @param winnerHealthLeft    health remaining for the winner at match end
 * @param duelTimeLeftSeconds time left (seconds) when the duel ended
 */
public record RankedMatchFactors(
        double winnerDamageGiven,
        double winnerDamageTaken,
        double winnerHealthLeft,
        double duelTimeLeftSeconds
) {
    public static RankedMatchFactors empty() {
        return new RankedMatchFactors(0, 0, 0, 0);
    }
}
