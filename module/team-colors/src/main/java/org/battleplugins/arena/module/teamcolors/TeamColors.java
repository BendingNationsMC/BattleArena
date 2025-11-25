package org.battleplugins.arena.module.teamcolors;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.battleplugins.arena.ArenaPlayer;
import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.competition.LiveCompetition;
import org.battleplugins.arena.event.arena.ArenaPhaseStartEvent;
import org.battleplugins.arena.event.player.ArenaJoinEvent;
import org.battleplugins.arena.event.player.ArenaLeaveEvent;
import org.battleplugins.arena.event.player.ArenaTeamJoinEvent;
import org.battleplugins.arena.event.player.ArenaTeamLeaveEvent;
import org.battleplugins.arena.module.ArenaModule;
import org.battleplugins.arena.module.ArenaModuleInitializer;
import org.battleplugins.arena.options.ArenaOptionType;
import org.battleplugins.arena.options.types.BooleanArenaOption;
import org.battleplugins.arena.team.ArenaTeam;
import org.battleplugins.arena.team.ArenaTeams;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Team;

/**
 * A module that adds team colors to a player's name.
 */
@ArenaModule(id = TeamColors.ID, name = "Team Colors", description = "Adds player team colors to their name.", authors = "BattlePlugins")
public class TeamColors implements ArenaModuleInitializer {
    public static final String ID = "team-colors";

    public static final ArenaOptionType<BooleanArenaOption> TEAM_PREFIXES = ArenaOptionType.create("team-prefixes", BooleanArenaOption::new);
    public static final ArenaOptionType<BooleanArenaOption> ELEMENT_COLORS = ArenaOptionType.create("element-colors", BooleanArenaOption::new);
    public static final ArenaOptionType<BooleanArenaOption> TEAM_GLOW = ArenaOptionType.create("team-glow", BooleanArenaOption::new);

    private final PacketListenerAbstract glowListener;

    public TeamColors() {
        PacketListenerAbstract listener = null;
        if (Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
            try {
                listener = new TeamGlowPacketListener();
                PacketEvents.getAPI().getEventManager().registerListener(listener);
            } catch (Exception ex) {
                BattleArena.getInstance().warn("Failed to register team glow listener: {}", ex.getMessage());
                listener = null;
            }
        }

        this.glowListener = listener;
    }

    @EventHandler
    public void onJoin(ArenaJoinEvent event) {
        if (!event.getArena().isModuleEnabled(ID)) {
            return;
        }

        this.post(5, () -> {
            for (ArenaTeam team : event.getCompetition().getTeamManager().getTeams()) {
                // Register a new Bukkit team for each team in the competition
                Team bukkitTeam = event.getPlayer().getScoreboard().getTeam("ba-" + team.getName());
                if (bukkitTeam == null) {
                    bukkitTeam = event.getPlayer().getScoreboard().registerNewTeam("ba-" + team.getName());
                    this.applyTeamColors(event.getPlayer(), event.getCompetition(), team, bukkitTeam);
                }

                // If players are already on the team, add them to the Bukkit team
                for (ArenaPlayer teamPlayer : event.getCompetition().getTeamManager().getPlayersOnTeam(team)) {
                    bukkitTeam.addPlayer(teamPlayer.getPlayer());
                }
            }

            this.updateGlow(event.getArenaPlayer(), event.getCompetition());
        });
    }

    @EventHandler
    public void onPhaseStart(ArenaPhaseStartEvent event) {
        if (!event.getArena().isModuleEnabled(ID)) {
            return;
        }

        this.post(5, () -> {
            // Scoreboards may change when phases change, so update
            // team colors in player scoreboards when this happens
            if (event.getCompetition() instanceof LiveCompetition<?> liveCompetition) {
                for (ArenaPlayer arenaPlayer : liveCompetition.getPlayers()) {
                    Player player = arenaPlayer.getPlayer();
                    for (ArenaTeam team : liveCompetition.getTeamManager().getTeams()) {
                        Team bukkitTeam = player.getScoreboard().getTeam("ba-" + team.getName());
                        if (bukkitTeam == null) {
                            bukkitTeam = player.getScoreboard().registerNewTeam("ba-" + team.getName());
                            this.applyTeamColors(player, liveCompetition, team, bukkitTeam);
                        }

                        for (ArenaPlayer teamPlayer : arenaPlayer.getCompetition().getTeamManager().getPlayersOnTeam(team)) {
                            bukkitTeam.addPlayer(teamPlayer.getPlayer());
                        }
                    }

                    this.updateGlow(arenaPlayer, liveCompetition);
                }
            }
        });
    }

