package org.battleplugins.arena.competition.map;

import org.battleplugins.arena.Arena;
import org.battleplugins.arena.ArenaLike;
import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.BattleArenaConfig;
import org.battleplugins.arena.competition.LiveCompetition;
import org.battleplugins.arena.competition.map.options.Bounds;
import org.battleplugins.arena.competition.map.options.Spawns;
import org.battleplugins.arena.config.ArenaConfigSerializer;
import org.battleplugins.arena.config.ArenaOption;
import org.battleplugins.arena.config.ParseException;
import org.battleplugins.arena.config.PostProcessable;
import org.battleplugins.arena.module.domination.config.DominationMapSettings;
import org.battleplugins.arena.proxy.Elements;
import org.battleplugins.arena.util.BlockUtil;
import org.battleplugins.arena.util.Util;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Represents a map for a competition which is live on this server.
 */
public class LiveCompetitionMap implements ArenaLike, CompetitionMap, PostProcessable {
    private static final MapFactory FACTORY = MapFactory.create(LiveCompetitionMap.class, LiveCompetitionMap::new);

    @ArenaOption(name = "name", description = "The name of the map.", required = true)
    private String name;

    @ArenaOption(name = "arena", description = "The arena this map is for.", required = true)
    private Arena arena;

    @ArenaOption(name = "type", description = "The type of map.", required = true)
    private MapType type;

    @ArenaOption(name = "world", description = "The world the map is located in.", required = true)
    private String world;

    @ArenaOption(name = "bounds", description = "The bounds of the map.")
    private Bounds bounds;

    @ArenaOption(name = "spawns", description = "The spawn locations.")
    private Spawns spawns;

    @ArenaOption(name = "matchups", description = "Elements this map is targeted to.")
    private List<Elements> matchups;

    @ArenaOption(name = "domination", description = "Domination capture areas defined for this map.")
    private DominationMapSettings domination;

    private World mapWorld;
    private World parentWorld;
    private int offset;
    private int slot;

    @ArenaOption(name = "proxy", description = "Makes the map a proxy")
    private boolean remote;

    public LiveCompetitionMap() {
    }

    public LiveCompetitionMap(String name, Arena arena, MapType type, String world, @Nullable Bounds bounds, @Nullable Spawns spawns, @Nullable DominationMapSettings domination) {
        this.name = name;
        this.arena = arena;
        this.type = type;
        this.world = world;
        this.bounds = bounds;
        this.spawns = spawns;
        this.domination = domination;
    }

    /**
     * Convenience constructor allowing the proxy/remote flag to be set
     * at creation time.
     */
    public LiveCompetitionMap(String name, Arena arena, MapType type, String world, @Nullable Bounds bounds, @Nullable Spawns spawns, @Nullable DominationMapSettings domination, boolean remote,
                              List<Elements> matchups) {
        this(name, arena, type, world, bounds, spawns, domination);
        this.remote = remote;
        this.matchups = matchups;
    }

