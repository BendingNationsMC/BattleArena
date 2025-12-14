package org.battleplugins.arena.queue;

import org.battleplugins.arena.Arena;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Service exposed by queue-enabled modules so core commands
 * can interact with proxy queues without a hard dependency.
 */
public interface QueueService {
    String MODULE_ID = "queue-system";

    /**
     * Attempts to remove the player from any active queue.
     *
     * @param arena  the arena whose command triggered the action
     * @param player the player issuing the leave command
     * @return true if the player was queued and was dequeued successfully
     */
    boolean leaveQueue(Arena arena, Player player);

    /**
     * Returns how long the player has been waiting in a queue, if applicable.
     *
     * @param playerId the player ID to query
     * @return the duration the player has been queued for, if queued
     */
    default Optional<Duration> getQueueDuration(UUID playerId) {
        return Optional.empty();
    }
}
