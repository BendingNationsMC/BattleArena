package org.battleplugins.arena.module.domination;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.object.Style;
import org.battleplugins.arena.Arena;
import org.battleplugins.arena.competition.Competition;
import org.battleplugins.arena.competition.LiveCompetition;
import org.battleplugins.arena.competition.phase.CompetitionPhaseType;
import org.battleplugins.arena.event.ArenaEventHandler;
import org.battleplugins.arena.event.ArenaListener;
import org.battleplugins.arena.event.arena.ArenaPhaseCompleteEvent;
import org.battleplugins.arena.event.arena.ArenaPhaseStartEvent;
import org.battleplugins.arena.event.arena.ArenaRemoveCompetitionEvent;
import org.battleplugins.arena.event.player.ArenaJoinEvent;
import org.battleplugins.arena.event.player.ArenaLeaveEvent;
import org.battleplugins.arena.event.player.ArenaRespawnEvent;
import org.battleplugins.arena.module.domination.config.DominationArenaSettings;
import org.battleplugins.arena.module.domination.config.DominationMapSettings;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;

/**
 * Registers arena-specific listeners and manages per-competition domination state.
 */
final class DominationArenaHandler implements ArenaListener {

    private final DominationModule module;
    private final Arena arena;
    private final DominationArenaSettings settings;
    private final Map<Competition<?>, DominationRound> activeRounds = new HashMap<>();

    DominationArenaHandler(DominationModule module, Arena arena, DominationArenaSettings settings) {
        this.module = module;
        this.arena = arena;
        this.settings = settings;
    }

    public Arena getArena() {
        return this.arena;
    }

    public DominationArenaSettings getSettings() {
        return this.settings;
    }

    @ArenaEventHandler
    public void onPhaseStart(ArenaPhaseStartEvent event) {
        if (!this.isTrackingPhase(event.getPhase().getType())) {
            return;
        }

        this.startRound(event.getCompetition());
    }

    @ArenaEventHandler
    public void onPhaseComplete(ArenaPhaseCompleteEvent event) {
        if (!this.isTrackingPhase(event.getPhase().getType())) {
            return;
        }

        this.stopRound(event.getCompetition());
    }

    @ArenaEventHandler
    public void onCompetitionRemoved(ArenaRemoveCompetitionEvent event) {
        this.stopRound(event.getCompetition());
    }

    @ArenaEventHandler
    public void onRespawn(ArenaRespawnEvent event) {
        if (this.module.getDamageResistance().contains(event.getPlayer().getUniqueId())) {
            event.getPlayer().addPotionEffect(new PotionEffect(PotionEffectType.getByName("RESISTANCE"), 9999, 0));
        }

        if (this.module.getAvatarState().contains(event.getPlayer().getUniqueId())) {
            event.getPlayer().setGlowing(true);
        }
    }

    @ArenaEventHandler
    public void onPlayerLeave(ArenaLeaveEvent event) {
        this.module.getDamageResistance().remove(event.getPlayer().getUniqueId());
        this.module.getIncreasedDamage().remove(event.getPlayer().getUniqueId());
        this.module.getAvatarState().remove(event.getPlayer().getUniqueId());
        event.getPlayer().setGlowing(false);

        BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(event.getPlayer());
        if (bPlayer == null) return;
        bPlayer.setStyle(null);
    }

    @ArenaEventHandler
    public void onPlayerJoin(ArenaJoinEvent event) {
        event.getPlayer().setGlowing(false);
    }

    void shutdown() {
        this.activeRounds.values().forEach(DominationRound::stop);
        this.activeRounds.clear();
    }

    private boolean isTrackingPhase(CompetitionPhaseType<?, ?> phaseType) {
        CompetitionPhaseType<?, ?> activePhase = this.settings.getActivePhase();
        return activePhase == null || activePhase.equals(phaseType);
    }

    private void startRound(Competition<?> competition) {
        if (this.activeRounds.containsKey(competition)) {
            return;
        }

        if (!(competition instanceof LiveCompetition<?> liveCompetition)) {
            return;
        }

        DominationMapSettings mapSettings = liveCompetition.getMap().getDominationSettings().orElse(null);
        if (mapSettings == null || mapSettings.getAreas().isEmpty()) {
            return;
        }

        DominationRound round = new DominationRound(this.module, this.arena, liveCompetition, mapSettings);
        if (!round.hasTrackers()) {
            return;
        }

        round.start();
        this.activeRounds.put(competition, round);
    }

    private void stopRound(Competition<?> competition) {
        DominationRound round = this.activeRounds.remove(competition);
        if (round != null) {
            round.stop();
        }
    }
}