    @Override
    public void postProcess() {
        if (this.mapWorld != null) {
            return; // Map was already set in createDynamicCompetition
        }

        this.mapWorld = Bukkit.getWorld(this.world);
        if (this.mapWorld == null && !remote) {
            throw new IllegalStateException("World " + this.world + " for map " + this.name + " in arena " + this.arena.getName() + " does not exist!");
        }

        // Normalize matchups list so it always contains Elements values even if the
        // configuration was loaded as strings by the parser.
        if (this.matchups != null && !this.matchups.isEmpty()) {
            java.util.List<Elements> normalized = new java.util.ArrayList<>();
            for (Object o : this.matchups) {
                if (o instanceof Elements e) {
                    normalized.add(e);
                } else if (o instanceof String s) {
                    try {
                        normalized.add(Elements.valueOf(s.toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException ignored) {
                        // Ignore unknown or invalid element names
                    }
                }
            }
            this.matchups = normalized;
        }
    }

    /**
     * Creates a new competition for this map.
     *
     * @param arena the arena to create the competition for
     * @return the created competition
     */
    public LiveCompetition<?> createCompetition(Arena arena) {
        return new LiveCompetition<>(arena, arena.getType(), this);
    }

    public Optional<DominationMapSettings> getDominationSettings() {
        return Optional.ofNullable(this.domination);
    }

    public void setDomination(@Nullable DominationMapSettings domination) {
        this.domination = domination;
    }

    public void save() throws ParseException, IOException {
        Path mapsPath = this.arena.getMapPath();
        if (Files.notExists(mapsPath)) {
            Files.createDirectories(mapsPath);
        }

        Path mapPath = mapsPath.resolve(this.getName().toLowerCase(Locale.ROOT) + ".yml");
        if (Files.notExists(mapPath)) {
            Files.createFile(mapPath);
        }

        FileConfiguration configuration = YamlConfiguration.loadConfiguration(Files.newBufferedReader(mapPath));
        ArenaConfigSerializer.serialize(this, configuration);

        configuration.save(mapPath.toFile());
    }

    @Override
    public final String getName() {
        return this.name;
    }

    /**
     * Sets the name of the map.
     *
     * @param name the name of the map
     */
    public final void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the {@link Arena} this map belongs to.
     *
     * @return the arena this map belongs to
     */
    @Override
    public final Arena getArena() {
        return this.arena;
    }

    /**
     * Gets the {@link MapType} of this map.
     *
     * @return the map type of this map
     */
    @Override
    public final MapType getType() {
        return this.type;
    }

    /**
     * Sets the type of the map.
     *
     * @param type the type of the map
     */
    public final void setType(MapType type) {
        this.type = type;
    }

    /**
     * Gets the {@link World} this map is located in.
     *
     * @return the world this map is located in
     */
    public final World getWorld() {
        return this.mapWorld;
    }

    /**
     * Gets the parent world of the map.
     * <p>
     * This is the world that the map is located in, or the parent world
     * if the map is a dynamic map.
     *
     * @return the parent world of the map
     */
    public final World getParentWorld() {
        return this.parentWorld == null ? this.mapWorld : this.parentWorld;
    }

    /**
     * Gets the {@link Bounds} of the map.
     *
     * @return the bounds of the map
     */
    public final Optional<Bounds> bounds() {
        return Optional.ofNullable(this.bounds);
    }

    /**
     * Gets the bounds of the map.
     *
     * @return the bounds of the map, or null if there are no bounds
     */
    @Nullable
    public final Bounds getBounds() {
        return this.bounds;
    }

    /**
     * Sets the bounds of the map.
     *
     * @param bounds the bounds of the map
     */
    public final void setBounds(Bounds bounds) {
        this.bounds = bounds;
    }

    /**
     * Gets the {@link Spawns} of the map.
     *
     * @return the spawn locations of the map
     */
    public final Optional<Spawns> spawns() {
        return Optional.ofNullable(this.spawns);
    }

    /**
     * Gets the spawn locations of the map.
     *
     * @return the spawn locations of the map, or null if there are no spawn locations
     */
    @Nullable
    public final Spawns getSpawns() {
        return this.spawns;
    }

    /**
     * Returns the elements this map is targeted to.
     * <p>
     * This can be used by matchmaking logic to prefer
     * certain maps for players with matching elements.
     *
     * @return an immutable list of targeted elements
     */
    public final List<Elements> getMatchups() {
        return this.matchups == null ? List.of() : List.copyOf(this.matchups);
    }

    /**
     * Sets the elements this map is targeted to.
     *
     * @param matchups list of elements, or null for none
     */
    public final void setMatchups(List<Elements> matchups) {
        this.matchups = matchups == null ? null : new java.util.ArrayList<>(matchups);
    }

    /**
     * Sets the spawn locations of the map.
     *
     * @param spawns the spawn locations of the map
     */
    public final void setSpawns(Spawns spawns) {
        this.spawns = spawns;
    }

    /**
     * Creates a new dynamic competition for this map.
     * <p>
     * This is only supported for maps with a {@link MapType}
     * of type {@link MapType#DYNAMIC}.
     *
     * @param arena the arena to create the competition for
     * @return the created dynamic competition
     */
    @Nullable
    public final LiveCompetition<?> createDynamicCompetition(Arena arena) {
        if (this.type != MapType.DYNAMIC) {
            throw new IllegalStateException("Cannot create dynamic competition for non-dynamic map!");
        }

        int slot = BattleArena.getMapPool().acquire();
        int offsetX = slot * BattleArena.SLOT_SPACING;
        World world = BattleArena.instancesWorld();

        if (world == null) {
            return null;
        }

        world.setGameRule(GameRule.DISABLE_RAIDS, true);
        world.setAutoSave(false);

        BattleArenaConfig config = this.getArena().getPlugin().getMainConfig();
        Bounds shiftedBounds = bounds.shift(offsetX, 0, 0);
        Spawns shiftedSpawns = this.spawns == null ? null : this.spawns.shift(offsetX, 0, 0);
        DominationMapSettings shiftedDomination = this.domination == null ? null : this.domination.shift(offsetX, 0, 0);

        // If schematic usage is disabled in the config OR schematic pasting fails,
        // then attempt to fall back to copying the map directly from the map world.
        // If that also fails, return null to indicate map setup failure.
        if ((!config.isSchematicUsage() || !BlockUtil.pasteSchematic(this.name, this.getArena().getName(), world, shiftedBounds))
                && !BlockUtil.copyToWorld(this.mapWorld, world, bounds, shiftedBounds)) {
            return null;
        }

        LiveCompetitionMap copy = arena.getMapFactory().create(
                this.name, arena, this.type,
                BattleArena.instancesWorld().getName(),
                shiftedBounds,
                shiftedSpawns,
                shiftedDomination,
                this.remote,
                this.matchups
        );

        copy.slot = slot;
        copy.offset = offsetX;
        // Copy additional fields for custom maps
        if (copy.getClass() != LiveCompetitionMap.class) {
            Util.copyFields(this, copy);
        }

        copy.mapWorld = world;
        copy.parentWorld = this.mapWorld;
        copy.postProcess();

        return copy.createCompetition(arena);
    }

    /**
     * Prepares a new dynamic competition for this map asynchronously.
     * <p>
     * The returned future completes once the underlying FAWE operations
     * (schematic paste or world copy) have finished and the competition
     * has been created, or with null if preparation failed.
     *
     * @param arena the arena to create the competition for
     * @return a future completing with the created dynamic competition or null on failure
     */
    @Nullable
    public final java.util.concurrent.CompletableFuture<LiveCompetition<?>> createDynamicCompetitionAsync(Arena arena) {
        java.util.concurrent.CompletableFuture<LiveCompetition<?>> future = new java.util.concurrent.CompletableFuture<>();

        if (this.type != MapType.DYNAMIC) {
            future.completeExceptionally(new IllegalStateException("Cannot create dynamic competition for non-dynamic map!"));
            return future;
        }

        int slot = BattleArena.getMapPool().acquire();
        int offsetX = slot * BattleArena.SLOT_SPACING;
        World world = BattleArena.instancesWorld();

        if (world == null) {
            BattleArena.getMapPool().release(slot);
            future.complete(null);
            return future;
        }

        BattleArenaConfig config = this.getArena().getPlugin().getMainConfig();
        Bounds shiftedBounds = bounds.shift(offsetX, 0, 0);
        Spawns shiftedSpawns = this.spawns == null ? null : this.spawns.shift(offsetX, 0, 0);
        DominationMapSettings shiftedDomination = this.domination == null ? null : this.domination.shift(offsetX, 0, 0);

        Runnable onReady = () -> {
            try {
                LiveCompetitionMap copy = arena.getMapFactory().create(
                        this.name, arena, this.type,
                        BattleArena.instancesWorld().getName(),
                        shiftedBounds,
                        shiftedSpawns,
                        shiftedDomination,
                        this.remote,
                        this.matchups
                );

                copy.slot = slot;
                copy.offset = offsetX;
                // Copy additional fields for custom maps
                if (copy.getClass() != LiveCompetitionMap.class) {
                    Util.copyFields(this, copy);
                }

                copy.mapWorld = world;
                copy.parentWorld = this.mapWorld;
                copy.postProcess();

                LiveCompetition<?> competition = copy.createCompetition(arena);
                arena.getPlugin().addCompetition(arena, competition);

                future.complete(competition);
            } catch (Throwable t) {
                BattleArena.getInstance().error("Failed to prepare dynamic competition for map " + this.name, t);
                BattleArena.getMapPool().release(slot);
                future.complete(null);
            }
        };

        boolean started = false;

        if (!config.isSchematicUsage() || !BlockUtil.pasteSchematic(this.name, this.getArena().getName(), world, shiftedBounds, onReady)) {
            // Either schematic usage is disabled or paste failed, fall back to world copy.
            if (!BlockUtil.copyToWorld(this.mapWorld, world, bounds, shiftedBounds, onReady)) {
                BattleArena.getMapPool().release(slot);
                future.complete(null);
            } else {
                started = true;
            }
        } else {
            started = true;
        }

        if (!started && !future.isDone()) {
            BattleArena.getMapPool().release(slot);
            future.complete(null);
        }

        return future;
    }

    public void setSlot(int slot) {
        this.slot = slot;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }

    /**
     * Gets the default factory for creating {@link LiveCompetitionMap live maps}.
     *
     * @return the factory for creating maps
     */
    public static MapFactory getFactory() {
        return FACTORY;
    }

    public int getSlot() {
        return slot;
    }

    public boolean isRemote() {
        return remote;
    }

    public void setRemote(boolean remote) {
        this.remote = remote;
    }
}
