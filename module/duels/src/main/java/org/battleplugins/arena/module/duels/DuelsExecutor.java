package org.battleplugins.arena.module.duels;

import org.battleplugins.arena.Arena;
import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.command.ArenaCommand;
import org.battleplugins.arena.command.SubCommandExecutor;
import org.battleplugins.arena.competition.map.LiveCompetitionMap;
import org.battleplugins.arena.competition.map.MapType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.battleplugins.arena.duel.DuelSelectionRegistry;
import org.battleplugins.arena.feature.party.Parties;
import org.battleplugins.arena.feature.party.Party;
import org.battleplugins.arena.feature.party.PartyMember;
import org.battleplugins.arena.messages.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.UUID;

public class DuelsExecutor implements SubCommandExecutor {
    private final Duels module;
    private final Arena arena;
    private final String parentCommand;

    public DuelsExecutor(Duels module, Arena arena) {
        this.module = module;
        this.arena = arena;
        this.parentCommand = arena.getName().toLowerCase(Locale.ROOT);
    }

    @ArenaCommand(commands = "duel", description = "Duel another player.", permissionNode = "duel")
    public void duel(Player player, Player target) {
        if (player.equals(target)) {
            DuelsMessages.CANNOT_DUEL_SELF.send(player);
            return;
        }

        if (!this.ensurePartyLeader(player)) {
            return;
        }

        BattleArena plugin = BattleArena.getInstance();
        if (plugin != null && plugin.getMainConfig().isProxySupport() && plugin.getMainConfig().isProxyHost()) {
            Messages.ARENA_ERROR.send(player, "Duels must be initiated from a non-host server.");
            return;
        }

        Party targetParty = Parties.getParty(target.getUniqueId());
        if (targetParty != null) {
            PartyMember leader = targetParty.getLeader();
            if (leader != null && !leader.getUniqueId().equals(target.getUniqueId())) {
                DuelsMessages.TARGET_MUST_BE_PARTY_LEADER.send(player, leader.getName());
                return;
            }
        }

        if (this.module.hasOutgoingRequest(player.getUniqueId())) {
            DuelsMessages.DUEL_REQUEST_ALREADY_SENT.send(player, this.parentCommand);
            return;
        }

        if (this.module.hasIncomingRequest(target.getUniqueId())) {
            DuelsMessages.TARGET_HAS_PENDING_REQUEST.send(player, target.getName());
            return;
        }

        String preferredMap = null;
        if (plugin != null) {
            DuelSelectionRegistry registry = plugin.getDuelSelectionRegistry();
            DuelSelectionRegistry.DuelSelection selection = registry.consume(player.getUniqueId(), target.getUniqueId(), this.arena.getName()).orElse(null);
            if (selection != null) {
                LiveCompetitionMap selectedMap = plugin.getMap(this.arena, selection.mapName());
                if (selectedMap == null || selectedMap.getType() != MapType.DYNAMIC) {
                    Messages.ARENA_ERROR.send(player, "Selected duel map is no longer available. Using a random map.");
                } else if (plugin.getMainConfig().isProxySupport() && plugin.getMainConfig().isProxyHost() && !selectedMap.isRemote()) {
                    Messages.ARENA_ERROR.send(player, "Selected duel map cannot run on the proxy host. Using a random map.");
                } else {
                    preferredMap = selectedMap.getName();
                }
            }
        }

        DuelsMessages.DUEL_REQUEST_SENT.send(player, target.getName());
        DuelsMessages.DUEL_REQUEST_RECEIVED.send(
                target,
                player.getName(),
                this.parentCommand,
                player.getName(),
                this.parentCommand,
                player.getName()
        );

        Component acceptButton = Component.text("[ACCEPT]", NamedTextColor.GREEN, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/" + this.parentCommand + " duel accept " + player.getName()))
                .hoverEvent(HoverEvent.showText(Component.text("Click to accept duel request.", NamedTextColor.GREEN)));

        Component denyButton = Component.text("[DENY]", NamedTextColor.RED, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/" + this.parentCommand + " duel deny " + player.getName()))
                .hoverEvent(HoverEvent.showText(Component.text("Click to deny duel request.", NamedTextColor.RED)));

        target.sendMessage(Messages.PREFIX.toComponent()
                .append(Component.space())
                .append(Component.text("Respond: ", NamedTextColor.GOLD))
                .append(acceptButton)
                .append(Component.text(" ", NamedTextColor.GRAY))
                .append(denyButton));

        this.module.addDuelRequest(player, target, preferredMap);
    }

    @ArenaCommand(commands = "duel", subCommands = "accept", description = "Accept a duel request.", permissionNode = "duel.accept")
    public void acceptDuel(Player player, Player target) {
        if (!this.ensurePartyLeader(player)) {
            return;
        }

        Duels.DuelRequest request = this.module.getIncomingRequest(player.getUniqueId());
        if (request == null) {
            DuelsMessages.NO_DUEL_REQUESTS.send(player);
            return;
        }

        if (!request.getRequester().equals(target.getUniqueId())) {
            DuelsMessages.USER_DID_NOT_REQUEST.send(player, target.getName());
            return;
        }

        DuelsMessages.DUEL_REQUEST_ACCEPTED.send(player, target.getName());
        DuelsMessages.ACCEPTED_DUEL_REQUEST.send(target, player.getName());

        this.module.removeDuelRequest(request);

        BattleArena plugin = BattleArena.getInstance();
        if (plugin != null) {
            Bukkit.getScheduler().runTask(plugin, () ->
                    this.module.acceptDuel(this.arena, target, player, request.getRequesterParty(), request.getTargetParty(), request.getPreferredMap())
            );
        } else {
            this.module.acceptDuel(this.arena, target, player, request.getRequesterParty(), request.getTargetParty(), request.getPreferredMap());
        }
    }
    
    @ArenaCommand(commands = "duel", subCommands = "deny", description = "Deny a duel request.", permissionNode = "duel.deny")
    public void denyDuel(Player player, Player target) {
        Duels.DuelRequest request = this.module.getIncomingRequest(player.getUniqueId());
        if (request == null) {
            DuelsMessages.NO_DUEL_REQUESTS.send(player);
            return;
        }

        if (!request.getRequester().equals(target.getUniqueId())) {
            DuelsMessages.USER_DID_NOT_REQUEST.send(player, target.getName());
            return;
        }

        DuelsMessages.DUEL_REQUEST_DENIED.send(player, target.getName());
        DuelsMessages.DENIED_DUEL_REQUEST.send(target, player.getName());
        
        this.module.removeDuelRequest(request);
    }
    
    @ArenaCommand(commands = "duel", subCommands = "cancel", description = "Cancel a duel request.", permissionNode = "duel.cancel")
    public void cancelDuel(Player player) {
        Duels.DuelRequest request = this.module.getOutgoingRequest(player.getUniqueId());
        if (request == null) {
            DuelsMessages.NO_DUEL_REQUESTS.send(player);
            return;
        }

        Player target = Bukkit.getPlayer(request.getTarget());
        if (target == null) {
            this.module.removeDuelRequest(request);
            
            DuelsMessages.NO_DUEL_REQUESTS.send(player);
            return;
        }
        
        DuelsMessages.DUEL_REQUEST_CANCELLED.send(player, target.getName());
        DuelsMessages.CANCELLED_DUEL_REQUEST.send(target, player.getName());

        this.module.removeDuelRequest(request);
    }

    private boolean ensurePartyLeader(Player player) {
        Party party = Parties.getParty(player.getUniqueId());
        if (party == null) {
            return true;
        }

        PartyMember leader = party.getLeader();
        if (leader != null && !leader.getUniqueId().equals(player.getUniqueId())) {
            DuelsMessages.PARTY_LEADER_REQUIRED.send(player);
            return false;
        }

        return true;
    }
}
