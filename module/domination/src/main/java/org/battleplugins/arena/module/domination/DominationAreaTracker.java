package org.battleplugins.arena.module.domination;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.battleplugins.arena.ArenaPlayer;
import org.battleplugins.arena.competition.LiveCompetition;
import org.battleplugins.arena.competition.PlayerRole;
import org.battleplugins.arena.feature.hologram.Hologram;
import org.battleplugins.arena.feature.hologram.Holograms;
import org.battleplugins.arena.module.domination.config.DominationAreaDefinition;
import org.battleplugins.arena.module.domination.config.RewardType;
import org.battleplugins.arena.team.ArenaTeam;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.*;

/**
 * Runtime tracker for a domination area.
 */
final class DominationAreaTracker {

    private final String id;
    private final DominationAreaDefinition definition;
    private final String displayLabel;
    private final Location center;
    private final double radius;
    private final double radiusSquared;
    private final long requiredTicks;
    private final BossBar captureBossBar;
    private final Set<UUID> bossBarViewers = new HashSet<>();
    private static final Component INSTRUCTION_LINE = Component.text("Hold the zone to capture!", NamedTextColor.AQUA);

    private ArenaTeam owner;
    private ArenaTeam capturingTeam;
    private double captureProgressTicks;
    private boolean locked;
    private long visualTick;
    private Hologram hologram;

    DominationAreaTracker(String id, DominationAreaDefinition definition, Location center) {
        this.id = id;
        this.definition = definition;
        String displayName = definition.getDisplayName();
        this.displayLabel = displayName == null || displayName.isBlank() ? "Domination Zone " + id : displayName;
        this.center = center;

        double radius = Math.max(0.5D, definition.getRadius());
        this.radius = radius;
        this.radiusSquared = radius * radius;

        Duration captureDuration = definition.getCaptureDuration();
        long ticks = captureDuration == null ? 0 : (long) Math.ceil(captureDuration.toMillis() / 50D);
        this.requiredTicks = Math.max(1L, ticks);
        this.captureBossBar = Bukkit.createBossBar("Capturing " + this.displayLabel, BarColor.PURPLE, BarStyle.SOLID);
        this.captureBossBar.setProgress(0D);
    }

    public String getId() {
        return this.id;
    }

    public DominationAreaDefinition getDefinition() {
        return this.definition;
    }

    String getDisplayLabel() {
        return this.displayLabel;
    }

    void spawnHologram(LiveCompetition<?> competition) {
        if (this.hologram != null) {
            return;
        }

        Location hologramLocation = this.center.clone().add(0, 5, 0);
        this.hologram = Holograms.createHologram(
                competition,
                hologramLocation,
                this.buildTitleLine(),
                this.buildPowerupLine(),
                this.buildProgressLine(0D, false, null, false, false),
                INSTRUCTION_LINE
        );
    }

    void removeHologram() {
        if (this.hologram == null) {
            return;
        }

        Holograms.removeHologram(this.hologram);
        this.hologram = null;
        this.captureBossBar.removeAll();
        this.bossBarViewers.clear();
    }

    /**
     * Advances capture progress for this tracker.
     *
     * @param players active players participating in the competition
     */
    public CaptureNotification tick(Collection<ArenaPlayer> players) {
        this.playSwirlParticles();

        ControlState controlState = this.determineControl(players);
        CaptureNotification notification = CaptureNotification.none();

        if (!this.locked) {
            double delta = this.computeProgressDelta(controlState);
            ArenaTeam previousCapturing = this.capturingTeam;

            if (delta != 0D) {
                this.captureProgressTicks = Math.max(0D, Math.min(this.requiredTicks, this.captureProgressTicks + delta));
                if (this.captureProgressTicks <= 0D) {
                    this.captureProgressTicks = 0D;
                }
            }

            boolean started = previousCapturing != this.capturingTeam && this.capturingTeam != null && delta > 0D;
            if (started) {
                notification = CaptureNotification.started(this.capturingTeam);
            }

            if (this.captureProgressTicks >= this.requiredTicks && this.capturingTeam != null) {
                ArenaTeam previousOwner = this.owner;
                ArenaTeam winningTeam = this.capturingTeam;
                this.owner = winningTeam;
                this.capturingTeam = null;
                this.captureProgressTicks = 0D;

                boolean lockedNow = this.definition.lockAfterCapture();
                if (lockedNow) {
                    this.locked = true;
                }

                CaptureResult result = new CaptureResult(winningTeam, previousOwner, lockedNow);
                notification = CaptureNotification.completed(started ? winningTeam : null, result);
            }
        }

        this.refreshVisuals(controlState);
        return notification;
    }

