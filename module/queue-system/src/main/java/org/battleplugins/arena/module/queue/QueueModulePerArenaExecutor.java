package org.battleplugins.arena.module.queue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.battleplugins.arena.Arena;
import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.command.ArenaCommand;
import org.battleplugins.arena.command.SubCommandExecutor;
import org.battleplugins.arena.feature.party.Parties;
import org.battleplugins.arena.feature.party.Party;
import org.battleplugins.arena.messages.Messages;
import org.battleplugins.arena.proxy.SerializedPlayer;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Per-arena executor that adds /&lt;arena&gt; queue.
 */
public class QueueModulePerArenaExecutor implements SubCommandExecutor {
    private final Arena arena;
    private final String parentCommand;
    private final QueueModule module;

    public QueueModulePerArenaExecutor(Arena arena, QueueModule module) {
        this.arena = arena;
        this.module = module;
        this.parentCommand = arena.getName().toLowerCase(Locale.ROOT);
    }

    @ArenaCommand(commands = { "queue", "q" }, description = "Queue for this arena.", permissionNode = "queue")
    public void queue(Player player) {
        BattleArena plugin = arena.getPlugin();

        if (plugin.isPendingProxyJoin(player.getUniqueId())) {
            Messages.ARENA_ERROR.send(player);
            return;
        }

        Party party = Parties.getParty(player.getUniqueId());
        if (party != null) {
            Messages.QUEUE_CANNOT_QUEUE_IN_PARTY.send(player);
            return;
        }

        boolean proxySupport = plugin.getMainConfig().isProxySupport();
        if (!proxySupport) {
            // Fallback: just use normal join when proxy support is disabled.
            Messages.ARENA_ERROR.send(player, "Proxy queueing requires proxy-support to be enabled.");
            return;
        }

        SerializedPlayer serialized = SerializedPlayer.toSerializedPlayer(player);
        java.util.UUID playerId = player.getUniqueId();
        boolean adding = !module.isLocallyQueued(playerId);
        if (adding) {
            module.addLocalQueue(playerId);
        } else {
            module.removeLocalQueue(playerId);
            // Leaving the queue locally; clear any pending proxy join state.
            plugin.removePendingProxyJoin(playerId);
        }

        String origin = plugin.getMainConfig().getProxyServerName();
        if (origin == null) {
            origin = "";
        }

        // If this is the proxy host, toggle directly; otherwise send a queue_join/queue_leave message to the host.
        if (plugin.getMainConfig().isProxyHost()) {
            Messages.ALREADY_IN_ARENA.send(player, "Cannot queue, already in arena.");
            return;
        } else if (plugin.getConnector() != null) {
            JsonObject payload = new JsonObject();

            if (adding) {
                // Joining the queue remotely
                payload.addProperty("type", "queue_join");
                payload.addProperty("arena", this.parentCommand);

                if (!origin.isEmpty()) {
                    payload.addProperty("origin", origin);
                }

                JsonObject playerObject = new JsonObject();
                playerObject.addProperty("uuid", serialized.getUuid());

                if (!serialized.getElements().isEmpty()) {
                    JsonArray elementsArray = new JsonArray();
                    serialized.getElements().forEach(element -> elementsArray.add(element.name()));
                    playerObject.add("elements", elementsArray);
                }

                if (!serialized.getAbilities().isEmpty()) {
                    JsonObject abilitiesObject = new JsonObject();
                    serialized.getAbilities().forEach((slot, ability) ->
                            abilitiesObject.addProperty(String.valueOf(slot), ability));
                    playerObject.add("abilities", abilitiesObject);
                }

                payload.add("player", playerObject);
            } else {
                // Leaving the queue remotely
                payload.addProperty("type", "queue_leave");
                payload.addProperty("uuid", serialized.getUuid());

                if (!origin.isEmpty()) {
                    payload.addProperty("origin", origin);
                }
            }

            plugin.getConnector().sendToRouter(payload.toString());
        }

        if (adding) {
            Messages.ARENA_JOINED.send(player, this.parentCommand);
        } else {
            Messages.ARENA_LEFT.send(player, this.parentCommand);
        }
    }
}
