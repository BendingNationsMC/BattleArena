package org.battleplugins.arena.module.storm.wave;

import io.lumine.mythic.api.mobs.MythicMob;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import org.battleplugins.arena.Arena;
import org.battleplugins.arena.ArenaPlayer;
import org.battleplugins.arena.competition.LiveCompetition;
import org.battleplugins.arena.competition.map.options.Bounds;
import org.battleplugins.arena.messages.Message;
import org.battleplugins.arena.module.storm.StormMessages;
import org.battleplugins.arena.module.storm.config.StormSettings;
import org.battleplugins.arena.module.storm.config.StormWave;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.*;

/**
 * Handles the actual storm shrinking process for a single competition.
 */
public final class StormController {

    private final Arena arena;
    private final LiveCompetition<?> competition;
    private final StormSettings settings;

    private final Deque<WavePhase> waveQueue = new ArrayDeque<>();
    private Location center;
    private double currentRadius;
    private double waveStartRadius;

    private BukkitTask tickTask;
    private WavePhase currentWave;
    private WaveStage waveStage;
    private long stageTickElapsed;
    private long stageTotalTicks;
    private final Map<UUID, WorldBorder> activeBorders = new HashMap<>();
    private int waveIndex;
    private boolean spawnedBoss;
    private ActiveMob mob;

    public StormController(Arena arena, LiveCompetition<?> competition, StormSettings settings) {
        this.arena = arena;
        this.competition = competition;
        this.settings = settings;
    }

    public boolean initialize() {
        if (!this.settings.hasWaves()) {
            return false;
        }

        Location waitroom = this.competition.getMap().getSpawns() == null ? null : this.competition.getMap().getSpawns().getWaitroomSpawn() == null ? null : this.competition.getMap().getSpawns().getWaitroomSpawn().toLocation(this.competition.getMap().getWorld());
        if (waitroom == null) {
            return false;
        }

        this.center = waitroom;
        this.currentRadius = this.calculateInitialRadius(competition.getMap().getBounds());
        this.prepareWaves(this.settings.getWaves());
        return true;
    }

    public void start() {
        if (this.tickTask != null) {
            return;
        }

        if (!this.beginNextWave()) {
            return;
        }

        this.broadcast(StormMessages.STORM_STARTED);
        this.tickTask = Bukkit.getScheduler().runTaskTimer(this.arena.getPlugin(), this::tick, 1L, 1L);
    }

    public void stop() {
        this.shutdown(true);
    }

    private void shutdown(boolean clearBorders) {
        if (this.tickTask != null) {
            this.tickTask.cancel();
            this.tickTask = null;
        }
        this.currentWave = null;
        this.waveStage = null;
        this.stageTickElapsed = 0L;

        this.stageTotalTicks = 0L;
        this.waveQueue.clear();
        mob.despawn();
        if (clearBorders) {
            this.clearAllBorders();
        }
    }

    private void tick() {
        if (!spawnedBoss && this.waveStage == WaveStage.FINAL && settings.isSpawnBoss()) {
            // Use try catch
            MythicMob mob = MythicBukkit.inst().getMobManager().getMythicMob("LRD_KRAMPUS").orElse(null);
            if (mob != null) {
                this.mob = mob.spawn(BukkitAdapter.adapt(center),1);

                int partySize = this.competition.getAlivePlayerCount();
                double scaledHealth = 100 + 25 * partySize;
                scaledHealth = Math.min(scaledHealth, 200);
                this.mob.getEntity().setMaxHealth(scaledHealth);
                this.mob.getEntity().setHealth(scaledHealth);
            }

            for (ArenaPlayer player : this.competition.getPlayers()) {
                player.getPlayer().setPlayerTime(18000, false);
            }
            spawnedBoss = true;
        }

        // If we don't have any stage (storm never started or has been fully shut down), do nothing
        if (this.waveStage == null) {
            return;
        }

        // In FINAL stage, we skip stage transitions and radius updates, but still keep borders & damage
        if (this.waveStage != WaveStage.FINAL && this.stageTickElapsed >= this.stageTotalTicks) {
            if (!this.advanceStage()) {
                // advanceStage() returning false just means "no more waves",
                // but we still want to keep ticking for damage in FINAL stage.
                // So just return for this tick; future ticks will run with FINAL stage.
                return;
            }
        }

        if (this.waveStage == WaveStage.PAUSING) {
            this.currentRadius = this.waveStartRadius;
        } else if (this.waveStage == WaveStage.RUSHING) {
            double targetRadius = Math.max(1D, this.currentWave.targetRadius());
            double divisor = Math.max(1D, this.stageTotalTicks);
            // FIX: ensure floating-point division for smooth interpolation
            double progress = Math.min(1D, (double) this.stageTickElapsed / divisor);
            this.currentRadius = this.waveStartRadius + (targetRadius - this.waveStartRadius) * progress;
        }
        // In FINAL stage, currentRadius stays as last target radius

        this.stageTickElapsed++;
        this.updatePlayerBorders();
        applyStormDamage();
    }

