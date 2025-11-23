package org.battleplugins.arena;

import org.battleplugins.arena.command.BACommandExecutor;
import org.battleplugins.arena.command.BaseCommandExecutor;
import org.battleplugins.arena.competition.*;
import org.battleplugins.arena.competition.event.EventOptions;
import org.battleplugins.arena.competition.event.EventScheduler;
import org.battleplugins.arena.competition.event.EventType;
import org.battleplugins.arena.competition.map.LiveCompetitionMap;
import org.battleplugins.arena.competition.map.MapType;
import org.battleplugins.arena.config.ArenaConfigParser;
import org.battleplugins.arena.config.ParseException;
import org.battleplugins.arena.event.BattleArenaPreInitializeEvent;
import org.battleplugins.arena.event.BattleArenaReloadEvent;
import org.battleplugins.arena.event.BattleArenaReloadedEvent;
import org.battleplugins.arena.event.BattleArenaShutdownEvent;
import org.battleplugins.arena.messages.MessageLoader;
import org.battleplugins.arena.module.ArenaModuleContainer;
import org.battleplugins.arena.module.ArenaModuleLoader;
import org.battleplugins.arena.module.ModuleLoadException;
import org.battleplugins.arena.proxy.Connector;
import org.battleplugins.arena.team.ArenaTeams;
import org.battleplugins.arena.util.*;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The main class for BattleArena.
 */
public class BattleArena extends JavaPlugin implements LoggerHolder, BattleArenaApi {
    private static final int PLUGIN_ID = 4597;

    private static BattleArena instance;

    final Map<String, ArenaType> arenaTypes = new HashMap<>();
    final Map<String, Arena> arenas = new ConcurrentHashMap<>();

    private final Map<Arena, List<LiveCompetitionMap>> arenaMaps = new ConcurrentHashMap<>();
    private final Map<String, ArenaLoader> arenaLoaders = new HashMap<>();

    private final CompetitionManager competitionManager = new CompetitionManager(this);
    private final EventScheduler eventScheduler = new EventScheduler();

    private BattleArenaConfig config;
    private ArenaModuleLoader moduleLoader;
    private ArenaTeams teams;

    private Path arenasPath;

    // Set to true before config is loaded in the event that the config
    // fails to load and the additional debug information is required
    private boolean debugMode = true;

    private static World INSTANCES_WORLD;
    public static final int SLOT_SPACING = 2048; // 128 chunks apart
    private static final SlotPool MAP_POOL = new SlotPool();
    private Connector connector;
    private boolean initialized;
    private final Set<java.util.UUID> pendingProxyJoins = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Optional ranked API provided by the ranked module.
    private org.battleplugins.arena.ranked.RankedApi rankedApi;


    @Override
    public void onLoad() {
        instance = this;

        this.info("Loading BattleArena {} for {}", this.getPluginMeta().getVersion(), Version.getServerVersion());

        this.loadConfig(false);

        Path dataFolder = this.getDataFolder().toPath();
        this.arenasPath = dataFolder.resolve("arenas");
        Path modulesPath = dataFolder.resolve("modules");

        Util.copyDirectories(this.getFile(), modulesPath, "modules", this.config.getDisabledModules().toArray(String[]::new));

        this.moduleLoader = new ArenaModuleLoader(this, this.getClassLoader(), modulesPath);
        try {
            this.moduleLoader.loadModules();
        } catch (IOException e) {
            this.error("An error occurred loading modules!", e);
        }

        new BattleArenaPreInitializeEvent(this).callEvent();
    }

    public static World instancesWorld() {
        return INSTANCES_WORLD;
    }

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(new BattleArenaListener(this), this);
        // Register proxy messaging channel for Bungee/Velocity transfers
        this.getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        // Register default arenas
        this.registerArena(this, "Arena", Arena.class);

        INSTANCES_WORLD = Bukkit.createWorld(
                new WorldCreator("ba-instances")
                        .generator(VoidChunkGenerator.INSTANCE)
                        .environment(World.Environment.NORMAL)
                        .generateStructures(false)
                        .keepSpawnLoaded(net.kyori.adventure.util.TriState.FALSE)
                        .type(org.bukkit.WorldType.NORMAL)
        );
        INSTANCES_WORLD.setAutoSave(false);
        INSTANCES_WORLD.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        INSTANCES_WORLD.setGameRule(GameRule.DISABLE_RAIDS, true);

