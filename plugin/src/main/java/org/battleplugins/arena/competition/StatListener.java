package org.battleplugins.arena.competition;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.battleplugins.arena.ArenaPlayer;
import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.competition.PlayerRole;
import org.battleplugins.arena.competition.map.options.Spawns;
import org.battleplugins.arena.competition.map.options.TeamSpawns;
import org.battleplugins.arena.event.ArenaEventHandler;
import org.battleplugins.arena.event.ArenaListener;
import org.battleplugins.arena.event.player.ArenaDeathEvent;
import org.battleplugins.arena.event.player.ArenaKillEvent;
import org.battleplugins.arena.event.player.ArenaLifeDepleteEvent;
import org.battleplugins.arena.event.player.ArenaLivesExhaustEvent;
import org.battleplugins.arena.event.player.ArenaSpectateEvent;
import org.battleplugins.arena.event.player.ArenaStatChangeEvent;
import org.battleplugins.arena.messages.Messages;
import org.battleplugins.arena.options.Lives;
import org.battleplugins.arena.stat.ArenaStats;
import org.battleplugins.arena.team.ArenaTeam;
import org.battleplugins.arena.util.PositionWithRotation;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventPriority;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

class StatListener<T extends Competition<T>> implements ArenaListener, CompetitionLike<T> {
    private final LiveCompetition<T> competition;
    private final Map<UUID, RespawnCountdown> respawnCountdowns = new HashMap<>();

    public StatListener(LiveCompetition<T> competition) {
        this.competition = competition;
    }

    @ArenaEventHandler(priority = EventPriority.LOWEST)
    public void onDeath(ArenaDeathEvent event) {
        event.getArenaPlayer().computeStat(ArenaStats.DEATHS, old -> (old == null ? 0 : old) + 1);
        if (event.getArena().isLivesEnabled()) {
            event.getArenaPlayer().computeStat(ArenaStats.LIVES, old -> (old == null ? 0 : old) - 1);
        }
    }

    @ArenaEventHandler(priority = EventPriority.LOWEST)
    public void onKill(ArenaKillEvent event) {
        event.getKiller().computeStat(ArenaStats.KILLS, old -> (old == null ? 0 : old) + 1);
    }

    @ArenaEventHandler(priority = EventPriority.LOWEST)
    public void onStatChange(ArenaStatChangeEvent<?> event) {
        if (event.getStat() == ArenaStats.LIVES && event.getStatHolder() instanceof ArenaPlayer player) {
            int newValue = (int) event.getNewValue();
            if (event.getOldValue() != null && (int) event.getOldValue() < newValue) {
                return;
            }

            if (newValue == 0) {
                this.competition.getArena().getEventManager().callEvent(new ArenaLivesExhaustEvent(this.competition.getArena(), player));
                return;
            }

            if (newValue < 0) {
                return;
            }

            this.competition.getArena().getEventManager().callEvent(new ArenaLifeDepleteEvent(this.competition.getArena(), player, newValue));
        }
    }

    @ArenaEventHandler
    public void onLifeDeplete(ArenaLifeDepleteEvent event) {
        Lives lives = event.getArena().getLives();
        if (lives == null || !lives.isEnabled()) {
            return;
        }

        ArenaPlayer player = event.getArenaPlayer();
        if (player.getPlayer().isOnline()) {
            Messages.LIVES_REMAINING.send(player.getPlayer(), String.valueOf(event.getLivesLeft()));
        }

        Duration timeout = lives.getRespawnTimeout();
        long seconds = timeout == null ? 0L : (long) Math.ceil(timeout.toMillis() / 1000D);
        if (seconds <= 0L) {
            return;
        }

        this.startRespawnCountdown(player, seconds);
    }

    @ArenaEventHandler
    public void onLivesExhaust(ArenaLivesExhaustEvent event) {
        this.cancelRespawnCountdown(event.getArenaPlayer(), false);

        ArenaPlayer player = event.getArenaPlayer();
        if (player.getRole() == PlayerRole.SPECTATING) {
            return;
        }

        this.applySpectatorState(player);

        player.getCompetition().changeRole(player, PlayerRole.SPECTATING);
        player.getCompetition().getTeamManager().leaveTeam(player);
        player.getArena().getEventManager().callEvent(new ArenaSpectateEvent(player));
        this.ensureSpectatorState(player);
    }

    public void shutdown() {
        this.respawnCountdowns.values().forEach(countdown -> countdown.cancel(true));
        this.respawnCountdowns.clear();
    }

    private void startRespawnCountdown(ArenaPlayer player, long seconds) {
        this.cancelRespawnCountdown(player, true);

        RespawnCountdown countdown = new RespawnCountdown(player, seconds);
        this.respawnCountdowns.put(player.getPlayer().getUniqueId(), countdown);
        countdown.start();
    }

