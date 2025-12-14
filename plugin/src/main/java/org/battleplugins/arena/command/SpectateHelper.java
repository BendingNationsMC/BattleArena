package org.battleplugins.arena.command;

import org.battleplugins.arena.ArenaPlayer;
import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.competition.Competition;
import org.battleplugins.arena.competition.PlayerRole;
import org.battleplugins.arena.messages.Messages;
import org.bukkit.entity.Player;

/**
 * Shared helper for handling local spectate logic so the arena-specific
 * commands and the global /spectate command stay consistent.
 */
final class SpectateHelper {

    private SpectateHelper() {
    }

    static void spectateCompetition(Player player, Competition<?> competition, BattleArena plugin) {
        if (ArenaPlayer.getArenaPlayer(player) != null) {
            Messages.ALREADY_IN_ARENA.send(player);
            return;
        }

        if (competition == null) {
            Messages.NO_ARENA_WITH_NAME.send(player);
            return;
        }

        competition.canJoin(player, PlayerRole.SPECTATING).whenComplete((result, e) -> {
            if (e != null) {
                Messages.ARENA_ERROR.send(player, e.getMessage());
                plugin.error("An error occurred while spectating the arena", e);
                return;
            }

            if (result.canJoin()) {
                competition.join(player, PlayerRole.SPECTATING);
                Messages.ARENA_SPECTATE.send(player, competition.getMap().getName());
            } else if (result.message() != null) {
                result.message().send(player);
            } else {
                Messages.ARENA_NOT_SPECTATABLE.send(player);
            }
        });
    }
}