        // Enable the plugin
        this.enable();

        if (config.isProxyHost() && config.isProxySupport()) {
            // Handle generic arena join requests on the proxy host.
            Bukkit.getPluginManager().registerEvents(new org.battleplugins.arena.proxy.ProxyArenaJoinHandler(this), this);
        }

        // Enable modules
        this.moduleLoader.enableModules();

        // Register base command
        PluginCommand command = this.getCommand("battlearena");
        if (command == null) {
            throw new IllegalArgumentException("Failed to register command 'battlearena'. Was it not registered?");
        }

        command.setExecutor(new BACommandExecutor("battlearena"));

        // Global stats command (leverages ranked module if present)
        org.battleplugins.arena.command.StatsCommandExecutor statsExecutor = new org.battleplugins.arena.command.StatsCommandExecutor(this);
        PluginCommand statsCommand = CommandInjector.inject("stats", "stats");
        TabExecutor statsBridge = statsExecutor.standaloneExecutor();
        statsCommand.setExecutor(statsBridge);
        statsCommand.setTabCompleter(statsBridge);
        CommandInjector.registerPermissions("stats", statsExecutor);

        // Loads all arena loaders
        this.loadArenaLoaders(this.arenasPath);

        new Metrics(this, PLUGIN_ID);

        // Cheeky little message about BattleTracker <3
        if (Bukkit.getPluginManager().getPlugin("BattleTracker") == null) {
            this.warn("----------------------------------------");
            this.warn("BattleTracker not found! Arena statistics will not be saved.");
            this.warn("You can download BattleTracker at: https://modrinth.com/project/battletracker.");
            this.warn("----------------------------------------");
        }
    }

    private void enable() {
        if (config.isProxySupport()) {
            connector = new Connector(this);
            connector.connect();
        }

        this.debugMode = this.config.isDebugMode();

        if (Files.notExists(this.arenasPath)) {
            Util.copyDirectories(this.getFile(), this.arenasPath, "arenas");
        }

        Path dataFolder = this.getDataFolder().toPath();
        if (Files.notExists(dataFolder.resolve("messages.yml"))) {
            this.saveResource("messages.yml", false);
        }

        if (Files.notExists(dataFolder.resolve("teams.yml"))) {
            this.saveResource("teams.yml", false);
        }

        // Load teams
        File teamsFile = new File(this.getDataFolder(), "teams.yml");
        Configuration teamsConfig = YamlConfiguration.loadConfiguration(teamsFile);
        try {
            this.teams = ArenaConfigParser.newInstance(teamsFile.toPath(), ArenaTeams.class, teamsConfig, this);
        } catch (ParseException e) {
            ParseException.handle(e);

            this.error("Failed to load teams configuration! Disabling plugin.");
            this.getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Clear any remaining dynamic maps
        this.clearDynamicMaps();
    }

    @Override
    public void onDisable() {
        new BattleArenaShutdownEvent(this).callEvent();

        this.disable();
    }

    private void disable() {
        // Close all active competitions
        this.competitionManager.completeAllActiveCompetitions();

        // Stop all scheduled events
        this.eventScheduler.stopAllEvents();

        // Clear dynamic maps
        this.clearDynamicMaps();

        for (Arena arena : this.arenas.values()) {
            arena.getEventManager().unregisterAll();
        }

        this.arenas.clear();
        this.arenaMaps.clear();
        this.arenaLoaders.clear();
        this.pendingProxyJoins.clear();

        if (this.connector != null) {
            try {
                this.connector.shutdown();
            } catch (Exception ignored) {
            }
            this.connector = null;
        }

        this.config = null;
        this.teams = null;
        this.initialized = false;

        this.rankedApi = null;
    }

    void postInitialize() {
        // Load messages
        MessageLoader.load(this.getDataFolder().toPath().resolve("messages.yml"));

        // Load all arenas
        this.loadArenas();

        // Load the arena maps
        this.loadArenaMaps(false);

        // Initialize matches
        for (Map.Entry<Arena, List<LiveCompetitionMap>> entry : this.arenaMaps.entrySet()) {
            if (entry.getKey().getType() != CompetitionType.MATCH) {
                continue;
            }

            for (LiveCompetitionMap map : entry.getValue()) {
                if (map.getType() == MapType.STATIC) {
                    Competition<?> competition = map.createCompetition(entry.getKey());
                    this.addCompetition(entry.getKey(), competition);
                }
            }
        }

        // Initialize events
        for (Map.Entry<String, List<EventOptions>> entry : this.config.getEvents().entrySet()) {
            Arena arena = this.getArena(entry.getKey());
            if (arena == null) {
                this.warn("Event {} does not have a valid arena!", entry.getKey());
                continue;
            }

            for (EventOptions options : entry.getValue()) {
                if (options.getType() != EventType.SCHEDULED) {
                    continue; // We are not interested in starting manual events
                }

                this.eventScheduler.scheduleEvent(arena, options, true);
                this.info("Scheduled event for arena {} in {}m.", arena.getName(), options.getInterval().plus(options.getDelay()));
            }
        }

        this.initialized = true;

        // Handle proxy arena synchronization after we are fully initialized.
        if (this.config.isProxySupport() && this.connector != null) {
            if (this.config.isProxyHost()) {
                // Host server shares its maps with other BattleArena instances.
                this.connector.sendSyncConfig();
            } else {
                // Non-host servers request the latest maps from the host via the TCP router.
                this.connector.sendToRouter("{\"type\":\"sync_request\"}");
            }
        }
    }

    /**
     * Reloads the plugin.
     */
    public void reload() {
        new BattleArenaReloadEvent(this).callEvent();

        this.disable();

        // Reload the config
        this.loadConfig(true);

        this.enable();

        // Reload loaders - has to be done this way for third party
        // plugins that add their own arena types
        for (ArenaType type : this.arenaTypes.values()) {
            Path arenasPath = type.plugin().getDataFolder().toPath().resolve("arenas");
            this.loadArenaLoaders(arenasPath);
        }

        this.postInitialize();

        new BattleArenaReloadedEvent(this).callEvent();
    }

    /**
     * Returns whether the given {@link Player} is in an {@link Arena}.
     *
     * @param player the player to check
     * @return whether the player is in an arena
     */
    public boolean isInArena(Player player) {
        return ArenaPlayer.getArenaPlayer(player) != null;
    }

    /**
     * Returns the {@link Arena} from the given name.
     *
     * @param name the name of the arena
     * @return the arena from the given name
     */
    public Optional<Arena> arena(String name) {
        return Optional.ofNullable(this.getArena(name));
    }

    /**
     * Returns the {@link Arena} from the given name.
     *
     * @param name the name of the arena
     * @return the arena from the given name, or null if not found
     */
    @Nullable
    public Arena getArena(String name) {
        return this.arenas.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Returns all the {@link Arena}s for the plugin.
     *
     * @return all the arenas for the plugin
     */
    public List<Arena> getArenas() {
        return List.copyOf(this.arenas.values());
    }

    /**
     * Registers the given {@link Arena}.
     *
     * @param plugin     the plugin registering the arena
     * @param name       the name of the arena
     * @param arenaClass the arena type to register
     */
    public <T extends Arena> void registerArena(Plugin plugin, String name, Class<T> arenaClass) {
        this.registerArena(plugin, name, arenaClass, () -> {
            try {
                return arenaClass.getConstructor().newInstance();
            } catch (InstantiationException | IllegalAccessException | NoSuchMethodException |
                     InvocationTargetException e) {
                throw new RuntimeException("Failed to instantiate arena " + arenaClass.getName(), e);
            }
        });
    }

    /**
     * Registers the given {@link Arena}.
     *
     * @param plugin       the plugin registering the arena
     * @param name         the name of the arena
     * @param arenaClass   the arena type to register
     * @param arenaFactory the factory to create the arena
     */
    public <T extends Arena> void registerArena(Plugin plugin, String name, Class<T> arenaClass, Supplier<T> arenaFactory) {
        ArenaConfigParser.registerFactory(arenaClass, arenaFactory);

        this.arenaTypes.put(name, new ArenaType(plugin, arenaClass));
        if (plugin != this) {
            this.info("Registered arena {} from plugin {}.", name, plugin.getName());

            this.loadArenaLoaders(plugin.getDataFolder().toPath().resolve("arenas"));
        }
    }

    /**
     * Returns all the available maps for the given {@link Arena}.
     *
     * @param arena the arena to get the maps for
     * @return all the available maps for the given arena
     */
    public List<LiveCompetitionMap> getMaps(Arena arena) {
        List<LiveCompetitionMap> maps = this.arenaMaps.get(arena);
        if (maps == null) {
            return List.of();
        }

        return List.copyOf(maps);
    }

    /**
     * Returns the map from the given {@link Arena} and map name.
     *
     * @param arena the arena to get the map from
     * @param name  the name of the map
     * @return the map from the given arena and name
     */
    public Optional<LiveCompetitionMap> map(Arena arena, String name) {
        return Optional.ofNullable(this.getMap(arena, name));
    }

    /**
     * Returns the map from the given {@link Arena} and map name.
     *
     * @param arena the arena to get the map from
     * @param name  the name of the map
     * @return the map from the given arena and name, or null if not found
     */
    @Nullable
    public LiveCompetitionMap getMap(Arena arena, String name) {
        List<LiveCompetitionMap> maps = this.arenaMaps.get(arena);
        if (maps == null) {
            return null;
        }

        return maps.stream()
                .filter(map -> map.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * Adds a new {@link LiveCompetitionMap} to the given {@link Arena}.
     *
     * @param arena the arena to add the map to
     * @param map   the map to add
     */
    public void addArenaMap(Arena arena, LiveCompetitionMap map) {
        this.arenaMaps.computeIfAbsent(arena, k -> new ArrayList<>()).add(map);
    }

    /**
     * Removes the given {@link LiveCompetitionMap} from the given {@link Arena}.
     *
     * @param arena the arena to remove the map from
     * @param map   the map to remove
     */
    public void removeArenaMap(Arena arena, LiveCompetitionMap map) {
        this.arenaMaps.computeIfAbsent(arena, k -> new ArrayList<>()).remove(map);

        // If the map is removed, also remove the competition if applicable
        for (Competition<?> competition : this.competitionManager.getCompetitions(arena)) {
            if (competition.getMap() == map) {
                this.competitionManager.removeCompetition(arena, competition);
            }
        }

        // Now remove the map from the file system
        Path mapPath = arena.getMapPath().resolve(map.getName().toLowerCase(Locale.ROOT) + ".yml");
        try {
            Files.deleteIfExists(mapPath);
        } catch (IOException e) {
            this.error("Failed to delete map file for map {} in arena {}!", map.getName(), arena.getName(), e);
        }
    }

    /**
     * Returns all the {@link Competition}s for the given {@link Arena}.
     *
     * @param arena the arena to get the competitions for
     * @return all the competitions for the given arena
     */
    public List<Competition<?>> getCompetitions(Arena arena) {
        return this.competitionManager.getCompetitions(arena);
    }

    /**
     * Returns all the {@link Competition}s for the given {@link Arena} and
     * specified map name.
     *
     * @param arena the arena to get the competitions for
     * @param name  the name of the competition
     * @return all the competitions for the given arena and name
     */
    public List<Competition<?>> getCompetitions(Arena arena, String name) {
        return this.competitionManager.getCompetitions(arena, name);
    }

    /**
     * Returns a currently active {@link Competition} for the given {@link Arena},
     * {@link Player}, {@link PlayerRole} and map name. If no competition is found,
     * a new one is created if applicable.
     *
     * @param arena  the arena to get the competition for
     * @param player the player to get the competition for
     * @param role   the role of the player
     * @param name   the name of the competition
     * @return the competition result
     */
    public CompletableFuture<CompetitionResult> getOrCreateCompetition(Arena arena, Player player, PlayerRole role, @Nullable String name) {
        return this.competitionManager.getOrCreateCompetition(arena, player, role, name);
    }

    /**
     * Returns a currently active {@link Competition} for the given {@link Arena},
     * {@link Player}s, {@link PlayerRole} and map name. If no competition is found,
     * a new one is created if applicable.
     *
     * @param arena   the arena to get the competition for
     * @param players the players to get the competition for
     * @param role    the role of the player
     * @param name    the name of the competition
     * @return the competition result
     */
    public CompletableFuture<CompetitionResult> getOrCreateCompetition(Arena arena, Collection<Player> players, PlayerRole role, @Nullable String name) {
        return this.competitionManager.getOrCreateCompetition(arena, players, role, name);
    }

    /**
     * Finds a joinable {@link Competition} for the given {@link Player} and {@link PlayerRole}.
     *
     * @param competitions the competitions to find from
     * @param player       the player to find the competition for
     * @param role         the role of the player
     * @return the competition result
     */
    public CompletableFuture<CompetitionResult> findJoinableCompetition(List<Competition<?>> competitions, Player player, PlayerRole role) {
        return this.competitionManager.findJoinableCompetition(competitions, player, role);
    }

    /**
     * Finds a joinable {@link Competition} for the given {@link Player}s and {@link PlayerRole}.
     *
     * @param competitions the competitions to find from
     * @param players      the players to find the competition for
     * @param role         the role of the player
     * @return the competition result
     */
    public CompletableFuture<CompetitionResult> findJoinableCompetition(List<Competition<?>> competitions, Collection<Player> players, PlayerRole role) {
        return this.competitionManager.findJoinableCompetition(competitions, players, role);
    }

    /**
     * Adds a new {@link Competition} to the given {@link Arena}.
     *
     * @param arena       the arena to add the competition to
     * @param competition the competition to add
     */
    public void addCompetition(Arena arena, Competition<?> competition) {
        this.competitionManager.addCompetition(arena, competition);
    }

    /**
     * Removes the given {@link Competition} from the specified {@link Arena}.
     *
     * @param arena       the arena to remove the competition from
     * @param competition the competition to remove
     */
    public void removeCompetition(Arena arena, Competition<?> competition) {
        this.competitionManager.removeCompetition(arena, competition);
    }

    /**
     * Returns the {@link EventScheduler}, which is responsible for scheduling events.
     *
     * @return the event scheduler
     */
    public EventScheduler getEventScheduler() {
        return this.eventScheduler;
    }

    /**
     * Returns an in-memory representation of the configuration.
     *
     * @return the BattleArena configuration
     */
    public BattleArenaConfig getMainConfig() {
        return this.config;
    }

    /**
     * Returns the connector used for proxy communication, if any.
     *
     * @return the connector, or null if proxy support is disabled
     */
    @Nullable
    public Connector getConnector() {
        return this.connector;
    }

    /**
     * Returns whether the plugin has completed its post-initialization phase.
     *
     * @return whether the plugin is fully initialized
     */
    public boolean isInitialized() {
        return this.initialized;
    }

    /**
     * Sends the given player to the configured proxy host server,
     * if proxy support is enabled and a host server name is set.
     * <p>
     * This uses the standard BungeeCord plugin messaging channel,
     * which is also supported in Velocity via the legacy bridge.
     *
     * @param player the player to send
     */
    public void sendPlayerToProxyHost(Player player) {
        if (!this.config.isProxySupport()) {
            return;
        }

        this.sendPlayerToServer(player, this.config.getProxyHostServer());
    }

    /**
     * Sends the given player to the specified proxy server using the
     * standard BungeeCord plugin messaging channel.
     *
     * @param player the player to send
     * @param server the target server name on the proxy
     */
    public void sendPlayerToServer(Player player, String server) {
        if (server == null || server.isEmpty()) {
            return;
        }

        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(baos);
            out.writeUTF("Connect");
            out.writeUTF(server);
            player.sendPluginMessage(this, "BungeeCord", baos.toByteArray());
        } catch (java.io.IOException ignored) {
            // If plugin messaging fails we silently ignore.
        }
    }

    /**
     * Returns the teams for the plugin.
     *
     * @return the teams for the plugin
     */
    public ArenaTeams getTeams() {
        return this.teams;
    }

    /**
     * Returns the {@link ArenaModuleContainer} for the given module id.
     *
     * @param id  the id of the module
     * @param <T> the type of the module
     * @return the module container for the given id
     */
    public <T> Optional<ArenaModuleContainer<T>> module(String id) {
        return Optional.ofNullable(this.getModule(id));
    }

    /**
     * Returns the {@link ArenaModuleContainer} for the given module id.
     *
     * @param id  the id of the module
     * @param <T> the type of the module
     * @return the module container for the given id, or null if not found
     */
    @Nullable
    public <T> ArenaModuleContainer<T> getModule(String id) {
        return this.moduleLoader.getModule(id);
    }

    /**
     * Returns all the modules for the plugin.
     *
     * @return all the modules for the plugin
     */
    public List<ArenaModuleContainer<?>> getModules() {
        return this.moduleLoader.getModules();
    }

    /**
     * Returns all the failed modules for the plugin.
     *
     * @return all the failed modules for the plugin
     */
    public Set<ModuleLoadException> getFailedModules() {
        return this.moduleLoader.getFailedModules();
    }

    /**
     * Registers a new command executor for the given command.
     *
     * @param commandName the name of the command
     * @param executor    the executor to register
     * @param aliases     the aliases for the command
     */
    public void registerExecutor(String commandName, BaseCommandExecutor executor, String... aliases) {
        PluginCommand command = CommandInjector.inject(commandName, commandName.toLowerCase(Locale.ROOT), aliases);
        command.setExecutor(executor);

        CommandInjector.registerPermissions(commandName.toLowerCase(Locale.ROOT), executor);
    }

    /**
     * Returns the path to the maps directory.
     *
     * @return the path to the maps directory
     */
    public Path getMapsPath() {
        return this.getDataFolder().toPath().resolve("maps");
    }

    /**
     * Returns the path to the backup directory for the given type.
     *
     * @param type the type of backup
     * @return the path to the backup directory
     */
    public Path getBackupPath(String type) {
        return this.getDataFolder().toPath().resolve("backups").resolve(type);
    }

    /**
     * Returns whether the plugin is in debug mode.
     *
     * @return whether the plugin is in debug mode
     */
    @Override
    public boolean isDebugMode() {
        return this.debugMode;
    }

    /**
     * Sets whether the plugin is in debug mode.
     *
     * @param debugMode whether the plugin is in debug mode
     */
    @Override
    public void setDebugMode(boolean debugMode) {
        this.debugMode = debugMode;
    }

    /**
     * Gets the SLF4J logger for the plugin.
     *
     * @return the SLF4J logger for the plugin
     */
    @Override
    public @NotNull Logger getSLF4JLogger() {
        return super.getSLF4JLogger();
    }

    private void loadArenas() {
        // Register our arenas once ALL the plugins have loaded. This ensures that
        // all custom plugins adding their own arena types have been loaded.
        for (ArenaLoader value : this.arenaLoaders.values()) {
            try {
                value.load();
            } catch (Exception e) {
                this.error("An error occurred when loading arena {}: {}", value.arenaPath().getFileName(), e.getMessage(), e);
            }
        }
    }

    private void loadArenaLoaders(Path path) {
        if (Files.notExists(path)) {
            return;
        }

        // Create arena loaders
        try (Stream<Path> arenaPaths = Files.walk(path)) {
            arenaPaths.forEach(arenaPath -> {
                try {
                    if (Files.isDirectory(arenaPath)) {
                        return;
                    }

                    Configuration configuration = YamlConfiguration.loadConfiguration(Files.newBufferedReader(arenaPath));
                    String name = configuration.getString("name");
                    if (name == null) {
                        this.warn("Arena {} does not have a name!", arenaPath.getFileName());
                        return;
                    }

                    String mode = configuration.getString("mode", name);
                    List<String> aliases = configuration.getStringList("aliases");
                    ArenaLoader arenaLoader = new ArenaLoader(this, mode, configuration, arenaPath);
                    this.arenaLoaders.put(name, arenaLoader);

                    // Because Bukkit locks its command map upon startup, we need to
                    // add our plugin commands here, but populating the executor
                    // can happen at any time. This also means that Arenas can specify
                    // their own executors if they so please.
                    CommandInjector.inject(name, name.toLowerCase(Locale.ROOT), aliases.toArray(String[]::new));
                } catch (IOException e) {
                    throw new RuntimeException("Error reading arena config", e);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Error walking arenas path!", e);
        }
    }

    public void loadArenaMaps(boolean proxy) {
        // All the arenas have been loaded, now we can load the maps
        Path mapsPath = this.getMapsPath();
        if (Files.notExists(mapsPath)) {
            // No maps to load
            return;
        }

        // Check to see if there are any maps to load
        for (Map.Entry<String, Arena> entry : this.arenas.entrySet()) {
            String arenaName = entry.getKey();
            Arena arena = entry.getValue();

            Path arenaMapPath = mapsPath.resolve(arenaName.toLowerCase(Locale.ROOT));
            if (Files.notExists(arenaMapPath)) {
                continue;
            }

            try (Stream<Path> mapPaths = Files.walk(arenaMapPath)) {
                mapPaths.forEach(mapPath -> {
                    if (Files.isDirectory(mapPath)) {
                        return;
                    }

                    try {
                        Configuration configuration = YamlConfiguration.loadConfiguration(Files.newBufferedReader(mapPath));
                        LiveCompetitionMap map = ArenaConfigParser.newInstance(mapPath, arena.getMapFactory().getMapClass(), configuration, this);
                        if (map.getBounds() == null && map.getType() == MapType.DYNAMIC) {
                            // Cannot create dynamic map without bounds
                            this.warn("Map {} for arena {} is dynamic but does not have bounds!", map.getName(), arena.getName());
                            return;
                        }

                        if (map.isRemote() || !proxy) {
                            this.addArenaMap(arena, map);
                        }
                        this.info("Loaded map {} for arena {}.", map.getName(), arena.getName());
                    } catch (IOException e) {
                        throw new RuntimeException("Error reading competition config", e);
                    } catch (ParseException e) {
                        ParseException.handle(e);
                    }
                });
            } catch (IOException e) {
                throw new RuntimeException("Error loading maps for arena " + arena.getName(), e);
            }
        }
    }

    private void clearDynamicMaps() {
        for (File file : Bukkit.getWorldContainer().listFiles()) {
            if (file.isDirectory() && file.getName().startsWith("ba-dynamic")) {
                try {
                    try (Stream<Path> pathsToDelete = Files.walk(file.toPath())) {
                        for (Path path : pathsToDelete.sorted(Comparator.reverseOrder()).toList()) {
                            Files.deleteIfExists(path);
                        }
                    }
                } catch (IOException e) {
                    this.error("Failed to delete dynamic map {}", file.getName(), e);
                }
            }
        }
    }

    public void loadConfig(boolean reload) {
        this.saveDefaultConfig();

        File configFile = new File(this.getDataFolder(), "config.yml");
        Configuration config = YamlConfiguration.loadConfiguration(configFile);
        try {
            this.config = ArenaConfigParser.newInstance(configFile.toPath(), BattleArenaConfig.class, config);
        } catch (ParseException e) {
            ParseException.handle(e);

            this.error("Failed to load BattleArena configuration!");
            if (!reload) {
                this.getServer().getPluginManager().disablePlugin(this);
            }
        }
    }

    public Map<Arena, List<LiveCompetitionMap>> getArenaMaps() {
        return arenaMaps;
    }

    public static SlotPool getMapPool() {
        return MAP_POOL;
    }

    public boolean isPendingProxyJoin(java.util.UUID id) {
        return this.pendingProxyJoins.contains(id);
    }

    public void addPendingProxyJoin(java.util.UUID id) {
        this.pendingProxyJoins.add(id);
    }

    public void addPendingProxyJoins(Collection<Player> players) {
        for (Player player : players) {
            this.pendingProxyJoins.add(player.getUniqueId());
        }
    }

    public org.battleplugins.arena.ranked.RankedApi getRankedApi() {
        return this.rankedApi;
    }

    public void setRankedApi(org.battleplugins.arena.ranked.RankedApi api) {
        this.rankedApi = api;
    }

    public void removePendingProxyJoin(java.util.UUID id) {
        this.pendingProxyJoins.remove(id);
    }

    /**
     * Returns the instance of the plugin.
     *
     * @return the instance of the plugin
     */
    public static BattleArena getInstance() {
        return instance;
    }
}
