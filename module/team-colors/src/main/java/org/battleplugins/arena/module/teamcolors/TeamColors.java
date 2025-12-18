package org.battleplugins.arena.module.teamcolors;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import fr.skytasul.glowingentities.GlowingEntities;
import net.kyori.adventure.text.format.NamedTextColor;
import org.battleplugins.arena.ArenaPlayer;
import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.competition.LiveCompetition;
import org.battleplugins.arena.event.BattleArenaPostInitializeEvent;
import org.battleplugins.arena.event.player.ArenaLeaveEvent;
import org.battleplugins.arena.event.player.ArenaTeamJoinEvent;
import org.battleplugins.arena.module.ArenaModule;
import org.battleplugins.arena.module.ArenaModuleInitializer;
import org.battleplugins.arena.options.ArenaOptionType;
import org.battleplugins.arena.options.types.BooleanArenaOption;
import org.battleplugins.arena.team.ArenaTeam;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;

/**
 * A module that adds team colors to a player's name.
 */
@SuppressWarnings("ALL")
@ArenaModule(id = TeamColors.ID, name = "Team Colors", description = "Adds player team colors to their name.", authors = "BattlePlugins")
public class TeamColors implements ArenaModuleInitializer {
    public static final String ID = "team-colors";

    public static final ArenaOptionType<BooleanArenaOption> TEAM_PREFIXES = ArenaOptionType.create("team-prefixes", BooleanArenaOption::new);
    public static final ArenaOptionType<BooleanArenaOption> ELEMENT_COLORS = ArenaOptionType.create("element-colors", BooleanArenaOption::new);
    public static final ArenaOptionType<BooleanArenaOption> TEAM_GLOW = ArenaOptionType.create("team-glow", BooleanArenaOption::new);
    private GlowingEntities glowing;

    @EventHandler
    public void onPostInitialize(BattleArenaPostInitializeEvent event) {
        System.out.println("Post init.");
        this.glowing = new GlowingEntities(BattleArena.getInstance());
    }

    private boolean useTeamGlow(LiveCompetition<?> competition) {
        if (this.glowing == null) {
            return false;
        }

        return competition.option(TEAM_GLOW)
                .map(BooleanArenaOption::isEnabled)
                .orElse(false);
    }

    private ChatColor toChatColor(NamedTextColor color) {
        if (color == null) {
            return ChatColor.WHITE;
        }

        if (color == NamedTextColor.RED) return ChatColor.RED;
        if (color == NamedTextColor.BLUE) return ChatColor.BLUE;
        if (color == NamedTextColor.GREEN) return ChatColor.GREEN;
        if (color == NamedTextColor.AQUA) return ChatColor.AQUA;
        if (color == NamedTextColor.WHITE) return ChatColor.WHITE;
        if (color == NamedTextColor.YELLOW) return ChatColor.YELLOW;
        if (color == NamedTextColor.GOLD) return ChatColor.GOLD;
        if (color == NamedTextColor.DARK_RED) return ChatColor.DARK_RED;
        if (color == NamedTextColor.DARK_GREEN) return ChatColor.DARK_GREEN;
        if (color == NamedTextColor.DARK_BLUE) return ChatColor.DARK_BLUE;
        if (color == NamedTextColor.DARK_AQUA) return ChatColor.DARK_AQUA;
        if (color == NamedTextColor.DARK_PURPLE) return ChatColor.DARK_PURPLE;
        if (color == NamedTextColor.LIGHT_PURPLE) return ChatColor.LIGHT_PURPLE;
        if (color == NamedTextColor.GRAY) return ChatColor.GRAY;
        if (color == NamedTextColor.DARK_GRAY) return ChatColor.DARK_GRAY;
        if (color == NamedTextColor.BLACK) return ChatColor.BLACK;

        return ChatColor.WHITE;
    }

    @EventHandler
    public void onJoin(ArenaTeamJoinEvent event) {
        if (!event.getArena().isModuleEnabled(ID)) {
            return;
        }

        System.out.println("Updating glow!!!");

        this.post(5, () -> {
            this.updateGlow(event.getArenaPlayer(), event.getCompetition());
        });
    }

    /**
     * Per-viewer glow color: teammates get element/team color, others white.
     */
    private ChatColor getGlowColor(LiveCompetition<?> competition, ArenaPlayer target, ArenaPlayer viewer) {
        return ChatColor.GREEN;
    }

    /**
     * Clears glowing for a specific arena player for all viewers in the competition.
     */
    private void clearGlow(ArenaPlayer targetArenaPlayer, LiveCompetition<?> competition) {
        if (this.glowing == null || targetArenaPlayer == null || competition == null) {
            return;
        }

        Player target = targetArenaPlayer.getPlayer();
        if (target == null) {
            return;
        }

        for (ArenaPlayer viewerArena : competition.getPlayers()) {
            Player receiver = viewerArena.getPlayer();
            if (receiver != null) {
                try {
                    this.glowing.unsetGlowing(target, receiver);
                } catch (ReflectiveOperationException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void updateGlow(ArenaPlayer arenaPlayer, LiveCompetition<?> competition) {
        if (arenaPlayer == null || competition == null || this.glowing == null) {
            System.out.println("SOMETHING HERE IS FUCKING NULL");
            return;
        }

        Player target = arenaPlayer.getPlayer();
        if (target == null) {
            System.out.println("Target is null");
            return;
        }

        boolean enableGlow = arenaPlayer.getTeam() != null;

        for (ArenaPlayer viewerArena : competition.getPlayers()) {
            Player receiver = viewerArena.getPlayer();
            if (receiver == null) {
                continue;
            }

            if (enableGlow) {
                System.out.println("GLOWING!");
                ChatColor color = getGlowColor(competition, arenaPlayer, viewerArena);
                try {
                    this.glowing.setGlowing(target, receiver, ChatColor.GREEN);
                } catch (ReflectiveOperationException e) {
                    e.printStackTrace();
                }
            } else {
                System.out.println("NOT GLOWING");
                try {
                    this.glowing.unsetGlowing(target, receiver);
                } catch (ReflectiveOperationException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @EventHandler
    public void onLeave(ArenaLeaveEvent event) {
        if (!event.getArena().isModuleEnabled(ID)) {
            return;
        }

        this.post(5, () -> {
            // Clear GlowingEntities glow for this player
            if (event.getCompetition() instanceof LiveCompetition<?> liveCompetition) {
                clearGlow(event.getArenaPlayer(), liveCompetition);
            }
        });
    }

    private boolean useElementColors(LiveCompetition<?> competition) {
        return competition.option(ELEMENT_COLORS)
                .map(BooleanArenaOption::isEnabled)
                .orElse(false);
    }

    private NamedTextColor resolveElementColor(LiveCompetition<?> competition, ArenaTeam team) {
        if (team == null) {
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

    private void post(int ticks, Runnable runnable) {
        Bukkit.getScheduler().runTaskLater(BattleArena.getInstance(), runnable, ticks);
    }
}
