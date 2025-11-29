package org.battleplugins.arena.event.action.types;

import org.battleplugins.arena.Arena;
import org.battleplugins.arena.ArenaPlayer;
import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.competition.Competition;
import org.battleplugins.arena.event.action.EventAction;
import org.battleplugins.arena.resolver.Resolvable;
import org.bukkit.Bukkit;

import java.util.Map;

public class RunCommandAction extends EventAction {
    private static final String COMMAND_KEY = "command";
    private static final String SOURCE_KEY = "source";
    private static final String LOOP_KEY = "loop";

    public RunCommandAction(Map<String, String> params) {
        super(params, COMMAND_KEY);
    }

    @Override
    public void call(ArenaPlayer arenaPlayer, Resolvable resolvable) {
        if (!Boolean.parseBoolean(getOrDefault(LOOP_KEY, "true"))) {
            return;
        }

        String command = resolvable.resolve().resolveToString(this.get(COMMAND_KEY));
        String source = this.getOrDefault(SOURCE_KEY, "player");
        if (source.equalsIgnoreCase("player")) {
            arenaPlayer.getPlayer().performCommand(command);
            return;
        }

        if (source.equalsIgnoreCase("console")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            return;
        }

        throw new IllegalArgumentException("Invalid source " + source + " for command " + command);
    }

    @Override
    public void postProcess(Arena arena, Competition<?> competition, Resolvable resolvable) {
        if (Boolean.parseBoolean(getOrDefault(LOOP_KEY, "true"))) {
            return;
        }

        String source = this.getOrDefault(SOURCE_KEY, "player");
        if (source.equals("player")) {
            BattleArena.getInstance().getLogger().info("Loop is true while source is player. Not possible!");
            return;
        }

        String command = resolvable.resolve().resolveToString(this.get(COMMAND_KEY));

        if (source.equalsIgnoreCase("console")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            return;
        }

        throw new IllegalArgumentException("Invalid source " + source + " for command " + command);
    }
}
