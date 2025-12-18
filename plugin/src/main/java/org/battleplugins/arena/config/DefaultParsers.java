package org.battleplugins.arena.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.battleplugins.arena.Arena;
import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.competition.CompetitionType;
import org.battleplugins.arena.competition.phase.CompetitionPhaseType;
import org.battleplugins.arena.config.context.EventContextProvider;
import org.battleplugins.arena.config.context.OptionContextProvider;
import org.battleplugins.arena.config.context.PhaseContextProvider;
import org.battleplugins.arena.config.context.VictoryConditionContextProvider;
import org.battleplugins.arena.util.CustomEffect;
import org.battleplugins.arena.util.IntRange;
import org.battleplugins.arena.util.PositionWithRotation;
import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.awt.*;
import java.time.Duration;
import java.util.function.Function;

final class DefaultParsers {

    static void register() {
        ArenaConfigParser.registerContextProvider(EventContextProvider.class, new EventContextProvider());
        ArenaConfigParser.registerContextProvider(OptionContextProvider.class, new OptionContextProvider());
        ArenaConfigParser.registerContextProvider(PhaseContextProvider.class, new PhaseContextProvider());
        ArenaConfigParser.registerContextProvider(VictoryConditionContextProvider.class, new VictoryConditionContextProvider());

        ArenaConfigParser.registerProvider(Duration.class, new DurationParser());
        ArenaConfigParser.registerProvider(ItemStack.class, new ItemStackParser());
        ArenaConfigParser.registerProvider(PotionEffect.class, new PotionEffectParser());
        ArenaConfigParser.registerProvider(PositionWithRotation.class, configValue -> {
            if (!(configValue instanceof ConfigurationSection positionSection)) {
                return null;
            }

            double x = positionSection.getDouble("x");
            double y = positionSection.getDouble("y");
            double z = positionSection.getDouble("z");
            float yaw = (float) positionSection.getDouble("yaw");
            float pitch = (float) positionSection.getDouble("pitch");
            return new PositionWithRotation(x, y, z, yaw, pitch);
        });

        ArenaConfigParser.registerProvider(Arena.class, parseString(BattleArena.getInstance()::getArena));
        ArenaConfigParser.registerProvider(CompetitionType.class, parseString(CompetitionType::get));
        ArenaConfigParser.registerProvider(CompetitionPhaseType.class, parseString(CompetitionPhaseType::get));
        ArenaConfigParser.registerProvider(IntRange.class, configValue -> {
            // If config value is not a string or number, return null
            if (!(configValue instanceof String || configValue instanceof Number)) {
                return null;
            }

            String value = configValue.toString();
            if (value.contains("-")) {
                String[] split = value.split("-");
                return new IntRange(Integer.parseInt(split[0]), Integer.parseInt(split[1]));
            } else {
                if (value.endsWith("+")) {
                    return IntRange.minInclusive(Integer.parseInt(value.substring(0, value.length() - 1)));
                } else if (value.startsWith("+")) {
                    return IntRange.maxInclusive(Integer.parseInt(value.substring(1)));
                }

                return new IntRange(Integer.parseInt(value));
            }
        });

        ArenaConfigParser.registerProvider(Color.class, new ColorParser());
        ArenaConfigParser.registerProvider(Component.class, configValue -> {
            if (!(configValue instanceof String value)) {
                return null;
            }

            return MiniMessage.miniMessage().deserialize(value);
        });

        ArenaConfigParser.registerProvider(BlockData.class, parseString(Bukkit::createBlockData));
        ArenaConfigParser.registerProvider(CustomEffect.class, new CustomEffectParser<>());
    }

    private static <T> ArenaConfigParser.Parser<T> parseString(Function<String, T> parser) {
        return configValue -> {
            if (!(configValue instanceof String value)) {
                return null;
            }

            return parser.apply(value);
        };
    }
}