    private ControlState determineControl(Collection<ArenaPlayer> players) {
        if (players.isEmpty()) {
            return ControlState.EMPTY;
        }

        List<ArenaPlayer> viewers = new ArrayList<>();
        Map<ArenaTeam, Integer> strengths = new HashMap<>();
        World centerWorld = this.center.getWorld();

        for (ArenaPlayer player : players) {
            Player bukkitPlayer = player.getPlayer();
            Location location = bukkitPlayer.getLocation();
            if (location.getWorld() == null || centerWorld == null || !location.getWorld().equals(centerWorld)) {
                continue;
            }

            if (location.distanceSquared(this.center) > this.radiusSquared) {
                continue;
            }

            viewers.add(player);
            if (this.isEligibleForCapture(player)) {
                ArenaTeam team = player.getTeam();
                if (team != null) {
                    strengths.merge(team, 1, Integer::sum);
                }
            }
        }

        if (viewers.isEmpty()) {
            return ControlState.EMPTY;
        }

        return new ControlState(viewers, strengths);
    }

    private boolean isEligibleForCapture(ArenaPlayer player) {
        if (player.getRole() != PlayerRole.PLAYING) {
            return false;
        }

        if (player.getPlayer().getGameMode() == GameMode.SPECTATOR) {
            return false;
        }

        return player.getTeam() != null;
    }

    private double computeProgressDelta(ControlState state) {
        int totalStrength = state.totalStrength();
        if (totalStrength == 0) {
            if (this.captureProgressTicks > 0D) {
                double decay = Math.min(1D, this.captureProgressTicks);
                if (this.captureProgressTicks - decay <= 0D) {
                    this.capturingTeam = null;
                }
                return -decay;
            }

            this.capturingTeam = null;
            return 0D;
        }

        if (this.capturingTeam == null) {
            ArenaTeam strongest = state.strongestTeam();
            if (strongest == null || strongest.equals(this.owner)) {
                return this.captureProgressTicks > 0D ? -Math.min(1D, this.captureProgressTicks) : 0D;
            }

            int advantage = state.advantage(strongest);
            if (advantage > 0) {
                this.capturingTeam = strongest;
                return advantage;
            }

            return this.captureProgressTicks > 0D ? -Math.min(1D, this.captureProgressTicks) : 0D;
        }

        int advantage = state.advantage(this.capturingTeam);
        if (advantage > 0) {
            return advantage;
        }

        if (advantage < 0) {
            double delta = advantage;
            if (this.captureProgressTicks + delta <= 0D) {
                delta = -this.captureProgressTicks;
                this.capturingTeam = null;
            }
            return delta;
        }

        if (state.strength(this.capturingTeam) == 0 && this.captureProgressTicks > 0D) {
            double decay = Math.min(1D, this.captureProgressTicks);
            if (this.captureProgressTicks - decay <= 0D) {
                this.capturingTeam = null;
            }
            return -decay;
        }

        return 0D;
    }

    private void playSwirlParticles() {
        World world = this.center.getWorld();
        if (world == null) {
            return;
        }

        double centerX = this.center.getX();
        double centerY = this.center.getY();
        double centerZ = this.center.getZ();

        long tick = this.visualTick++;
        double baseAngle = tick * 0.12D;
        int outerStrands = 5;
        double swirlHeight = Math.max(1.25D, this.radius * 0.6D);
        Particle.DustOptions dust = this.resolveDustOptions();

        for (int strand = 0; strand < outerStrands; strand++) {
            double angle = baseAngle + strand * ((Math.PI * 2) / outerStrands);
            double dynamicRadius = this.radius * (0.85D + 0.18D * Math.sin(angle * 0.5D));
            double x = centerX + Math.cos(angle) * dynamicRadius;
            double z = centerZ + Math.sin(angle) * dynamicRadius;
            double y = centerY + 0.3D + ((Math.sin(angle * 1.5D) + 1D) * (swirlHeight / 3D));

            world.spawnParticle(Particle.valueOf("DUST"), x, y, z, 1, 0D, 0D, 0D, 0D, dust);
        }

        double reverseBaseAngle = -baseAngle * 1.15D;
        int innerStrands = 4;
        for (int strand = 0; strand < innerStrands; strand++) {
            double angle = reverseBaseAngle + strand * ((Math.PI * 2) / innerStrands);
            double x = centerX + Math.cos(angle) * radius;
            double z = centerZ + Math.sin(angle) * radius;
            double y = centerY + 0.25D + (strand * 0.15D);

            world.spawnParticle(Particle.END_ROD, x, y, z, 1, 0D, 0D, 0D, 0D);
        }
    }

