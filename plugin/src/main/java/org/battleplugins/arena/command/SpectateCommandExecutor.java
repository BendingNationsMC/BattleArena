package org.battleplugins.arena.command;

import org.battleplugins.arena.ArenaPlayer;
import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.competition.Competition;
import org.battleplugins.arena.competition.PlayerRole;
import org.battleplugins.arena.event.player.ArenaLeaveEvent;
import org.battleplugins.arena.messages.Messages;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Global /spectate command that lets players follow anyone on the network.
 */
public class SpectateCommandExecutor extends BaseCommandExecutor {
    private final BattleArena plugin;

    public SpectateCommandExecutor(BattleArena plugin) {
        super("spectate");
        this.plugin = plugin;
    }

    public TabExecutor standaloneExecutor() {
        return new SpectateCommandExecutor.StandaloneAdapter(this);
    }

    @ArenaCommand(
            commands = {"spectate"},
            description = "Stop spectating and return to your origin server.",
            permissionNode = "spectate",
            minArgs = 0,
            maxArgs = 0
    )
    public void spectate(Player player) {
        ArenaPlayer arenaPlayer = ArenaPlayer.getArenaPlayer(player);
        if (arenaPlayer == null) {
            Messages.NOT_IN_ARENA.send(player);
            return;
        }

        if (arenaPlayer.getRole() != PlayerRole.SPECTATING) {
            Messages.ALREADY_IN_ARENA.send(player);
            return;
        }

        this.stopSpectating(player, arenaPlayer);
    }

    @ArenaCommand(
            commands = {"spectate"},
            description = "Spectate the arena match a player is currently in.",
            permissionNode = "spectate",
            minArgs = 1,
            maxArgs = 1
    )
    public void spectate(Player player, @Argument(name = "player") String targetName) {
        ArenaPlayer arenaPlayer = ArenaPlayer.getArenaPlayer(player);
        if (arenaPlayer != null) {
            if (arenaPlayer.getRole() != PlayerRole.SPECTATING && plugin.getMainConfig().isProxyHost()) {
                Messages.ALREADY_IN_ARENA.send(player);
                return;
            }

            if (plugin.getMainConfig().isProxyHost()) {
                this.stopSpectating(player, arenaPlayer);
            }
        }

        Player targetOnline = Bukkit.getPlayerExact(targetName);
        if (targetOnline != null) {
            ArenaPlayer targetArenaPlayer = ArenaPlayer.getArenaPlayer(targetOnline);
            if (targetArenaPlayer == null || targetArenaPlayer.getCompetition() == null) {
                Messages.NOT_IN_ARENA.send(player);
                return;
            }

            Competition<?> competition = targetArenaPlayer.getCompetition();
            if (competition != null && this.tryLocalSpectate(player, competition)) {
                return;
            }
        }

        // Target not on this backend. Try proxy spectate if enabled.
        if (!this.plugin.requestProxyPlayerSpectate(player, this.resolveTargetId(targetName), targetName)) {
            Messages.PLAYER_NOT_ONLINE.send(player, targetName);
        }
    }

    private boolean tryLocalSpectate(Player player, Competition<?> competition) {
        if (competition == null) {
            return false;
        }

        SpectateHelper.spectateCompetition(player, competition, this.plugin);
        return true;
    }

    private void stopSpectating(Player player, ArenaPlayer arenaPlayer) {
        String mapName = arenaPlayer.getCompetition().getMap().getName();
        arenaPlayer.getCompetition().leave(player, ArenaLeaveEvent.Cause.COMMAND);
        Messages.ARENA_LEFT.send(player, mapName);
    }

    private UUID resolveTargetId(String targetName) {
        try {
            return UUID.fromString(targetName);
        } catch (IllegalArgumentException ignored) {
        }

        OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
        return offline.getUniqueId();
    }

    @Override
    protected List<String> onVerifyTabComplete(String arg, Class<?> parameter) {
        List<String> completions = super.onVerifyTabComplete(arg, parameter);
        if (parameter == String.class) {
            List<String> duelPlayers = this.plugin.getSpectatableDuelPlayers();
            if (duelPlayers.isEmpty()) {
                return completions;
            }

            if (completions == null || completions.isEmpty()) {
                return duelPlayers;
            }

            List<String> merged = new ArrayList<>(completions);
            merged.addAll(duelPlayers);
            return merged;
        }

        return completions;
    }

    private static final class StandaloneAdapter implements TabExecutor {
        private final SpectateCommandExecutor delegate;

        private StandaloneAdapter(SpectateCommandExecutor delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean onCommand(CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
            return this.delegate.onCommand(sender, command, label, this.ensureSubCommand(args));
        }

        @Override
        public java.util.List<String> onTabComplete(CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
            return this.delegate.onTabComplete(sender, command, label, this.ensureSubCommand(args));
        }

        private String[] ensureSubCommand(String[] args) {
            if (args.length == 0 || !"spectate".equalsIgnoreCase(args[0])) {
                String[] adjusted = new String[args.length + 1];
                adjusted[0] = "spectate";
                System.arraycopy(args, 0, adjusted, 1, args.length);
                return adjusted;
            }

            return args;
        }
    }
}
