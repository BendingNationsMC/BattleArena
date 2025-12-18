package org.battleplugins.arena.module.teamheads;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.battleplugins.arena.ArenaPlayer;
import org.battleplugins.arena.event.action.EventAction;
import org.battleplugins.arena.resolver.Resolvable;
import org.battleplugins.arena.team.ArenaTeam;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;

import java.util.Map;

public class TeamChestplateAction extends EventAction {

    public TeamChestplateAction(Map<String, String> params) {
        super(params);
    }

    @Override
    public void call(ArenaPlayer arenaPlayer, Resolvable resolvable) {
        if (!arenaPlayer.getArena().isModuleEnabled(TeamHeads.ID)) {
            return;
        }

        ArenaTeam team = arenaPlayer.getTeam();
        if (team == null || arenaPlayer.getArena().getTeams().isNonTeamGame()) {
            return; // Not a team game
        }

        ItemStack item = new ItemStack(Material.LEATHER_CHESTPLATE);

        item.editMeta(meta -> {
            if (!(meta instanceof LeatherArmorMeta leatherMeta)) {
                return;
            }

            // Set color
            leatherMeta.setColor(org.bukkit.Color.fromRGB(team.getColor().getRGB()));

            // Set name
            leatherMeta.displayName(
                    Component.text(team.getName() + " Team", TextColor.color(team.getColor().getRGB()))
                            .decoration(TextDecoration.ITALIC, false)
            );
        });

        arenaPlayer.getPlayer().getInventory().setChestplate(item);
    }
}