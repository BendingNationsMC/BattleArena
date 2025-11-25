package org.battleplugins.arena.module.domination;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.attribute.AttributeModification;
import com.projectkorra.projectkorra.attribute.AttributeModifier;
import com.projectkorra.projectkorra.event.AbilityRecalculateAttributeEvent;
import com.projectkorra.projectkorra.object.Style;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.battleplugins.arena.Arena;
import org.battleplugins.arena.ArenaPlayer;
import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.competition.Competition;
import org.battleplugins.arena.competition.LiveCompetition;
import org.battleplugins.arena.config.ArenaConfigParser;
import org.battleplugins.arena.config.ParseException;
import org.battleplugins.arena.event.ArenaEventType;
import org.battleplugins.arena.event.arena.ArenaInitializeEvent;
import org.battleplugins.arena.messages.Message;
import org.battleplugins.arena.messages.Messages;
import org.battleplugins.arena.module.ArenaModule;
import org.battleplugins.arena.module.ArenaModuleInitializer;
import org.battleplugins.arena.module.domination.config.DominationArenaSettings;
import org.battleplugins.arena.module.domination.config.RewardType;
import org.battleplugins.arena.team.ArenaTeam;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry point for the Domination module.
 */
@ArenaModule(id = DominationModule.ID, name = "Domination", description = "Adds configurable domination capture points to arenas.", authors = "BattlePlugins")
public class DominationModule implements ArenaModuleInitializer, Listener {
    public static final String ID = "domination";
    @SuppressWarnings("unused")
    private static final ArenaEventType<DominationCaptureEvent> DOMINATION_CAPTURE_EVENT = ArenaEventType.create("domination-capture", DominationCaptureEvent.class);
    @SuppressWarnings("unused")
    private static final ArenaEventType<DominationCaptureStartEvent> DOMINATION_CAPTURE_START_EVENT = ArenaEventType.create("domination-capture-start", DominationCaptureStartEvent.class);

    private final Map<String, DominationArenaHandler> handlers = new ConcurrentHashMap<>();
    private final Set<UUID> increasedDamage = new HashSet<>();
    private final Set<UUID> damageResistance = new HashSet<>();

    @EventHandler
    public void onArenaInitialize(ArenaInitializeEvent event) {
        Arena arena = event.getArena();
        if (!arena.isModuleEnabled(ID)) {
            return;
        }

        ConfigurationSection dominationSection = arena.getConfig().get("domination");
        DominationArenaSettings settings;
        if (dominationSection != null) {
            try {
                settings = ArenaConfigParser.newInstance(DominationArenaSettings.class, dominationSection, arena, null);
            } catch (ParseException e) {
                ParseException.handle(e.context("Arena", arena.getName()));
                BattleArena.getInstance().warn("Failed to parse domination configuration for arena {}.", arena.getName());
                return;
            }
        } else {
            settings = new DominationArenaSettings();
        }

        this.registerHandler(arena, settings);
    }

    private void registerHandler(Arena arena, DominationArenaSettings settings) {
        String key = arena.getName().toLowerCase(Locale.ROOT);

        DominationArenaHandler existing = this.handlers.remove(key);
        if (existing != null) {
            arena.getEventManager().unregisterEvents(existing);
            existing.shutdown();
        }

        DominationArenaHandler handler = new DominationArenaHandler(this, arena, settings);
        this.handlers.put(key, handler);
        arena.getEventManager().registerEvents(handler);
    }

    void handleAreaCapture(Arena arena, Competition<?> competition, DominationAreaTracker tracker, DominationAreaTracker.CaptureResult result) {
        DominationCaptureEvent event = new DominationCaptureEvent(
                arena,
                competition,
                tracker.getId(),
                tracker.getDefinition(),
                result.capturingTeam(),
                result.previousOwner(),
                result.lockedAfterCapture()
        );

        arena.getEventManager().callEvent(event);
        this.handlePowerup(event);
        this.broadcastCaptureResult(competition, tracker, result);
    }

    void handleAreaCaptureStart(Arena arena, Competition<?> competition, DominationAreaTracker tracker, ArenaTeam capturingTeam) {
        DominationCaptureStartEvent event = new DominationCaptureStartEvent(
                arena,
                competition,
                tracker.getId(),
                tracker.getDefinition(),
                capturingTeam
        );

        arena.getEventManager().callEvent(event);
        this.broadcastCaptureStart(competition, tracker, capturingTeam);
    }

    protected void handlePowerup(DominationCaptureEvent event) {
        var competition = event.getCompetition();
        var areaDef = event.getAreaDefinition();

        if (!(competition instanceof LiveCompetition<?> liveCompetition)) {
            return;
        }

        var rewardType = areaDef.getRewardType();
        if (rewardType == null) {
            return;
        }

        this.removePowerup(liveCompetition, event.getPreviousOwner(), rewardType);
        this.applyPowerup(liveCompetition, event.getCapturingTeam(), rewardType);
    }

    private void broadcastCaptureStart(Competition<?> competition, DominationAreaTracker tracker, ArenaTeam capturingTeam) {
        if (capturingTeam == null) {
            return;
        }

        Component message = this.prefixed(Messages.DOMINATION_CAPTURE_STARTED,
                this.formatTeamName(capturingTeam),
                this.formatAreaName(tracker));
        this.broadcast(competition, message);
    }