    private Particle.DustOptions resolveDustOptions() {
        ArenaTeam colorSource = this.capturingTeam != null ? this.capturingTeam : this.owner;
        java.awt.Color awtColor = colorSource == null ? null : colorSource.getColor();
        org.bukkit.Color bukkitColor;
        if (awtColor == null) {
            bukkitColor = org.bukkit.Color.fromRGB(150, 90, 255);
        } else {
            bukkitColor = org.bukkit.Color.fromRGB(awtColor.getRed(), awtColor.getGreen(), awtColor.getBlue());
        }

        float size = colorSource == null ? 1.1F : 1.35F;
        return new Particle.DustOptions(bukkitColor, size);
    }

    private void refreshVisuals(ControlState controlState) {
        if (controlState == null) {
            controlState = ControlState.EMPTY;
        }

        int totalStrength = controlState.totalStrength();
        int capturingStrength = this.capturingTeam == null ? 0 : controlState.strength(this.capturingTeam);
        int capturingAdvantage = capturingStrength - (totalStrength - capturingStrength);
        boolean activeCapture = !this.locked && this.capturingTeam != null && capturingAdvantage > 0;
        boolean contested = totalStrength > 0 && controlState.topAdvantage() == 0;
        boolean secured = !activeCapture && this.capturingTeam == null && this.owner != null && controlState.strength(this.owner) > 0 && !contested;

        double progress = this.locked ? 1D : this.captureProgressTicks / this.requiredTicks;
        progress = Math.min(1D, Math.max(0D, progress));

        this.updateBossBar(controlState, progress, activeCapture, secured, contested);
        ArenaTeam displayTeam = secured ? this.owner : this.capturingTeam;
        this.updateHologramProgress(progress, contested, displayTeam, activeCapture, secured);
    }

    private void updateBossBar(ControlState state, double progress, boolean activeCapture, boolean secured, boolean contested) {
        if (this.locked || state.viewers().isEmpty()) {
            if (!this.bossBarViewers.isEmpty()) {
                this.captureBossBar.removeAll();
                this.bossBarViewers.clear();
            }
            return;
        }

        BarColor color;
        String title;
        if (contested) {
            color = BarColor.RED;
            title = this.displayLabel + " - Contested!";
        } else if (activeCapture) {
            color = BarColor.PURPLE;
            title = this.displayLabel + " - Capturing";
            if (this.capturingTeam != null) {
                title += " (" + this.capturingTeam.getName() + ")";
            }
        } else if (secured && this.owner != null) {
            color = BarColor.GREEN;
            title = this.displayLabel + " - Secured by " + this.owner.getName();
        } else {
            color = BarColor.WHITE;
            title = this.displayLabel + " - Waiting for Control";
        }

        this.captureBossBar.setColor(color);
        this.captureBossBar.setTitle(title + " " + (int) Math.round(progress * 100D) + "%");
        this.captureBossBar.setProgress(progress);
        this.syncBossBarViewers(state.viewers());
    }

    private void syncBossBarViewers(List<ArenaPlayer> occupants) {
        Set<UUID> occupantIds = new HashSet<>();
        for (ArenaPlayer occupant : occupants) {
            occupantIds.add(occupant.getPlayer().getUniqueId());
        }

        this.bossBarViewers.removeIf(uuid -> {
            if (occupantIds.contains(uuid)) {
                return false;
            }
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                this.captureBossBar.removePlayer(player);
            }
            return true;
        });

