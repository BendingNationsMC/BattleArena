package org.battleplugins.arena.module.ranked;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.battleplugins.arena.proxy.Elements;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

/**
 * PlaceholderAPI expansion for ranked/ELO placeholders.
 * <p>
 * Supported placeholders:
 *   %ranked_elo_<element>%    - the player's ELO for the element
 *   %ranked_rank_<element>%   - the player's rank for the element (if leaderboards are enabled)
 *   %ranked_elo_global%       - average ELO across all elements
 *   %ranked_rank_global%      - rank by average ELO (if enabled)
 *   %<element>_rank{n}%       - username at leaderboard position n for <element> (e.g. %air_rank1%, %fire_rank{5}%)
 *   %global_rank{n}%          - username at global leaderboard position n
 */
public class RankedPlaceholderExpansion extends PlaceholderExpansion {
    private final RankedService rankedService;

    public RankedPlaceholderExpansion(RankedService rankedService) {
        this.rankedService = rankedService;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "ranked";
    }

    @Override
    public @NotNull String getAuthor() {
        return "BattlePlugins";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0.0";
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (rankedService == null) {
            return "";
        }

        String lowered = params.toLowerCase(Locale.ROOT);
        UUID playerId = player == null ? null : player.getUniqueId();

        if (lowered.startsWith("elo_")) {
            String elementName = lowered.substring("elo_".length());
            if (isGlobal(elementName)) {
                if (!rankedService.getConfig().isUseGlobalAverage()) {
                    return "";
                }
                return formatElo(rankedService.getAverageElo(playerId));
            }

            Elements element = parseElement(elementName);
            if (element == null) {
                return "";
            }

            return formatElo(rankedService.getElo(playerId, element));
        }

        if (lowered.startsWith("rank_")) {
            String elementName = lowered.substring("rank_".length());
            if (isGlobal(elementName)) {
                if (playerId == null) {
                    return "";
                }
                Long rank = rankedService.getGlobalRank(playerId);
                return rank == null ? "" : String.valueOf(rank);
            }

            Elements element = parseElement(elementName);
            if (element == null) {
                return "";
            }

            if (playerId == null) {
                return "";
            }
            Long rank = rankedService.getRank(playerId, element);
            return rank == null ? "" : String.valueOf(rank);
        }

        if (lowered.contains("_rank")) {
            String resolved = handleLeaderboardLookup(lowered);
            if (resolved != null) {
                return resolved;
            }
        }

        return "";
    }

    private boolean isGlobal(String value) {
        return value.equals("global") || value.equals("average");
    }

    private Elements parseElement(String raw) {
        try {
            return Elements.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String formatElo(double value) {
        return String.format("%.0f", value);
    }

    private @Nullable String handleLeaderboardLookup(String placeholder) {
        int idx = placeholder.indexOf("_rank");
        if (idx <= 0) {
            return null;
        }

        String elementToken = placeholder.substring(0, idx);
        String suffix = placeholder.substring(idx + "_rank".length());
        suffix = suffix.replace("{", "").replace("}", "");
        if (suffix.startsWith("_")) {
            suffix = suffix.substring(1);
        }

        if (suffix.isEmpty()) {
            return "";
        }

        int position;
        try {
            position = Integer.parseInt(suffix);
        } catch (NumberFormatException ex) {
            return "";
        }

        if (position <= 0) {
            return "";
        }

        UUID targetId;
        if (isGlobal(elementToken)) {
            targetId = rankedService.getGlobalPlayerAtRank(position);
        } else {
            Elements element = parseElement(elementToken);
            if (element == null) {
                return "";
            }
            targetId = rankedService.getPlayerAtRank(element, position);
        }

        if (targetId == null) {
            return "";
        }

        OfflinePlayer offline = Bukkit.getOfflinePlayer(targetId);
        if (offline == null) {
            return "";
        }

        String name = offline.getName();
        return name != null ? name : targetId.toString();
    }
}
