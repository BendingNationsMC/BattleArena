package org.battleplugins.arena.queue;

import org.battleplugins.arena.Arena;
import org.bukkit.entity.Player;

public interface QueueService {
    boolean leaveQueue(Arena arena, Player player);
}
