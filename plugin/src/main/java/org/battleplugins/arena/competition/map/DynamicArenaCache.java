package org.battleplugins.arena.competition.map;

import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.BattleArenaConfig;
import org.battleplugins.arena.competition.map.options.Bounds;
import org.battleplugins.arena.util.BlockUtil;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Caches dynamic arena copies so they can be recycled between matches.
 */
public final class DynamicArenaCache {

    private final BattleArena plugin;
    private final Map<String, Deque<LiveCompetitionMap>> cached = new ConcurrentHashMap<>();
    private final Map<LiveCompetitionMap, CacheEntry> metadata = new ConcurrentHashMap<>();
    private volatile boolean suspended;

    private record CacheEntry(String key, LiveCompetitionMap template) {}

    public DynamicArenaCache(BattleArena plugin) {
        this.plugin = plugin;
    }

    /**
     * Attempts to borrow a cached map copy for the supplied template.
     *
     * @param template the template map definition
     * @return a cached copy, or null if none are available
     */
    @Nullable
    public LiveCompetitionMap borrow(LiveCompetitionMap template) {
        if (!this.isEnabled()) {
            return null;
        }

        Deque<LiveCompetitionMap> queue = this.cached.get(this.cacheKey(template));
        if (queue == null) {
            return null;
        }

        LiveCompetitionMap map = queue.pollFirst();

        return map;
    }

    /**
     * Tracks the supplied copy so it can be recycled later.
     *
     * @param template the template definition
     * @param copy the created copy
     */
    public void track(LiveCompetitionMap template, LiveCompetitionMap copy) {
        if (!this.isEnabled() || template == null || copy == null) {
            return;
        }

        this.metadata.put(copy, new CacheEntry(this.cacheKey(template), template));
    }

    /**
     * Recycles the supplied map or falls back to the legacy teardown logic.
     *
     * @param map the completed map
     * @return true if the map will be recycled asynchronously
     */
    public boolean recycle(LiveCompetitionMap map) {
        if (!this.isEnabled()) {
            this.metadata.remove(map);
            return false;
        }

        CacheEntry entry = this.metadata.get(map);
        if (entry == null) {
            return false;
        }

        this.enqueue(entry.key(), map);

        if (entry.template().isCacheResetEnabled()) {
            BattleArena.getInstance().getLogger().info("Cache resetting");
            this.resetAsync(map, entry);
        }
        return true;
    }

    /**
     * Clears every tracked map and releases their slots.
     */
    public void shutdown() {
        this.suspend();
        this.flush();
    }

    public void suspend() {
        this.suspended = true;
    }

    public void resume() {
        this.suspended = false;
    }

    public void flush() {
        for (LiveCompetitionMap map : Map.copyOf(this.metadata).keySet()) {
            this.release(map);
        }
        this.cached.clear();
    }

    private void resetAsync(LiveCompetitionMap map, CacheEntry entry) {
        Bounds destination = map.getBounds();
        Bounds source = entry.template().getBounds();
        if (destination == null || source == null) {
            this.failReset(map);
            return;
        }

        if (map.getWorld() == null) {
            this.failReset(map);
            return;
        }

        this.repopulate(map, entry, source, destination);
    }

    private void repopulate(LiveCompetitionMap map, CacheEntry entry, Bounds source, Bounds destination) {
        BattleArenaConfig config = this.plugin.getMainConfig();
        LiveCompetitionMap template = entry.template();

        Runnable markReady = () -> {}; // no-op, we no longer gate reuse

        boolean started = false;
        if (config != null && config.isSchematicUsage()) {
            started = BlockUtil.pasteSchematic(
                    template.getName(),
                    template.getArena().getName(),
                    map.getWorld(),
                    destination,
                    markReady
            );
        }

        if (!started) {
            World sourceWorld = template.getWorld();
            if (sourceWorld == null) {
                this.failReset(map);
                return;
            }

            boolean copied = BlockUtil.copyToWorld(
                    sourceWorld,
                    map.getWorld(),
                    source,
                    destination,
                    markReady
            );

            if (!copied) {
                this.failReset(map);
            }
        }
    }

    private void failReset(LiveCompetitionMap map) {
        this.plugin.warn("Failed to reset cached arena {} - leaving current state in place.", map.getName());
    }

    private void release(LiveCompetitionMap map) {
        CacheEntry entry = this.metadata.remove(map);
        if (entry != null) {
            Deque<LiveCompetitionMap> queue = this.cached.get(entry.key());
            if (queue != null) {
                queue.remove(map);
            }
        }

        Bounds bounds = map.getBounds();
        if (bounds != null && map.getWorld() != null) {
            BlockUtil.unticketRegion(map.getWorld(), bounds, this.plugin);
        }

        BattleArena.getInstanceAllocator().release(map.getSlot());
        BattleArena.getMapPool().release(map.getSlot());
    }

    private boolean isEnabled() {
        BattleArenaConfig config = this.plugin.getMainConfig();
        return !this.suspended && config != null && config.isCacheDynamicArenas();
    }

    private void enqueue(String key, LiveCompetitionMap map) {
        this.cached.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>()).addLast(map);
    }

    private String cacheKey(LiveCompetitionMap template) {
        return template.getArena().getName().toLowerCase(Locale.ROOT) + "::" +
                template.getName().toLowerCase(Locale.ROOT);
    }
}