    @EventHandler
    public void onLeave(ArenaLeaveEvent event) {
        if (!event.getArena().isModuleEnabled(ID)) {
            return;
        }

        this.post(5, () -> {
            if (event.getArenaPlayer().getTeam() != null) {
                this.leaveTeam(event.getPlayer(), event.getCompetition(), event.getArenaPlayer().getTeam());
            }

            // Remove all teams for the player
            for (ArenaTeam team : event.getCompetition().getTeamManager().getTeams()) {
                Team bukkitTeam = event.getPlayer().getScoreboard().getTeam("ba-" + team.getName());
                if (bukkitTeam != null) {
                    bukkitTeam.unregister();
                }
            }
        });

        event.getPlayer().removePotionEffect(PotionEffectType.GLOWING);
    }

    @EventHandler
    public void onTeamJoin(ArenaTeamJoinEvent event) {
        if (!event.getArena().isModuleEnabled(ID)) {
            return;
        }

        this.post(6, () -> {
            this.joinTeam(event.getPlayer(), event.getCompetition(), event.getTeam());
            this.updateGlow(event.getArenaPlayer(), event.getCompetition());
        });
    }

    @EventHandler
    public void onTeamLeave(ArenaTeamLeaveEvent event) {
        if (!event.getArena().isModuleEnabled(ID)) {
            return;
        }

        this.post(5, () -> {
            this.leaveTeam(event.getPlayer(), event.getCompetition(), event.getTeam());
            this.updateGlow(event.getArenaPlayer(), event.getCompetition());
        });
    }

    private void joinTeam(Player player, LiveCompetition<?> competition, ArenaTeam arenaTeam) {
        for (ArenaPlayer arenaPlayer : competition.getPlayers()) {
            Player competitionPlayer = arenaPlayer.getPlayer();

            Team team = competitionPlayer.getScoreboard().getTeam("ba-" + arenaTeam.getName());
            if (team == null) {
                BattleArena.getInstance().warn("Team {} does not have a Bukkit team registered for {}!", arenaTeam.getName(), player.getName());
                continue;
            }

            team.addPlayer(player);
            this.applyTeamColors(competitionPlayer, competition, arenaTeam, team);
        }
    }

    private void leaveTeam(Player player, LiveCompetition<?> competition, ArenaTeam arenaTeam) {
        for (ArenaPlayer arenaPlayer : competition.getPlayers()) {
            Player competitionPlayer = arenaPlayer.getPlayer();
            Team team = competitionPlayer.getScoreboard().getTeam("ba-" + arenaTeam.getName());
            if (team != null) {
                team.removePlayer(player);
                this.applyTeamColors(competitionPlayer, competition, arenaTeam, team);
            }
        }
    }

    private void post(int ticks, Runnable runnable) {
        Bukkit.getScheduler().runTaskLater(BattleArena.getInstance(), runnable, ticks);
    }

    private static boolean showTeamPrefixes(LiveCompetition<?> competition, ArenaTeam team) {
        if (team == ArenaTeams.DEFAULT) {
            return false;
        }

        return competition.option(TEAM_PREFIXES)
                .map(BooleanArenaOption::isEnabled)
                .orElse(true);
    }

    private boolean useTeamGlow(LiveCompetition<?> competition) {
        if (this.glowListener == null) {
            return false;
        }

        return competition.option(TEAM_GLOW)
                .map(BooleanArenaOption::isEnabled)
                .orElse(false);
    }

