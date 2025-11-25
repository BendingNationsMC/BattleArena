package org.battleplugins.arena.editor.stage;

import net.kyori.adventure.text.Component;
import org.battleplugins.arena.config.DurationParser;
import org.battleplugins.arena.config.ParseException;
import org.battleplugins.arena.editor.WizardStage;
import org.battleplugins.arena.editor.context.MapCreateContext;
import org.battleplugins.arena.messages.Messages;
import org.battleplugins.arena.module.domination.config.DominationAreaDefinition;
import org.battleplugins.arena.util.InteractionInputs;
import org.battleplugins.arena.util.PositionWithRotation;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

public class DominationAreaInputStage implements WizardStage<MapCreateContext> {

    @Override
    public void enter(MapCreateContext context) {
        this.sendOverview(context);
        this.prompt(context);
    }

    private void sendOverview(MapCreateContext context) {
        context.inform(Messages.MAP_MANAGE_DOMINATION_AREAS);
        this.listAreas(context);
    }

    private void prompt(MapCreateContext context) {
        Player player = context.getPlayer();
        Messages.MAP_MANAGE_DOMINATION_PROMPT.send(player);

        new InteractionInputs.ChatInput(player, Messages.INVALID_INPUT.withContext("add, remove, list, done")) {
            @Override
            public void onChatInput(String input) {
                String normalized = input.trim().toLowerCase(Locale.ROOT);
                switch (normalized) {
                    case "done" -> context.advanceStage();
                    case "list" -> {
                        listAreas(context);
                        prompt(context);
                    }
                    case "remove" -> promptRemove(context);
                    case "add" -> beginAddFlow(context);
                    default -> {
                        Messages.INVALID_INPUT.withContext("add, remove, list, done").send(player);
                        prompt(context);
                    }
                }
            }

            @Override
            public boolean isValidChatInput(String input) {
                return super.isValidChatInput(input) && !input.startsWith("/");
            }
        }.bind(context);
    }

    private void listAreas(MapCreateContext context) {
        Player player = context.getPlayer();
        Map<String, DominationAreaDefinition> areas = context.getDominationAreas();
        if (areas.isEmpty()) {
            Messages.MAP_DOMINATION_AREA_NONE.send(player);
            return;
        }

        player.sendMessage(Component.text("Configured domination areas:", Messages.PRIMARY_COLOR));
        areas.forEach((id, definition) -> {
            String displayName = definition.getDisplayName() == null ? id : definition.getDisplayName();
            String summary = "- " + id + " (" + displayName + ")";
            player.sendMessage(Component.text(summary, Messages.SECONDARY_COLOR));
        });
    }

    private void promptRemove(MapCreateContext context) {
        Player player = context.getPlayer();
        Messages.MAP_DOMINATION_ENTER_REMOVE_ID.send(player);

        new InteractionInputs.ChatInput(player, Messages.INVALID_INPUT.withContext("domination id")) {
            @Override
            public void onChatInput(String input) {
                boolean removed = context.removeDominationArea(input.trim());
                if (removed) {
                    Messages.MAP_DOMINATION_AREA_REMOVED.send(player, input.trim());
                } else {
                    Messages.MAP_DOMINATION_AREA_UNKNOWN.send(player, input.trim());
                }

                prompt(context);
            }

            @Override
            public boolean isValidChatInput(String input) {
                return super.isValidChatInput(input) && !input.startsWith("/");
            }
        }.bind(context);
    }

    private void beginAddFlow(MapCreateContext context) {
        Player player = context.getPlayer();
        Location location = player.getLocation().clone();
        this.promptAreaId(context, location);
    }

    private void promptAreaId(MapCreateContext context, Location location) {
        Player player = context.getPlayer();
        Messages.MAP_DOMINATION_ENTER_ID.send(player);

        new InteractionInputs.ChatInput(player, Messages.MAP_DOMINATION_INVALID_ID) {
            @Override
            public void onChatInput(String input) {
                String id = input.trim();
                if (id.isEmpty()) {
                    Messages.MAP_DOMINATION_INVALID_ID.send(player);
                    promptAreaId(context, location);
                    return;
                }

                if (context.hasDominationArea(id)) {
                    Messages.MAP_DOMINATION_ID_EXISTS.send(player, id);
                    promptAreaId(context, location);
                    return;
                }

                promptDisplayName(context, location, id);
            }

            @Override
            public boolean isValidChatInput(String input) {
                return super.isValidChatInput(input) && !input.startsWith("/");
            }
        }.bind(context);
    }

