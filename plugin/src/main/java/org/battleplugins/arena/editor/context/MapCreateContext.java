package org.battleplugins.arena.editor.context;

import io.papermc.paper.math.Position;
import org.battleplugins.arena.Arena;
import org.battleplugins.arena.competition.map.ElementMatchup;
import org.battleplugins.arena.competition.map.LiveCompetitionMap;
import org.battleplugins.arena.competition.map.MapType;
import org.battleplugins.arena.competition.map.options.Bounds;
import org.battleplugins.arena.competition.map.options.Spawns;
import org.battleplugins.arena.competition.map.options.TeamSpawns;
import org.battleplugins.arena.editor.ArenaEditorWizard;
import org.battleplugins.arena.editor.EditorContext;
import org.battleplugins.arena.module.domination.config.DominationAreaDefinition;
import org.battleplugins.arena.module.domination.config.DominationMapSettings;
import org.battleplugins.arena.team.ArenaTeam;
import org.battleplugins.arena.util.IntRange;
import org.battleplugins.arena.util.PositionWithRotation;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.*;

public class MapCreateContext extends EditorContext<MapCreateContext> {
    private String mapName;
    private MapType mapType;
    private Position min;
    private Position max;
    private boolean remote;
    private List<ElementMatchup> matchups = new ArrayList<>();

    private PositionWithRotation waitroomSpawn;
    private PositionWithRotation spectatorSpawn;

    private final Map<String, List<PositionWithRotation>> spawns = new HashMap<>();
    private final Map<String, DominationAreaDefinition> dominationAreas = new LinkedHashMap<>();

    public MapCreateContext(ArenaEditorWizard<MapCreateContext> wizard, Arena arena, Player player) {
        super(wizard, arena, player);
    }

    public String getMapName() {
        return this.mapName;
    }

    public void setMapName(String mapName) {
        this.mapName = mapName;
    }

    public MapType getMapType() {
        return this.mapType;
    }

    public void setMapType(MapType mapType) {
        this.mapType = mapType;
    }

    public Position getMin() {
        return this.min;
    }

    public void setMin(Position min) {
        this.min = min;
    }

    public Position getMax() {
        return this.max;
    }

    public void setMax(Position max) {
        this.max = max;
    }

    public boolean isRemote() {
        return this.remote;
    }

    public void setRemote(boolean remote) {
        this.remote = remote;
    }

    public List<ElementMatchup> getMatchups() {
        return this.matchups;
    }

    public void setMatchups(List<ElementMatchup> matchups) {
        this.matchups = matchups == null ? new ArrayList<>() : new ArrayList<>(matchups);
    }

    public PositionWithRotation getWaitroomSpawn() {
        return this.waitroomSpawn;
    }

    public void setWaitroomSpawn(Location waitroomSpawn) {
        this.waitroomSpawn = new PositionWithRotation(waitroomSpawn);
    }

    public PositionWithRotation getSpectatorSpawn() {
        return this.spectatorSpawn;
    }

    public void setSpectatorSpawn(Location spectatorSpawn) {
        this.spectatorSpawn = new PositionWithRotation(spectatorSpawn);
    }

    public Map<String, List<PositionWithRotation>> getSpawns() {
        return this.spawns;
    }

    public void addSpawn(String team, PositionWithRotation spawns) {
        this.spawns.computeIfAbsent(team, k -> new ArrayList<>()).add(spawns);
    }

    public boolean hasValidTeamSpawns() {
        List<ArenaTeam> missingTeams = this.getMissingTeams();

        // No missing teams - we don't need to check for anything further
        if (missingTeams.isEmpty()) {
            return true;
        }

        IntRange teamAmount = this.arena.getTeams().getTeamAmount();
        if (teamAmount.getMax() == Integer.MAX_VALUE) {
            // Check if we have the spawns for the minimum amount
            int teamsWithSpawns = 0;
            for (ArenaTeam availableTeam : this.arena.getTeams().getAvailableTeams()) {
                if (this.spawns.containsKey(availableTeam.getName()) && !this.spawns.get(availableTeam.getName()).isEmpty()) {
                    teamsWithSpawns++;
                }
            }

            return teamsWithSpawns >= teamAmount.getMin();
        }

        // We are not bounded by the maximum value, so
        // each team must have a spawnpoint
        return false;
    }