    private void cancelRespawnCountdown(ArenaPlayer player, boolean restoreState) {
        RespawnCountdown countdown = this.respawnCountdowns.remove(player.getPlayer().getUniqueId());
        if (countdown != null) {
            countdown.cancel(restoreState);
        }
    }

    private void applySpectatorState(ArenaPlayer player) {
        player.getPlayer().setGameMode(GameMode.SPECTATOR);
        player.getPlayer().setAllowFlight(true);
        player.getPlayer().setFlying(true);
    }

    private void ensureSpectatorState(ArenaPlayer player) {
        Bukkit.getScheduler().runTaskLater(BattleArena.getInstance(), () -> {
            if (!player.getPlayer().isOnline()) {
                return;
            }

            if (player.getCompetition() != this.competition) {
                return;
            }

            if (player.getRole() != PlayerRole.SPECTATING) {
                return;
            }

            this.applySpectatorState(player);
        }, 20L);
    }

    private void teleportToTeamSpawn(ArenaPlayer player) {
        Spawns spawns = player.getCompetition().getMap().getSpawns();
        if (spawns == null || spawns.getTeamSpawns() == null) {
            return;
        }

        ArenaTeam team = player.getTeam();
        if (team == null) {
            return;
        }

        TeamSpawns teamSpawns = spawns.getTeamSpawns().get(team.getName());
        if (teamSpawns == null || teamSpawns.getSpawns() == null || teamSpawns.getSpawns().isEmpty()) {
            return;
        }

        List<PositionWithRotation> options = teamSpawns.getSpawns();
        PositionWithRotation choice = options.get(ThreadLocalRandom.current().nextInt(options.size()));
        World world = player.getCompetition().getMap().getWorld();
        if (world == null) {
            return;
        }

        Location location = choice.toLocation(world);
        player.getPlayer().teleport(location);
    }

    private final class RespawnCountdown implements Runnable {
        private final ArenaPlayer player;
        private final GameMode previousMode;
        private long remainingSeconds;
        private BukkitTask task;

        private RespawnCountdown(ArenaPlayer player, long seconds) {
            this.player = player;
            this.previousMode = player.getPlayer().getGameMode();
            this.remainingSeconds = seconds;
        }

        private void start() {
            StatListener.this.applySpectatorState(this.player);
            this.scheduleSpectatorStateCheck();
            org.bukkit.attribute.AttributeInstance maxHealth = this.player.getPlayer().getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
            if (maxHealth != null) {
                this.player.getPlayer().setHealth(maxHealth.getDefaultValue());
            }

            this.task = Bukkit.getScheduler().runTaskTimer(BattleArena.getInstance(), this, 0L, 20L);
        }

        @Override
        public void run() {
            if (!this.player.getPlayer().isOnline() || this.player.getCompetition() != StatListener.this.competition) {
                this.cancel(false);
                respawnCountdowns.remove(this.player.getPlayer().getUniqueId());
                return;
            }

            if (this.remainingSeconds <= 0) {
                this.finish();
                return;
            }

            Title title = Title.title(
                    Component.text("Respawning in", NamedTextColor.GRAY),
                    Component.text(this.remainingSeconds + "s", NamedTextColor.GOLD)
            );
            this.player.getPlayer().showTitle(title);
            this.remainingSeconds--;

            if (this.remainingSeconds <= 0) {
                this.finish();
            }
        }

        private void scheduleSpectatorStateCheck() {
            Bukkit.getScheduler().runTaskLater(BattleArena.getInstance(), () -> {
                if (this.task == null) {
                    return;
                }

                if (!this.player.getPlayer().isOnline()) {
                    return;
                }

                if (this.player.getCompetition() != StatListener.this.competition) {
                    return;
                }

                StatListener.this.applySpectatorState(this.player);
            }, 20L);
        }

        private void finish() {
            this.cancel(false);

            if (!this.player.getPlayer().isOnline()) {
                respawnCountdowns.remove(this.player.getPlayer().getUniqueId());
                return;
            }

            this.player.getPlayer().setAllowFlight(false);
            this.player.getPlayer().setFlying(false);
            this.player.getPlayer().setGameMode(this.previousMode == null ? GameMode.SURVIVAL : this.previousMode);
            teleportToTeamSpawn(this.player);
            this.player.getPlayer().showTitle(Title.title(
                    Component.text("Respawned!", NamedTextColor.GREEN),
                    Component.empty()
            ));
            respawnCountdowns.remove(this.player.getPlayer().getUniqueId());
        }

        private void cancel(boolean restoreState) {
            if (this.task != null) {
                this.task.cancel();
                this.task = null;
            }

            if (restoreState && this.player.getPlayer().isOnline()) {
                this.player.getPlayer().setAllowFlight(false);
                this.player.getPlayer().setFlying(false);
                this.player.getPlayer().setGameMode(this.previousMode == null ? GameMode.SURVIVAL : this.previousMode);
                this.player.getPlayer().clearTitle();
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public T getCompetition() {
        return (T) this.competition;
    }
}
