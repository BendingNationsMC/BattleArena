package org.battleplugins.arena.module.storm;

import org.battleplugins.arena.messages.Message;

import static org.battleplugins.arena.messages.Messages.info;
import static org.battleplugins.arena.messages.Messages.success;

/**
 * Storm-specific messages, automatically exposed to configuration.
 */
public final class StormMessages {

    private StormMessages() {
    }

    public static final Message STORM_STARTED = info("storm-started", "<primary>A storm is forming around the waitroom! Stay within the safe zone.</primary>");
    public static final Message STORM_WAVE_STARTED = info("storm-wave-started", "Storm wave <secondary>{}</secondary> is closing in. Safe radius: <secondary>{} blocks</secondary>.");
    public static final Message STORM_COMPLETE = success("storm-complete", "The storm has fully converged.");
}