    public List<ArenaTeam> getMissingTeams() {
        List<ArenaTeam> missingTeams = new ArrayList<>();
        for (ArenaTeam availableTeam : this.arena.getTeams().getAvailableTeams()) {
            if (!this.spawns.containsKey(availableTeam.getName()) || this.spawns.get(availableTeam.getName()).isEmpty()) {
                missingTeams.add(availableTeam);
            }
        }

        return missingTeams;
    }

    public void reconstructFrom(LiveCompetitionMap map) {
        this.reconstructed = true;

        Bounds bounds = map.getBounds();
        if (bounds == null) {
            throw new IllegalArgumentException("Map " + map.getName() + " does not have bounds!");
        }

        this.mapName = map.getName();
        this.mapType = map.getType();
        this.min = Position.block(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ());
        this.max = Position.block(bounds.getMaxX(), bounds.getMaxY(), bounds.getMaxZ());
        this.remote = map.isRemote();
        this.matchups = new ArrayList<>(map.getMatchups());

        Spawns spawns = map.getSpawns();
        if (spawns == null) {
            throw new IllegalArgumentException("Map " + map.getName() + " does not have spawns!");
        }

        this.waitroomSpawn = spawns.getWaitroomSpawn();
        this.spectatorSpawn = spawns.getSpectatorSpawn();

        if (spawns.getTeamSpawns() == null) {
            throw new IllegalArgumentException("Map " + map.getName() + " does not have team spawns!");
        }

        spawns.getTeamSpawns().forEach((team, teamSpawns) -> this.spawns.put(team, teamSpawns.getSpawns()));

        map.getDominationSettings().ifPresent(settings -> this.dominationAreas.putAll(settings.getAreas()));
    }

    public void saveTo(LiveCompetitionMap map) {
        Bounds bounds = new Bounds(
                this.min.blockX(),
                this.min.blockY(),
                this.min.blockZ(),
                this.max.blockX(),
                this.max.blockY(),
                this.max.blockZ()
        );

        map.setName(this.mapName);
        map.setType(this.mapType);
        map.setBounds(bounds);

        Map<String, TeamSpawns> teamSpawns = new HashMap<>();
        Spawns spawns = new Spawns(this.waitroomSpawn, this.spectatorSpawn, teamSpawns);
        this.spawns.forEach((team, spawnsList) -> teamSpawns.put(team, new TeamSpawns(spawnsList)));

        map.setSpawns(spawns);
        map.setRemote(this.remote);
        map.setMatchups(this.matchups);
        map.setDomination(this.buildDominationSettings());
    }

    @Override
    public boolean isComplete() {
        return this.mapName != null
                && this.mapType != null
                && this.min != null
                && this.max != null
                && this.waitroomSpawn != null
                && this.spectatorSpawn != null
                && this.hasValidTeamSpawns();
    }

    @Override
    public String toString() {
        return "MapCreateContext{" +
                "mapName='" + this.mapName + '\'' +
                ", mapType=" + this.mapType +
                ", min=" + this.min +
                ", max=" + this.max +
                ", remote=" + this.remote +
                ", waitroomSpawn=" + this.waitroomSpawn +
                ", spectatorSpawn=" + this.spectatorSpawn +
                ", spawns=" + this.spawns +
                ", dominationAreas=" + this.dominationAreas.keySet() +
                ", wizard=" + this.wizard +
                ", arena=" + this.arena +
                ", player=" + this.player +
                ", reconstructed=" + this.reconstructed +
                '}';
    }

    public Map<String, DominationAreaDefinition> getDominationAreas() {
        return this.dominationAreas;
    }

    public void addDominationArea(String id, DominationAreaDefinition definition) {
        this.dominationAreas.put(id, definition);
    }

    public boolean removeDominationArea(String id) {
        String key = this.findDominationAreaKey(id);
        if (key == null) {
            return false;
        }

        this.dominationAreas.remove(key);
        return true;
    }

    public boolean hasDominationArea(String id) {
        return this.findDominationAreaKey(id) != null;
    }

    public DominationMapSettings buildDominationSettings() {
        return this.dominationAreas.isEmpty() ? null : new DominationMapSettings(this.dominationAreas);
    }

    private String findDominationAreaKey(String id) {
        for (String existing : this.dominationAreas.keySet()) {
            if (existing.equalsIgnoreCase(id)) {
                return existing;
            }
        }

        return null;
    }
}
