package org.battleplugins.arena.command;

import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.duel.DuelMenuService;
import org.battleplugins.arena.messages.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Global /duel command that opens the duel menu GUI.
 */
public class DuelCommandExecutor extends BaseCommandExecutor {
    private final DuelMenuService duelMenuService;

    public DuelCommandExecutor(DuelMenuService duelMenuService) {
        super("duel");
        this.duelMenuService = duelMenuService;
    }

    public TabExecutor standaloneExecutor() {
        return new StandaloneAdapter(this);
    }

    @ArenaCommand(
            commands = {"duel"},
            description = "Open the duel arena selector.",
            permissionNode = "duel",
            minArgs = 0,
            maxArgs = 0
    )
    public CommandResult duel(CommandSender sender) {
        return this.openMenu(sender, null, 1);
    }

    @ArenaCommand(
            commands = {"duel"},
            description = "Open the duel arena selector against another player.",
            permissionNode = "duel",
            minArgs = 1,
            maxArgs = 1
    )
    public CommandResult duel(CommandSender sender, @Argument(name = "player") Player target) {
        return this.openMenu(sender, target, 1);
    }

    @ArenaCommand(
            commands = {"duel"},
            description = "Open the duel arena selector against another player in a best-of series.",
            permissionNode = "duel",
            minArgs = 2,
            maxArgs = 2
    )
    public CommandResult duel(CommandSender sender, @Argument(name = "player") Player target, @Argument(name = "rounds") int rounds) {
        if (!this.isValidRounds(rounds)) {
            Messages.ARENA_ERROR.send(sender, "Rounds must be an odd number greater than 0.");
            return CommandResult.COMMAND_ERROR_HANDLED;
        }

        return this.openMenu(sender, target, rounds);
    }

    private CommandResult openMenu(CommandSender sender, Player target, int rounds) {
        if (!(sender instanceof Player player)) {
            Messages.MUST_BE_PLAYER.send(sender);
            return CommandResult.COMMAND_ERROR_HANDLED;
        }

        BattleArena plugin = BattleArena.getInstance();
        if (plugin != null && plugin.getMainConfig().isProxySupport() && plugin.getMainConfig().isProxyHost()) {
            Messages.ARENA_ERROR.send(player, "Duels must be initiated from a non-host server.");
            return CommandResult.COMMAND_ERROR_HANDLED;
        }

        this.duelMenuService.openArenaMenu(player, target, rounds);
        return CommandResult.SUCCESS;
    }

    private boolean isValidRounds(int rounds) {
        return rounds > 0 && (rounds % 2) == 1;
    }

    private static final class StandaloneAdapter implements TabExecutor {
        private final DuelCommandExecutor delegate;

        private StandaloneAdapter(DuelCommandExecutor delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            return delegate.onCommand(sender, command, label, this.ensureSubCommand(args));
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
            return delegate.onTabComplete(sender, command, label, this.ensureSubCommand(args));
        }

        private String[] ensureSubCommand(String[] args) {
            if (args.length == 0 || !"duel".equalsIgnoreCase(args[0])) {
                String[] adjusted = new String[args.length + 1];
                adjusted[0] = "duel";
                System.arraycopy(args, 0, adjusted, 1, args.length);
                return adjusted;
            }

            return args;
        }
    }
}
