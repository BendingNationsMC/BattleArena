package org.battleplugins.arena.queue;

import org.battleplugins.arena.Arena;
import org.bukkit.entity.Player;

/**
 * Service exposed by queue-enabled modules so core commands
 * can interact with proxy queues without a hard dependency.
 */
public interface QueueService {

    /**
     * Attempts to remove the player from any active queue.
     *
     * @param arena  the arena whose command triggered the action
     * @param player the player issuing the leave command
     * @return true if the player was queued and was dequeued successfully
     */
    boolean leaveQueue(Arena arena, Player player);
}
