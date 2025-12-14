package org.battleplugins.arena.duel;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks arena/map selections made through the global duel GUI
 * so the duels module can respect the chosen target and map.
 */
public final class DuelSelectionRegistry {
    private static final long EXPIRATION_MILLIS = Duration.ofSeconds(30).toMillis();

    private final Map<UUID, DuelSelection> selections = new ConcurrentHashMap<>();

    public void storeSelection(UUID requesterId, UUID targetId, String arenaName, String mapName) {
        this.selections.put(requesterId, new DuelSelection(
                requesterId,
                targetId,
                arenaName.toLowerCase(Locale.ROOT),
                mapName,
                System.currentTimeMillis() + EXPIRATION_MILLIS
        ));
    }

    public Optional<DuelSelection> consume(UUID requesterId, UUID targetId, String arenaName) {
        DuelSelection selection = this.selections.get(requesterId);
        if (selection == null) {
            return Optional.empty();
        }

        if (selection.expired()) {
            this.selections.remove(requesterId, selection);
            return Optional.empty();
        }

        if (!selection.matches(targetId, arenaName)) {
            return Optional.empty();
        }

        this.selections.remove(requesterId, selection);
        return Optional.of(selection);
    }

    public record DuelSelection(UUID requesterId, UUID targetId, String arenaName, String mapName, long expiresAt) {
        public boolean matches(UUID expectedTarget, String expectedArena) {
            return this.targetId.equals(expectedTarget)
                    && this.arenaName.equalsIgnoreCase(expectedArena);
        }

        public boolean expired() {
            return System.currentTimeMillis() > this.expiresAt;
        }
    }
}