    private void updateGlow(ArenaPlayer arenaPlayer, LiveCompetition<?> competition) {
        if (arenaPlayer == null) {
            return;
        }

        Player player = arenaPlayer.getPlayer();
        if (player == null) {
            return;
        }

        boolean enableGlow = this.useTeamGlow(competition)
                && arenaPlayer.getTeam() != null
                && arenaPlayer.getTeam() != ArenaTeams.DEFAULT;

        if (enableGlow) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, Integer.MAX_VALUE, 0, false, false, false));
        } else {
            player.removePotionEffect(PotionEffectType.GLOWING);
        }
    }

    private Player findPlayerByEntityId(int entityId) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getEntityId() == entityId) {
                return online;
            }
        }
        return null;
    }

    private void applyTeamColors(Player viewer, LiveCompetition<?> competition, ArenaTeam arenaTeam, Team team) {
        ArenaPlayer viewerArenaPlayer = ArenaPlayer.getArenaPlayer(viewer);
        ArenaTeam viewerTeam = viewerArenaPlayer == null ? null : viewerArenaPlayer.getTeam();

        NamedTextColor color;
        if (viewerTeam != null && viewerTeam.equals(arenaTeam)) {
            color = this.useElementColors(competition)
                    ? this.resolveElementColor(competition, arenaTeam)
                    : NamedTextColor.nearestTo(arenaTeam.getTextColor());
        } else {
            color = NamedTextColor.WHITE;
        }

        team.displayName(arenaTeam.getFormattedName());
        team.color(color);
        if (showTeamPrefixes(competition, arenaTeam)) {
            team.prefix(Component.text("[" + arenaTeam.getName() + "] ", color));
        }
    }

    private boolean useElementColors(LiveCompetition<?> competition) {
        return competition.option(ELEMENT_COLORS)
                .map(BooleanArenaOption::isEnabled)
                .orElse(false);
    }

    private NamedTextColor resolveElementColor(LiveCompetition<?> competition, ArenaTeam team) {
        if (team == null || team == ArenaTeams.DEFAULT) {
            return NamedTextColor.WHITE;
        }

        BendingPlayer example = competition.getTeamManager().getPlayersOnTeam(team).stream()
                .map(ArenaPlayer::getPlayer)
                .map(BendingPlayer::getBendingPlayer)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);

        Element element = example == null ? null : example.getElements().stream().findFirst().orElse(null);
        if (element == null) {
            return NamedTextColor.nearestTo(team.getTextColor());
        }

        if (element == Element.FIRE) {
            return NamedTextColor.RED;
        }
        if (element == Element.WATER) {
            return NamedTextColor.BLUE;
        }
        if (element == Element.EARTH) {
            return NamedTextColor.GREEN;
        }
        if (element == Element.AIR) {
            return NamedTextColor.AQUA;
        }

        return NamedTextColor.nearestTo(team.getTextColor());
    }

    private final class TeamGlowPacketListener extends PacketListenerAbstract {
        TeamGlowPacketListener() {
            super(PacketListenerPriority.NORMAL);
        }

        @Override
        public void onPacketSend(PacketSendEvent event) {
            if (event.getPacketType() != PacketType.Play.Server.ENTITY_METADATA) {
                return;
            }

            Player viewer = event.getPlayer();
            if (viewer == null) {
                return;
            }

            ArenaPlayer viewerArena = ArenaPlayer.getArenaPlayer(viewer);
            if (viewerArena == null || viewerArena.getCompetition() == null) {
                return;
            }

            LiveCompetition<?> competition = viewerArena.getCompetition();
            if (!useTeamGlow(competition)) {
                return;
            }

            WrapperPlayServerEntityMetadata wrapper = new WrapperPlayServerEntityMetadata(event);
            wrapper.read();
            Player target = findPlayerByEntityId(wrapper.getEntityId());
            if (target == null) {
                return;
            }

            ArenaPlayer targetArena = ArenaPlayer.getArenaPlayer(target);
            if (targetArena == null || targetArena.getCompetition() == null || targetArena.getCompetition() != competition) {
                return;
            }

            boolean sameTeam = viewerArena.getTeam() != null
                    && viewerArena.getTeam().equals(targetArena.getTeam())
                    && viewerArena.getTeam() != ArenaTeams.DEFAULT;

            for (EntityData<?> data : wrapper.getEntityMetadata()) {
                if (data.getIndex() != 0 || data.getType() != EntityDataTypes.BYTE) {
                    continue;
                }

                Object value = data.getValue();
                if (!(value instanceof Byte original)) {
                    continue;
                }

                byte updated = sameTeam ? (byte) (original | 0x40) : (byte) (original & ~0x40);
                if (updated != original) {
                    @SuppressWarnings("unchecked")
                    EntityData<Byte> byteData = (EntityData<Byte>) data;
                    byteData.setValue(updated);
                    wrapper.write();
                }
                break;
            }
        }
    }
}