    private void promptDisplayName(MapCreateContext context, Location location, String id) {
        Player player = context.getPlayer();
        Messages.MAP_DOMINATION_ENTER_DISPLAY_NAME.send(player);

        new InteractionInputs.ChatInput(player, null) {
            @Override
            public void onChatInput(String input) {
                String trimmed = input.trim();
                String displayName = trimmed.equalsIgnoreCase("skip") || trimmed.isEmpty() ? null : trimmed;
                promptRadius(context, location, id, displayName);
            }

            @Override
            public boolean isValidChatInput(String input) {
                return super.isValidChatInput(input);
            }
        }.bind(context);
    }

    private void promptRadius(MapCreateContext context, Location location, String id, String displayName) {
        Player player = context.getPlayer();
        Messages.MAP_DOMINATION_ENTER_RADIUS.send(player);

        new InteractionInputs.ChatInput(player, Messages.MAP_DOMINATION_INVALID_RADIUS) {
            @Override
            public void onChatInput(String input) {
                double radius;
                try {
                    radius = Double.parseDouble(input.trim());
                } catch (NumberFormatException e) {
                    Messages.MAP_DOMINATION_INVALID_RADIUS.send(player);
                    promptRadius(context, location, id, displayName);
                    return;
                }

                if (radius <= 0) {
                    Messages.MAP_DOMINATION_INVALID_RADIUS.send(player);
                    promptRadius(context, location, id, displayName);
                    return;
                }

                promptDuration(context, location, id, displayName, radius);
            }

            @Override
            public boolean isValidChatInput(String input) {
                return super.isValidChatInput(input) && !input.startsWith("/");
            }
        }.bind(context);
    }

    private void promptDuration(MapCreateContext context, Location location, String id, String displayName, double radius) {
        Player player = context.getPlayer();
        Messages.MAP_DOMINATION_ENTER_DURATION.send(player);

        new InteractionInputs.ChatInput(player, Messages.MAP_DOMINATION_INVALID_DURATION) {
            @Override
            public void onChatInput(String input) {
                Duration duration;
                try {
                    duration = DurationParser.deserializeSingular(input.trim());
                } catch (ParseException e) {
                    Messages.MAP_DOMINATION_INVALID_DURATION.send(player);
                    promptDuration(context, location, id, displayName, radius);
                    return;
                }

                promptLock(context, location, id, displayName, radius, duration);
            }

            @Override
            public boolean isValidChatInput(String input) {
                return super.isValidChatInput(input) && !input.startsWith("/");
            }
        }.bind(context);
    }

    private void promptLock(MapCreateContext context, Location location, String id, String displayName, double radius, Duration duration) {
        Player player = context.getPlayer();
        Messages.MAP_DOMINATION_ENTER_LOCK.send(player);

        new InteractionInputs.ChatInput(player, Messages.MAP_DOMINATION_INVALID_LOCK) {
            @Override
            public void onChatInput(String input) {
                String normalized = input.trim().toLowerCase(Locale.ROOT);
                boolean lock;
                if (normalized.equals("true") || normalized.equals("yes") || normalized.equals("y")) {
                    lock = true;
                } else if (normalized.equals("false") || normalized.equals("no") || normalized.equals("n")) {
                    lock = false;
                } else {
                    Messages.MAP_DOMINATION_INVALID_LOCK.send(player);
                    promptLock(context, location, id, displayName, radius, duration);
                    return;
                }

                DominationAreaDefinition definition = new DominationAreaDefinition(
                        displayName,
                        new PositionWithRotation(location),
                        radius,
                        duration,
                        lock,
                        null
                );

                context.addDominationArea(id, definition);
                Messages.MAP_DOMINATION_AREA_ADDED.send(player, id);
                prompt(context);
            }

            @Override
            public boolean isValidChatInput(String input) {
                return super.isValidChatInput(input) && !input.startsWith("/");
            }
        }.bind(context);
    }
}
