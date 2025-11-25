package org.battleplugins.arena.module.domination.config;

import org.battleplugins.arena.config.ArenaOption;
import org.battleplugins.arena.util.PositionWithRotation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Configuration for a single domination capture area.
 */
public class DominationAreaDefinition {
    public DominationAreaDefinition() {
    }

    public DominationAreaDefinition(String displayName,
                                    PositionWithRotation location,
                                    double radius,
                                    Duration captureDuration,
                                    boolean lockAfterCapture,
                                    RewardType rewardType) {
        this.displayName = displayName;
        this.location = location;
        this.radius = radius;
        this.captureDuration = captureDuration;
        this.lockAfterCapture = lockAfterCapture;
        this.rewardType = rewardType;
    }

    @ArenaOption(name = "display-name", description = "Optional display name for this area.")
    private String displayName;

    @ArenaOption(name = "location", description = "Center location of the zone.", required = true)
    private PositionWithRotation location;

    @ArenaOption(name = "radius", description = "Capture radius around the center.", required = true)
    private double radius = 5D;

    @ArenaOption(name = "capture-duration", description = "How long a team must hold the area to capture it.", required = true)
    private Duration captureDuration;

    @ArenaOption(name = "lock-after-capture", description = "Whether the area can only be captured once.")
    private boolean lockAfterCapture;

    private RewardType rewardType;

    public String getDisplayName() {
        return this.displayName;
    }

    public PositionWithRotation getLocation() {
        return this.location;
    }

    public double getRadius() {
        return this.radius;
    }

    public Duration getCaptureDuration() {
        return this.captureDuration;
    }

    public boolean lockAfterCapture() {
        return this.lockAfterCapture;
    }

    public RewardType getRewardType() {
        return this.rewardType;
    }

    /**
     * Returns copies of the provided definitions with randomized reward types.
     * All reward types are used once before any repeats.
     *
     * @param definitions definitions to assign rewards to
     * @return map of randomized definitions preserving original order
     */
    public static Map<String, DominationAreaDefinition> randomizedRewards(Map<String, DominationAreaDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return Map.of();
        }

        RewardType[] rewardTypes = RewardType.values();
        if (rewardTypes.length == 0) {
            return Map.copyOf(definitions);
        }

        Map<String, DominationAreaDefinition> randomized = new LinkedHashMap<>();
        List<RewardType> pool = new ArrayList<>(Arrays.asList(rewardTypes));
        ThreadLocalRandom random = ThreadLocalRandom.current();

        definitions.forEach((id, definition) -> {
            if (definition == null) {
                return;
            }

            if (pool.isEmpty()) {
                pool.addAll(Arrays.asList(rewardTypes));
            }

            RewardType selected = pool.remove(random.nextInt(pool.size()));
            randomized.put(id, definition.withRewardType(selected));
        });

        return randomized;
    }

    public DominationAreaDefinition withRewardType(RewardType rewardType) {
        return new DominationAreaDefinition(
                this.displayName,
                this.location,
                this.radius,
                this.captureDuration,
                this.lockAfterCapture,
                rewardType
        );
    }

    public DominationAreaDefinition shifted(double dx, double dy, double dz) {
        PositionWithRotation shiftedLocation = this.location == null ? null : this.location.shifted(dx, dy, dz);
        return new DominationAreaDefinition(
                this.displayName,
                shiftedLocation,
                this.radius,
                this.captureDuration,
                this.lockAfterCapture,
                this.rewardType
        );
    }
}
