package org.battleplugins.arena.module.teamheads;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.battleplugins.arena.ArenaPlayer;
import org.battleplugins.arena.event.action.EventAction;
import org.battleplugins.arena.resolver.Resolvable;
import org.battleplugins.arena.team.ArenaTeam;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
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

            // Mask out alpha channel so Bukkit only receives 0xRRGGBB values
            int rgb = team.getColor().getRGB() & 0xFFFFFF;

            // Set color
            leatherMeta.setColor(org.bukkit.Color.fromRGB(rgb));

            // Set name
            leatherMeta.displayName(
                    Component.text(team.getName() + " Team", TextColor.color(rgb))
                            .decoration(TextDecoration.ITALIC, false)
            );

            leatherMeta.setUnbreakable(true);
            leatherMeta.addEnchant(Enchantment.BINDING_CURSE, 1, true);
            leatherMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        });

        arenaPlayer.getPlayer().getInventory().setChestplate(item);
    }
}
