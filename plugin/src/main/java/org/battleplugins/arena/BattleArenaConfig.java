package org.battleplugins.arena;

import org.battleplugins.arena.competition.event.EventOptions;
import org.battleplugins.arena.config.ArenaOption;
import org.battleplugins.arena.config.Updater;
import org.battleplugins.arena.config.updater.ConfigUpdater;
import org.battleplugins.arena.config.updater.UpdaterStep;

import java.util.List;
import java.util.Map;

/**
 * Represents the BattleArena configuration.
 */
@Updater(BattleArenaConfig.Updater.class)
public class BattleArenaConfig {

    @ArenaOption(name = "config-version", description = "The version of the config.", required = true)
    private String configVersion;

    @ArenaOption(name = "backup-inventories", description = "Whether player inventories should be backed up when joining competitions.", required = true)
    private boolean backupInventories;

    @ArenaOption(name = "max-backups", description = "The maximum number of backups to save for each player.", required = true)
    private int maxBackups;

    @ArenaOption(name = "max-dynamic-maps", description = "The maximum number of dynamic maps an Arena can have allocated at once.", required = true)
    private int maxDynamicMaps;

    @ArenaOption(name = "randomized-arena-join", description = "Whether players should be randomly placed in an Arena when joining without specifying a map.", required = true)
    private boolean randomizedArenaJoin;

    @ArenaOption(name = "use-schematic", description = "Whether creating a dynamic arena should try to use a schematic if one is available first.", required = true)
    private boolean schematicUsage;

    @ArenaOption(name = "disabled-modules", description = "Modules that are disabled by default.")
    private List<String> disabledModules;

    @ArenaOption(name = "events", description = "The configured events.", required = true)
    private Map<String, List<EventOptions>> events;

    @ArenaOption(name = "debug-mode", description = "Whether debug mode is enabled.")
    private boolean debugMode;

    @ArenaOption(name = "proxy-support", description = "Turns on proxy support.")
    private boolean proxySupport;

    @ArenaOption(name = "proxy-host", description = "Shares the map and arenas across the server.")
    private boolean proxyHost;

    @ArenaOption(name = "proxy-host-server", description = "Name of the proxy server that hosts proxy arenas (Bungee/Velocity backend).")
    private String proxyHostServer;

    @ArenaOption(name = "proxy-server-name", description = "This backend's proxy server name for returning players to their origin server.")
    private String proxyServerName;

    @ArenaOption(name = "redis-host", description = "Redis host for proxy messaging")
    private String redisHost = "127.0.0.1";

    @ArenaOption(name = "redis-port", description = "Redis port for proxy messaging")
    private int redisPort = 6379;

    @ArenaOption(name = "redis-password", description = "Redis password (blank for none)")
    private String redisPassword = "";

    @ArenaOption(name = "redis-database", description = "Redis database index for proxy messaging")
    private int redisDatabase = 0;

    @ArenaOption(name = "redis-channel", description = "Redis pub/sub channel for proxy messaging")
    private String redisChannel = "battlearena:proxy";

    public String getConfigVersion() {
        return this.configVersion;
    }

    public boolean isBackupInventories() {
        return this.backupInventories;
    }

    public int getMaxBackups() {
        return this.maxBackups;
    }

    public int getMaxDynamicMaps() {
        return this.maxDynamicMaps;
    }

    public boolean isRandomizedArenaJoin() {
        return this.randomizedArenaJoin;
    }

    public List<String> getDisabledModules() {
        return this.disabledModules == null ? List.of() : List.copyOf(this.disabledModules);
    }

    public Map<String, List<EventOptions>> getEvents() {
        return Map.copyOf(this.events);
    }

    public boolean isDebugMode() {
        return this.debugMode;
    }

    public boolean isSchematicUsage() {
        return this.schematicUsage;
    }

    public boolean isProxyHost() {
        return proxyHost;
    }

    public boolean isProxySupport() {
        return proxySupport;
    }

    public String getProxyHostServer() {
        return proxyHostServer;
    }

    public String getProxyServerName() {
        return proxyServerName;
    }

    public String getRedisHost() {
        return redisHost;
    }

    public int getRedisPort() {
        return redisPort;
    }

    public String getRedisPassword() {
        return redisPassword;
    }

    public int getRedisDatabase() {
        return redisDatabase;
    }

    public String getRedisChannel() {
        return redisChannel;
    }

    public static class Updater implements ConfigUpdater<BattleArenaConfig> {

        @Override
        public Map<String, UpdaterStep<BattleArenaConfig>> buildUpdaters() {
            return Map.of(
                    "3.1", (config, instance) -> {
                        config.set("randomized-arena-join", false);
                        config.setComments("randomized-arena-join", List.of(
                                "Whether joining an arena using /<arena> join without specifying a map should",
                                "randomly pick an arena, rather than joining the most convenient one. Competitions",
                                "with players waiting will always be prioritized though, even with this setting",
                                "enabled."
                        ));
                    });
        }
    }
}