    private void broadcastCaptureResult(Competition<?> competition, DominationAreaTracker tracker, DominationAreaTracker.CaptureResult result) {
        ArenaTeam capturingTeam = result.capturingTeam();
        if (capturingTeam == null) {
            return;
        }

        Message template = result.lockedAfterCapture()
                ? Messages.DOMINATION_CAPTURE_LOCKED
                : Messages.DOMINATION_CAPTURE_COMPLETED;
        Component captured = this.prefixed(template,
                this.formatTeamName(capturingTeam),
                this.formatAreaName(tracker));
        this.broadcast(competition, captured);

        ArenaTeam previousOwner = result.previousOwner();
        if (previousOwner != null && !previousOwner.equals(capturingTeam)) {
            Component lost = this.prefixed(Messages.DOMINATION_CAPTURE_LOST,
                    this.formatTeamName(previousOwner),
                    this.formatAreaName(tracker));
            this.broadcast(competition, lost);
        }
    }

    private Component formatTeamName(ArenaTeam team) {
        return team == null ? Component.text("Unknown", NamedTextColor.GRAY) : team.getFormattedName();
    }

    private Component formatAreaName(DominationAreaTracker tracker) {
        return Component.text(tracker.getDisplayLabel(), Messages.SECONDARY_COLOR);
    }

    private void broadcast(Competition<?> competition, Component message) {
        if (!(competition instanceof LiveCompetition<?> liveCompetition)) {
            return;
        }

        Set<UUID> delivered = new HashSet<>();
        liveCompetition.getPlayers().forEach(player -> this.sendIfNeeded(player, message, delivered));
        liveCompetition.getSpectators().forEach(spectator -> this.sendIfNeeded(spectator, message, delivered));
    }

    private void sendIfNeeded(ArenaPlayer arenaPlayer, Component message, Set<UUID> delivered) {
        Player player = arenaPlayer.getPlayer();
        if (player == null || !player.isOnline()) {
            return;
        }

        if (delivered.add(player.getUniqueId())) {
            player.sendMessage(message);
        }
    }

    private Component prefixed(Message message, Component... replacements) {
        return Messages.PREFIX.toComponent()
                .append(Component.space())
                .append(message.toComponent(replacements));
    }

    private void applyPowerup(LiveCompetition<?> competition, ArenaTeam team, RewardType rewardType) {
        if (team == null || rewardType == null) {
            return;
        }

        Set<ArenaPlayer> arenaPlayers = competition.getTeamManager().getPlayersOnTeam(team);
        if (arenaPlayers == null || arenaPlayers.isEmpty()) {
            return;
        }

        switch (rewardType) {
            case DAMAGE -> {
                for (ArenaPlayer arenaPlayer : arenaPlayers) {
                    Player player = arenaPlayer.getPlayer();
                    if (player != null) {
                        this.increasedDamage.add(player.getUniqueId());
                    }
                }
            }
            case RESISTANCE -> {
                PotionEffectType resistance = PotionEffectType.getByName("RESISTANCE");
                if (resistance == null) {
                    return;
                }

                for (ArenaPlayer player : arenaPlayers) {
                    Player bukkitPlayer = player.getPlayer();
                    if (bukkitPlayer == null) {
                        continue;
                    }

                    bukkitPlayer.addPotionEffect(new PotionEffect(resistance, 9999999, 0));
                    this.damageResistance.add(bukkitPlayer.getUniqueId());
                }
            }
            case REDUCE_COOLDOWN -> {
                Style cooldown = Style.getStyle("cooldown");
                for (ArenaPlayer arenaPlayer : arenaPlayers) {
                    BendingPlayer bendingPlayer = BendingPlayer.getBendingPlayer(arenaPlayer.getPlayer());
                    if (bendingPlayer != null) {
                        bendingPlayer.setStyle(cooldown);
                    }
                }
            }
        }
    }

    private void removePowerup(LiveCompetition<?> competition, ArenaTeam team, RewardType rewardType) {
        if (team == null || rewardType == null) {
            return;
        }

        Set<ArenaPlayer> arenaPlayers = competition.getTeamManager().getPlayersOnTeam(team);
        if (arenaPlayers == null || arenaPlayers.isEmpty()) {
            return;
        }

        switch (rewardType) {
            case DAMAGE -> {
                for (ArenaPlayer arenaPlayer : arenaPlayers) {
                    Player player = arenaPlayer.getPlayer();
                    if (player != null) {
                        this.increasedDamage.remove(player.getUniqueId());
                    }
                }
            }
            case RESISTANCE -> {
                PotionEffectType resistance = PotionEffectType.getByName("RESISTANCE");
                if (resistance == null) {
                    return;
                }

                for (ArenaPlayer player : arenaPlayers) {
                    Player bukkitPlayer = player.getPlayer();
                    if (bukkitPlayer == null) {
                        continue;
                    }

                    this.damageResistance.remove(bukkitPlayer.getUniqueId());
                    bukkitPlayer.removePotionEffect(resistance);
                }
            }
            case REDUCE_COOLDOWN -> {
                for (ArenaPlayer arenaPlayer : arenaPlayers) {
                    BendingPlayer bendingPlayer = BendingPlayer.getBendingPlayer(arenaPlayer.getPlayer());
                    if (bendingPlayer != null) {
                        bendingPlayer.setStyle(null);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onRecalculate(AbilityRecalculateAttributeEvent event) {
        if (!Attribute.DAMAGE.equals(event.getAttribute()))
            return;

        BendingPlayer bPlayer = event.getAbility().getBendingPlayer();
        if (bPlayer == null || !increasedDamage.contains(bPlayer.getPlayer().getUniqueId()))
            return;

        final double modification = event.getAbility().getElement() == Element.CHI ? 0.5 : 1;

        event.addModification(AttributeModification.of(AttributeModifier.ADDITION, modification,
                new NamespacedKey(BattleArena.getInstance(), "double_damage")));
    }

    public Set<UUID> getDamageResistance() {
        return damageResistance;
    }

    public Set<UUID> getIncreasedDamage() {
        return increasedDamage;
    }
}