    private boolean advanceStage() {
        if (this.waveStage == WaveStage.PAUSING) {
            return this.beginRushStage();
        }

        // We just completed a RUSHING stage
        this.currentRadius = Math.max(1D, this.currentWave.targetRadius());
        if (!this.beginNextWave()) {
            // No more waves: lock in final radius but KEEP ticking for damage
            this.broadcast(StormMessages.STORM_COMPLETE);
            this.holdFinalRadius();
            this.updatePlayerBorders();
            return false;
        }
        return true;
    }

    /**
     * After the last wave completes, we hold the final radius and keep applying damage.
     */
    private void holdFinalRadius() {
        this.waveStage = WaveStage.FINAL;
        this.stageTickElapsed = 0L;
        this.stageTotalTicks = Long.MAX_VALUE;
        // Do NOT call shutdown() here; that would cancel tickTask and stop damage.
    }

    private boolean beginRushStage() {
        this.waveStage = WaveStage.RUSHING;
        this.stageTickElapsed = 0L;
        Duration rushDuration = this.currentWave.wave().getRushDuration();
        this.stageTotalTicks = Math.max(1L, (long) Math.ceil(rushDuration.toMillis() / 50D));
        this.waveStartRadius = this.currentRadius;
        return true;
    }

    private boolean beginNextWave() {
        this.currentWave = this.waveQueue.poll();
        if (this.currentWave == null) {
            return false;
        }

        Duration duration = this.currentWave.wave().getDuration();
        this.stageTotalTicks = (long) Math.ceil(duration.toMillis() / 50D);
        this.stageTickElapsed = 0L;
        this.waveStage = WaveStage.PAUSING;
        this.currentRadius = Math.max(1D, this.currentRadius);
        this.waveStartRadius = this.currentRadius;
        this.waveIndex++;
        double safeRadius = Math.max(1D, this.currentWave.targetRadius());
        this.broadcast(StormMessages.STORM_WAVE_STARTED, this.waveIndex, (int) Math.round(safeRadius));
        return true;
    }

    private double calculateInitialRadius(Bounds bounds) {
        double fallback = Math.max(1D, this.settings.getStartRadius());
        World world = this.center.getWorld();
        if (bounds == null || world == null) {
            return fallback;
        }

        double furthestCorner = 0D;
        int[] xs = {bounds.getMinX(), bounds.getMaxX()};
        int[] zs = {bounds.getMinZ(), bounds.getMaxZ()};
        for (int x : xs) {
            for (int z : zs) {
                Location corner = new Location(world, x, this.center.getY(), z);
                furthestCorner = Math.max(furthestCorner, this.center.distance(corner));
            }
        }

        return Math.max(furthestCorner, fallback);
    }

    private void prepareWaves(List<StormWave> waves) {
        this.waveQueue.clear();
        if (waves.isEmpty()) {
            return;
        }

        double minRadius = this.settings.getMinRadius();
        double current = this.currentRadius;
        double totalDelta = Math.max(0D, current - minRadius);
        double step = waves.isEmpty() ? 0D : totalDelta / waves.size();

        double nextRadius;
        for (int i = 0; i < waves.size(); i++) {
            nextRadius = Math.max(minRadius, current - step * (i + 1));
            if (i == waves.size() - 1) {
                nextRadius = minRadius;
            }
            this.waveQueue.add(new WavePhase(waves.get(i), nextRadius));
        }
    }

