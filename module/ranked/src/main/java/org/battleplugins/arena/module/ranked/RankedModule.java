package org.battleplugins.arena.module.ranked;

import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.config.ArenaConfigParser;
import org.battleplugins.arena.config.ParseException;
import org.battleplugins.arena.event.BattleArenaPostInitializeEvent;
import org.battleplugins.arena.event.BattleArenaReloadedEvent;
import org.battleplugins.arena.event.BattleArenaShutdownEvent;
import org.battleplugins.arena.module.ArenaModule;
import org.battleplugins.arena.module.ArenaModuleContainer;
import org.battleplugins.arena.module.ArenaModuleInitializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Proxy-only ranked module that stores player ELO in Redis.
 * <p>
 * The module will disable itself automatically unless proxy support
 * and Redis are enabled in the BattleArena main configuration.
 */
@ArenaModule(id = RankedModule.ID, name = "Ranked", description = "Proxy-ranked/ELO support backed by Redis.", authors = "BattlePlugins")
public class RankedModule implements ArenaModuleInitializer {
    public static final String ID = "ranked";

    private RankedConfig config;
    private RankedRedisClient redisClient;
    private RankedService rankedService;
    private RankedPlaceholderExpansion placeholders;
    private RankedMatchListener matchListener;

    @EventHandler
    public void onPostInitialize(BattleArenaPostInitializeEvent event) {
        this.load(event.getBattleArena(), true);
    }

    @EventHandler
    public void onReloaded(BattleArenaReloadedEvent event) {
        this.unload();
        this.load(event.getBattleArena(), false);
    }

    @EventHandler
    public void onShutdown(BattleArenaShutdownEvent event) {
        this.unload();
    }

    private void load(BattleArena plugin, boolean initial) {
        ArenaModuleContainer<RankedModule> container = plugin.<RankedModule>module(ID).orElse(null);
        if (container == null) {
            return;
        }

        if (!plugin.getMainConfig().isProxySupport() || plugin.getConnector() == null) {
            container.disable("Ranked module requires proxy and Redis support enabled on the proxy host.");
            return;
        }

        Path dataFolder = plugin.getDataFolder().toPath();
        Path rankedPath = dataFolder.resolve("ranked.yml");
        if (Files.notExists(rankedPath)) {
            try (InputStream inputStream = container.getResource("ranked.yml")) {
                Files.copy(inputStream, rankedPath);
            } catch (Exception e) {
                plugin.error("Failed to copy ranked.yml to data folder!", e);
                if (initial) {
                    container.disable("Failed to copy ranked.yml to data folder!");
                }
                return;
            }
        }

        Configuration rankedConfig = YamlConfiguration.loadConfiguration(rankedPath.toFile());
        try {
            this.config = ArenaConfigParser.newInstance(rankedPath, RankedConfig.class, rankedConfig);
        } catch (ParseException e) {
            ParseException.handle(e);
            if (initial) {
                container.disable("Failed to parse ranked.yml!");
            }
            return;
        }

        try {
            this.redisClient = new RankedRedisClient(plugin, this.config);
            this.rankedService = new RankedService(this.redisClient, this.config);
            plugin.setRankedApi(new RankedApiAdapter(this.rankedService));
        } catch (Exception e) {
            plugin.error("Failed to start ranked Redis client.", e);
            container.disable("Failed to start ranked Redis client. Check your Redis configuration.");
            return;
        }

        this.registerPlaceholders();
        this.registerListeners(plugin);
    }

    private void unload() {
        if (this.placeholders != null) {
            try {
                this.placeholders.unregister();
            } catch (Exception ignored) {
            }
            this.placeholders = null;
        }

        if (this.matchListener != null) {
            this.matchListener.unregister();
            this.matchListener = null;
        }

        if (this.redisClient != null) {
            this.redisClient.close();
            this.redisClient = null;
        }

        this.rankedService = null;
        this.config = null;
        BattleArena.getInstance().setRankedApi(null);
    }

    private void registerPlaceholders() {
        if (this.rankedService == null) {
            return;
        }

        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return;
        }

        this.placeholders = new RankedPlaceholderExpansion(this.rankedService);
        this.placeholders.register();
    }

    private void registerListeners(BattleArena plugin) {
        if (this.rankedService == null) {
            return;
        }

        this.matchListener = new RankedMatchListener(plugin, this.rankedService);
        org.bukkit.Bukkit.getPluginManager().registerEvents(this.matchListener, plugin);
    }

    public RankedService getRankedService() {
        return rankedService;
    }
}
