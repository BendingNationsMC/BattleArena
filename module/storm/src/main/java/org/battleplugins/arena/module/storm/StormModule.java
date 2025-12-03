package org.battleplugins.arena.module.storm;

import org.battleplugins.arena.Arena;
import org.battleplugins.arena.ArenaPlayer;
import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.config.ArenaConfigParser;
import org.battleplugins.arena.config.ParseException;
import org.battleplugins.arena.event.arena.ArenaInitializeEvent;
import org.battleplugins.arena.module.ArenaModule;
import org.battleplugins.arena.module.ArenaModuleInitializer;
import org.battleplugins.arena.module.storm.config.StormSettings;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleExitEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry point for the Storm module.
 */
@ArenaModule(id = StormModule.ID, name = "Storm", description = "Adds a configurable shrinking storm border to arenas.", authors = "BattlePlugins")
public final class StormModule implements ArenaModuleInitializer, Listener {

    public static final String ID = "storm";

    private final Map<String, StormArenaHandler> handlers = new ConcurrentHashMap<>();


    @EventHandler
    public void onArenaInitialize(ArenaInitializeEvent event) {
        Arena arena = event.getArena();
        if (!arena.isModuleEnabled(ID)) {
            return;
        }

        ConfigurationSection stormSection = arena.getConfig().get("storm");
        StormSettings settings;
        if (stormSection != null) {
            try {
                settings = ArenaConfigParser.newInstance(StormSettings.class, stormSection, arena, null);
            } catch (ParseException ex) {
                ParseException.handle(ex.context("Arena", arena.getName()));
                BattleArena.getInstance().warn("Failed to parse storm configuration for arena {}.", arena.getName());
                return;
            }
        } else {
            settings = new StormSettings();
        }

        if (!settings.isEnabled() || !settings.hasWaves()) {
            return;
        }

        this.registerHandler(arena, settings);
    }

    @EventHandler
    public void onVehicleMount(VehicleExitEvent event) {
        if (!(event.getExited() instanceof Player player)) {
            return;
        }

        ArenaPlayer arenaPlayer = ArenaPlayer.getArenaPlayer(player);
        if (arenaPlayer == null) return;

        event.setCancelled(true);
    }

    private void registerHandler(Arena arena, StormSettings settings) {
        String key = arena.getName().toLowerCase();
        StormArenaHandler existing = this.handlers.remove(key);
        if (existing != null) {
            arena.getEventManager().unregisterEvents(existing);
            existing.shutdown();
        }

        StormArenaHandler handler = new StormArenaHandler(this, arena, settings);
        this.handlers.put(key, handler);
        arena.getEventManager().registerEvents(handler);
    }
}
