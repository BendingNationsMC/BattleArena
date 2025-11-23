package org.battleplugins.arena.module.party;

import org.battleplugins.arena.event.BattleArenaPostInitializeEvent;
import org.battleplugins.arena.feature.party.Parties;
import org.battleplugins.arena.module.ArenaModule;
import org.battleplugins.arena.module.ArenaModuleInitializer;
import org.battleplugins.arena.module.party.paf.PAFPartiesFeature;
import org.battleplugins.arena.module.party.parties.PartiesPartiesFeature;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/**
 * A module that allows for hooking into various party plugins.
 */
@ArenaModule(id = PartyIntegration.ID, name = "Party", description = "Adds support for hooking into various Party plugins.", authors = "BattlePlugins")
public class PartyIntegration implements ArenaModuleInitializer {
    public static final String ID = "party";

    @EventHandler
    public void onPostInitialize(BattleArenaPostInitializeEvent event) {
        PluginManager pluginManager = Bukkit.getPluginManager();

        Plugin pafPlugin = pluginManager.getPlugin("Spigot-Party-API-PAF");
        if (pafPlugin == null || !pafPlugin.isEnabled()) {
            pafPlugin = pluginManager.getPlugin("PartyAndFriends");
        }
        if (pafPlugin == null || !pafPlugin.isEnabled()) {
            pafPlugin = pluginManager.getPlugin("Party and Friends");
        }

        if (pafPlugin != null && pafPlugin.isEnabled()) {
            Parties.register(new PAFPartiesFeature(pafPlugin));

            event.getBattleArena().info("{} detected. Using Party and Friends integration.", pafPlugin.getName());
        }

        if (pluginManager.isPluginEnabled("Parties")) {
            Parties.register(new PartiesPartiesFeature());

            event.getBattleArena().info("Parties found. Using Parties for party integration.");
        }
    }
}
