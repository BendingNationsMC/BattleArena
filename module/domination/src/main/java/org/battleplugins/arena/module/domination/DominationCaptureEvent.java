package org.battleplugins.arena.module.domination;

import org.battleplugins.arena.Arena;
import org.battleplugins.arena.competition.Competition;
import org.battleplugins.arena.event.ArenaEvent;
import org.battleplugins.arena.event.EventTrigger;
import org.battleplugins.arena.module.domination.config.DominationAreaDefinition;
import org.battleplugins.arena.resolver.Resolver;
import org.battleplugins.arena.resolver.ResolverKeys;
import org.battleplugins.arena.resolver.ResolverProvider;
import org.battleplugins.arena.team.ArenaTeam;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Event fired whenever a domination area is captured by a team.
 */
@EventTrigger("domination-capture")
public class DominationCaptureEvent extends Event implements ArenaEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Arena arena;
    private final Competition<?> competition;
    private final String areaId;
    private final DominationAreaDefinition areaDefinition;
    private final ArenaTeam capturingTeam;
    private final ArenaTeam previousOwner;
    private final boolean locked;

    public DominationCaptureEvent(
            Arena arena,
            Competition<?> competition,
            String areaId,
            DominationAreaDefinition areaDefinition,
            ArenaTeam capturingTeam,
            @Nullable ArenaTeam previousOwner,
            boolean locked
    ) {
        this.arena = arena;
        this.competition = competition;
        this.areaId = areaId;
        this.areaDefinition = areaDefinition;
        this.capturingTeam = capturingTeam;
        this.previousOwner = previousOwner;
        this.locked = locked;

    }

    @Override
    public Arena getArena() {
        return this.arena;
    }

    @Override
    public Competition<?> getCompetition() {
        return this.competition;
    }

    public String getAreaId() {
        return this.areaId;
    }

    public DominationAreaDefinition getAreaDefinition() {
        return this.areaDefinition;
    }

    public ArenaTeam getCapturingTeam() {
        return this.capturingTeam;
    }

    @Nullable
    public ArenaTeam getPreviousOwner() {
        return this.previousOwner;
    }

    public boolean isLockedAfterCapture() {
        return this.locked;
    }

    @Override
    public Resolver resolve() {
        Resolver.Builder builder = ArenaEvent.super.resolve().toBuilder();
        if (this.capturingTeam != null) {
            builder.define(ResolverKeys.TEAM, ResolverProvider.simple(this.capturingTeam, ArenaTeam::getName, ArenaTeam::getFormattedName));
        }

        builder.define(ResolverKeys.DOMINATION_AREA_ID, ResolverProvider.simple(this.areaId, id -> id));
        builder.define(ResolverKeys.DOMINATION_AREA_NAME, ResolverProvider.simple(this.resolveAreaName(), name -> name));
        return builder.build();
    }

    private String resolveAreaName() {
        return this.areaDefinition.getDisplayName() == null || this.areaDefinition.getDisplayName().isBlank()
                ? this.areaId
                : this.areaDefinition.getDisplayName();
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
