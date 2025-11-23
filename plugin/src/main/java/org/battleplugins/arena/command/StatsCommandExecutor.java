package org.battleplugins.arena.command;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.command.BaseCommandExecutor.CommandResult;
import org.battleplugins.arena.proxy.Elements;
import org.battleplugins.arena.ranked.RankedApi;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Global /stats command that pulls ranked ELO if the ranked module is present.
 */
public class StatsCommandExecutor extends BaseCommandExecutor {
    private static final Logger log = LoggerFactory.getLogger(StatsCommandExecutor.class);
    private final BattleArena plugin;

    public StatsCommandExecutor(BattleArena plugin) {
        super("stats");
        this.plugin = plugin;
    }

    public TabExecutor standaloneExecutor() {
        return new StandaloneAdapter(this);
    }

    @ArenaCommand(
            commands = {"stats"},
            description = "Show ranked ELO stats for yourself or another player.",
            permissionNode = "stats",
            minArgs = 0,
            maxArgs = 1
    )
    public CommandResult stats(CommandSender sender) {
        return this.stats(sender, (OfflinePlayer) null);
    }

    @ArenaCommand(
            commands = {"stats"},
            description = "Show ranked ELO stats for another player.",
            permissionNode = "stats",
            minArgs = 1,
            maxArgs = 1
    )
    public CommandResult stats(CommandSender sender, @Argument(name = "offlineplayer") OfflinePlayer target) {
        UUID id;
        String name;
        if (target != null) {
            id = target.getUniqueId();
            name = target.getName() == null ? target.getUniqueId().toString() : target.getName();
        } else if (sender instanceof Player player) {
            id = player.getUniqueId();
            name = player.getName();
        } else {
            sender.sendMessage(Component.text("You must specify a player.", NamedTextColor.RED));
            return CommandResult.COMMAND_ERROR_HANDLED;
        }

        Optional<RankedApi> rankedServiceOpt = resolveRankedService();
        if (rankedServiceOpt.isEmpty()) {
            sender.sendMessage(Component.text("Ranked module is not active on this server.", NamedTextColor.RED));
            return CommandResult.COMMAND_ERROR_HANDLED;
        }

        RankedApi rankedService = rankedServiceOpt.get();

        if (target != null && target.getName() != null && !target.hasPlayedBefore()) {
            String lookupName = target.getName();
            resolveFromMojang(lookupName).whenComplete((resolvedId, throwable) -> {
                Bukkit.getScheduler().runTask(this.plugin, () -> {
                    if (throwable != null) {
                        log.warn("Failed to resolve Mojang UUID for {}", lookupName, throwable);
                        sender.sendMessage(Component.text("Unable to resolve ranked stats for " + lookupName + " right now.", NamedTextColor.RED));
                        return;
                    }

                    if (resolvedId == null) {
                        sender.sendMessage(Component.text("Unable to find profile for " + lookupName + ".", NamedTextColor.RED));
                        return;
                    }

                    this.sendRankedStats(sender, rankedService, resolvedId, lookupName);
                });
            });
            return CommandResult.SUCCESS;
        }

        return this.sendRankedStats(sender, rankedService, id, name);
    }

    private CommandResult sendRankedStats(CommandSender sender, RankedApi rankedService, UUID id, String name) {
        try {
            Map<Elements, Double> perElement = rankedService.getAllElo(id);
            double global = rankedService.getAverageElo(id);

            sender.sendMessage(Component.text("Ranked stats for " + name, NamedTextColor.GOLD));
            for (Elements element : Elements.values()) {
                double elo = perElement.getOrDefault(element, 0.0);
                Long rank = rankedService.getRank(id, element);
                sender.sendMessage(Component.text(" - " + element.name() + ": " + Math.round(elo) +
                        (rank != null ? " (rank #" + rank + ")" : ""), NamedTextColor.YELLOW));
            }

            Long globalRank = rankedService.getGlobalRank(id);
            sender.sendMessage(Component.text("Global avg: " + Math.round(global) +
                    (globalRank != null ? " (rank #" + globalRank + ")" : ""), NamedTextColor.AQUA));

            return CommandResult.SUCCESS;
        } catch (Exception e) {
            plugin.error("Failed to fetch ranked stats", e);
            sender.sendMessage(Component.text("Unable to fetch ranked stats right now.", NamedTextColor.RED));
            return CommandResult.COMMAND_ERROR_HANDLED;
        }
    }

    private Optional<RankedApi> resolveRankedService() {
        return Optional.ofNullable(plugin.getRankedApi());
    }

    @Override
    protected Object onVerifyArgument(CommandSender sender, String arg, Class<?> parameter) {
        if (parameter.equals(OfflinePlayer.class)) {
            Player online = Bukkit.getPlayer(arg);
            if (online != null) {
                return online;
            }
            return Bukkit.getOfflinePlayer(arg);
        }
        return super.onVerifyArgument(sender, arg, parameter);
    }

    public CompletableFuture<UUID> resolveFromMojang(String name) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                URL url = new URL("https://api.mojang.com/users/profiles/minecraft/" + name);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                if (conn.getResponseCode() != 200) {
                    return null; // not found or error
                }

                try (Reader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                    String rawId = json.get("id").getAsString(); // 32-char hex
                    String withDashes = rawId.replaceFirst(
                            "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                            "$1-$2-$3-$4-$5"
                    );
                    return UUID.fromString(withDashes);
                }
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        });
    }

    private static final class StandaloneAdapter implements TabExecutor {
        private final StatsCommandExecutor delegate;

        private StandaloneAdapter(StatsCommandExecutor delegate) {
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
            if (args.length == 0 || !"stats".equalsIgnoreCase(args[0])) {
                String[] adjusted = new String[args.length + 1];
                adjusted[0] = "stats";
                System.arraycopy(args, 0, adjusted, 1, args.length);
                return adjusted;
            }

            return args;
        }
    }
}
