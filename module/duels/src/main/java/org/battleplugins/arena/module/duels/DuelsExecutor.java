package org.battleplugins.arena.module.duels;

import org.battleplugins.arena.Arena;
import org.battleplugins.arena.ArenaPlayer;
import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.command.ArenaCommand;
import org.battleplugins.arena.command.SubCommandExecutor;
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

        DuelsMessages.DUEL_REQUEST_SENT.send(player, target.getName());
        DuelsMessages.DUEL_REQUEST_RECEIVED.send(
                target,
                player.getName(),
                this.parentCommand,
                player.getName(),
                this.parentCommand,
                player.getName()
        );

        this.module.addDuelRequest(player, target);
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

        Bukkit.getScheduler().runTaskLater(BattleArena.getInstance(), () -> {
            this.module.acceptDuel(this.arena, target, player, request.getRequesterParty(), request.getTargetParty());
        }, 100);
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
