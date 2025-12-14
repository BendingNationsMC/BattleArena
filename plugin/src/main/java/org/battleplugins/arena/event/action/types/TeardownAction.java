package org.battleplugins.arena.event.action.types;

import org.battleplugins.arena.Arena;
import org.battleplugins.arena.ArenaPlayer;
import org.battleplugins.arena.competition.Competition;
import org.battleplugins.arena.competition.LiveCompetition;
import org.battleplugins.arena.event.action.EventAction;
import org.battleplugins.arena.event.player.ArenaLeaveEvent;
import org.battleplugins.arena.resolver.Resolvable;

import java.util.Map;

public class TeardownAction extends EventAction {

    public TeardownAction(Map<String, String> params, String... requiredKeys) {
        super(params, requiredKeys);
    }

    @Override
    public void call(ArenaPlayer arenaPlayer, Resolvable resolvable) {
    }

    @Override
    public void postProcess(Arena arena, Competition<?> competition, Resolvable resolvable) {
        if (competition instanceof LiveCompetition<?> liveCompetition) {
            if (resolvable instanceof ArenaLeaveEvent leaveEvent) {
                ArenaLeaveEvent.Cause cause = leaveEvent.getCause();
                boolean terminalCause = cause == ArenaLeaveEvent.Cause.GAME
                        || cause == ArenaLeaveEvent.Cause.SHUTDOWN
                        || cause == ArenaLeaveEvent.Cause.REMOVED;

                if (!terminalCause && !liveCompetition.getPlayers().isEmpty()) {
                    return;
                }
            } else if (!liveCompetition.getPlayers().isEmpty()) {
                return;
            }
        }

        arena.getPlugin().removeCompetition(arena, competition);
    }
}
