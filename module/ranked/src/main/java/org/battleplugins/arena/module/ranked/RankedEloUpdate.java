package org.battleplugins.arena.module.ranked;

/**
 * Represents the before/after delta for an ELO update between two players.
 */
public record RankedEloUpdate(
        double winnerOld,
        double loserOld,
        double winnerNew,
        double loserNew,
        double winnerDelta,
        double loserDelta
) {
}