        for (ArenaPlayer occupant : occupants) {
            UUID uuid = occupant.getPlayer().getUniqueId();
            if (this.bossBarViewers.add(uuid)) {
                this.captureBossBar.addPlayer(occupant.getPlayer());
            }
        }
    }

    private void updateHologramProgress(double progress, boolean contested, ArenaTeam displayTeam, boolean activeCapture, boolean secured) {
        if (this.hologram == null) {
            return;
        }

        this.hologram.setLines(
                this.buildTitleLine(),
                this.buildPowerupLine(),
                this.buildProgressLine(progress, contested, displayTeam, activeCapture, secured),
                INSTRUCTION_LINE
        );
    }

    private Component buildTitleLine() {
        return Component.text(this.displayLabel, NamedTextColor.GOLD);
    }

    private Component buildPowerupLine() {
        return Component.text("Powerup: ", NamedTextColor.GRAY)
                .append(Component.text(this.describeReward(), NamedTextColor.LIGHT_PURPLE));
    }

    private Component buildProgressLine(double progress, boolean contested, ArenaTeam controllingTeam, boolean activeCapture, boolean secured) {
        if (this.locked) {
            return Component.text("Captured!", NamedTextColor.GREEN);
        }

        if (contested) {
            return Component.text("Contested!", NamedTextColor.RED);
        }

        if (secured && controllingTeam != null) {
            return Component.text("Secured by " + controllingTeam.getName(), NamedTextColor.GREEN);
        }

        int percentage = (int) Math.round(progress * 100D);
        Component base = Component.text("Progress: ", NamedTextColor.YELLOW)
                .append(Component.text(percentage + "%", NamedTextColor.GOLD));

        if (activeCapture && controllingTeam != null) {
            base = base.append(Component.text(" (" + controllingTeam.getName() + ")", NamedTextColor.AQUA));
        }

        return base;
    }

    private String describeReward() {
        RewardType rewardType = this.definition.getRewardType();
        if (rewardType == null) {
            return "Random Reward";
        }

        return switch (rewardType) {
            case DAMAGE -> "Fury (Bonus Damage)";
            case RESISTANCE -> "Bulwark (Resistance)";
            case REDUCE_COOLDOWN -> "Focus (Cooldown Reduction)";
        };
    }

    record CaptureResult(ArenaTeam capturingTeam, ArenaTeam previousOwner, boolean lockedAfterCapture) {
    }

    private record ControlState(List<ArenaPlayer> viewers, Map<ArenaTeam, Integer> strengths) {
        private static final ControlState EMPTY = new ControlState(List.of(), Map.of());
        private static final int MAX_STRENGTH = 3;

        int strength(ArenaTeam team) {
            if (team == null) {
                return 0;
            }
            return Math.min(MAX_STRENGTH, this.strengths.getOrDefault(team, 0));
        }

        int totalStrength() {
            return this.strengths.values().stream()
                    .mapToInt(count -> Math.min(MAX_STRENGTH, count))
                    .sum();
        }

        ArenaTeam strongestTeam() {
            ArenaTeam strongest = null;
            int best = 0;
            boolean tie = false;
            for (Map.Entry<ArenaTeam, Integer> entry : this.strengths.entrySet()) {
                int count = Math.min(MAX_STRENGTH, entry.getValue());
                if (count > best) {
                    strongest = entry.getKey();
                    best = count;
                    tie = false;
                } else if (count == best) {
                    tie = true;
                }
            }

            if (tie) {
                return null;
            }

            return strongest;
        }

        int advantage(ArenaTeam team) {
            int teamStrength = this.strength(team);
            return teamStrength - (this.totalStrength() - teamStrength);
        }

        int topAdvantage() {
            ArenaTeam strongest = this.strongestTeam();
            if (strongest == null) {
                return 0;
            }

            return this.advantage(strongest);
        }
    }

    static final class CaptureNotification {
        private static final CaptureNotification NONE = new CaptureNotification(null, null);

        private final ArenaTeam startedTeam;
        private final CaptureResult result;

        private CaptureNotification(ArenaTeam startedTeam, CaptureResult result) {
            this.startedTeam = startedTeam;
            this.result = result;
        }

        static CaptureNotification none() {
            return NONE;
        }

        static CaptureNotification started(ArenaTeam team) {
            return new CaptureNotification(team, null);
        }

        static CaptureNotification completed(ArenaTeam startedTeam, CaptureResult result) {
            return new CaptureNotification(startedTeam, result);
        }

        public ArenaTeam startedTeam() {
            return this.startedTeam;
        }

        public Optional<CaptureResult> result() {
            return Optional.ofNullable(this.result);
        }
    }
}