    private enum WaveStage {
        PAUSING,
        RUSHING,
        FINAL // storm has fully converged; keep damaging at final radius
    }

    private record WavePhase(StormWave wave, double targetRadius) {
    }

    private void updatePlayerBorders() {
        World world = this.center.getWorld();
        if (world == null) {
            this.clearAllBorders();
            return;
        }

        double diameter = Math.max(1D, this.currentRadius * 2D);
        Set<UUID> processed = new HashSet<>();

        Set<ArenaPlayer> trackedPlayers = new HashSet<>();
        trackedPlayers.addAll(this.competition.getPlayers());
        trackedPlayers.addAll(this.competition.getSpectators());

        for (ArenaPlayer arenaPlayer : trackedPlayers) {
            Player player = arenaPlayer.getPlayer();
            if (!player.isOnline()) {
                this.clearBorder(player.getUniqueId());
                continue;
            }

            UUID uuid = player.getUniqueId();
            if (player.getWorld() != world) {
                this.clearBorder(uuid);
                continue;
            }

            processed.add(uuid);
            WorldBorder border = this.activeBorders.computeIfAbsent(uuid, id -> {
                WorldBorder wb = Bukkit.createWorldBorder();
                wb.setWarningDistance(0);
                wb.setWarningTime(0);
                wb.setDamageAmount(1);
                return wb;
            });

            border.setCenter(this.center.getX(), this.center.getZ());
            border.setSize(diameter);
            player.setWorldBorder(border);
        }

        if (this.activeBorders.isEmpty()) {
            return;
        }

        Set<UUID> toRemove = new HashSet<>(this.activeBorders.keySet());
        toRemove.removeAll(processed);
        toRemove.forEach(this::clearBorder);
    }

    private void broadcast(Message message, Object... context) {
        String[] args = Arrays.stream(context).map(String::valueOf).toArray(String[]::new);
        for (ArenaPlayer arenaPlayer : this.competition.getPlayers()) {
            Player player = arenaPlayer.getPlayer();
            if (player.isOnline()) {
                message.send(player, args);
            }
        }
    }

    private void clearBorder(UUID uuid) {
        WorldBorder border = this.activeBorders.remove(uuid);
        if (border == null) {
            return;
        }

        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            player.setWorldBorder(null);
        }
    }

    private void clearAllBorders() {
        Set<UUID> uuids = new HashSet<>(this.activeBorders.keySet());
        uuids.forEach(this::clearBorder);
        this.activeBorders.clear();
    }

    private static final double BLOCK_LENIENCY = 0.5;

    private void applyStormDamage() {
        World world = this.center.getWorld();
        if (world == null) return;

        // We set WORLD BORDER size to (currentRadius * 2) → square from centerX ± currentRadius, centerZ ± currentRadius.
        // To match that EXACTLY, use a square check, not a circular distance.
        double maxDelta = this.currentRadius + BLOCK_LENIENCY;

        Set<ArenaPlayer> trackedPlayers = new HashSet<>();
        trackedPlayers.addAll(this.competition.getPlayers());
        trackedPlayers.addAll(this.competition.getSpectators());

        for (ArenaPlayer arenaPlayer : trackedPlayers) {
            Player player = arenaPlayer.getPlayer();
            if (!player.isOnline() || player.getWorld() != world) {
                continue;
            }

            double dx = player.getLocation().getX() - this.center.getX();
            double dz = player.getLocation().getZ() - this.center.getZ();
            double dy = player.getLocation().getY() - this.center.getY();

            // Outside the same square that the world border uses?
            if (Math.abs(dx) > maxDelta || Math.abs(dz) > maxDelta || Math.abs(dy) > maxDelta) {
                //noinspection UnstableApiUsage
                player.damage(1.0, DamageSource.builder(DamageType.OUTSIDE_BORDER).build());
            }
        }
    }
}
