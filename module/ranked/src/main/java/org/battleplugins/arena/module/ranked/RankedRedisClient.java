package org.battleplugins.arena.module.ranked;

import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.proxy.Elements;
import org.slf4j.Logger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Thin Redis wrapper responsible for all ranked/ELO storage.
 * Keys are namespaced as:
 *   <prefix>:elo:<uuid>:<element>  -> string ELO value
 *   <prefix>:leaderboard:<element> -> sorted set for element-specific ranking
 *   <prefix>:leaderboard:global    -> sorted set for average/global ranking
 */
public class RankedRedisClient {
    private final BattleArena plugin;
    private final RankedConfig config;
    private final Logger log;
    private final JedisPool pool;
    private final String prefix;

    public RankedRedisClient(BattleArena plugin, RankedConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.log = plugin.getSLF4JLogger();
        this.prefix = config.getRedisPrefix();

        var main = plugin.getMainConfig();
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(0);

        if (main.getRedisPassword() != null && !main.getRedisPassword().isEmpty()) {
            this.pool = new JedisPool(
                    poolConfig,
                    main.getRedisHost(),
                    main.getRedisPort(),
                    2000,
                    main.getRedisPassword(),
                    main.getRedisDatabase()
            );
        } else {
            this.pool = new JedisPool(
                    poolConfig,
                    main.getRedisHost(),
                    main.getRedisPort(),
                    2000,
                    null,
                    main.getRedisDatabase()
            );
        }
    }

    public double getElo(UUID playerId, Elements element, double fallback) {
        try (Jedis jedis = pool.getResource()) {
            String value = jedis.get(eloKey(playerId, element));
            if (value == null) {
                return fallback;
            }

            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException ex) {
                log.warn("Invalid ELO value '{}' for {} {}. Resetting to fallback.", value, playerId, element.name());
                jedis.del(eloKey(playerId, element));
                return fallback;
            }
        }
    }

    public Map<Elements, Double> getAllElo(UUID playerId, double fallback) {
        Map<Elements, Double> values = new EnumMap<>(Elements.class);
        try (Jedis jedis = pool.getResource()) {
            Pipeline pipeline = jedis.pipelined();
            Map<Elements, Response<String>> responses = new EnumMap<>(Elements.class);
            for (Elements element : Elements.values()) {
                responses.put(element, pipeline.get(eloKey(playerId, element)));
            }

            pipeline.sync();
            for (Map.Entry<Elements, Response<String>> entry : responses.entrySet()) {
                String value = entry.getValue().get();
                if (value == null) {
                    values.put(entry.getKey(), fallback);
                } else {
                    try {
                        values.put(entry.getKey(), Double.parseDouble(value));
                    } catch (NumberFormatException ex) {
                        values.put(entry.getKey(), fallback);
                    }
                }
            }
        }
        return values;
    }

    public void setElo(UUID playerId, Elements element, double value) {
        String key = eloKey(playerId, element);
        try (Jedis jedis = pool.getResource()) {
            jedis.set(key, Double.toString(value));
            if (config.isMaintainLeaderboards()) {
                jedis.zadd(leaderboardKey(element), value, playerId.toString());
            }
        }
    }

    public Long getRank(UUID playerId, Elements element) {
        if (!config.isMaintainLeaderboards()) {
            return null;
        }

        try (Jedis jedis = pool.getResource()) {
            Long rank = jedis.zrevrank(leaderboardKey(element), playerId.toString());
            return rank == null ? null : rank + 1;
        }
    }

    public void updateGlobalLeaderboard(UUID playerId, double averageElo) {
        if (!config.isMaintainLeaderboards() || !config.isUseGlobalAverage()) {
            return;
        }

        try (Jedis jedis = pool.getResource()) {
            jedis.zadd(globalLeaderboardKey(), averageElo, playerId.toString());
        }
    }

    public Long getGlobalRank(UUID playerId) {
        if (!config.isMaintainLeaderboards() || !config.isUseGlobalAverage()) {
            return null;
        }

        try (Jedis jedis = pool.getResource()) {
            Long rank = jedis.zrevrank(globalLeaderboardKey(), playerId.toString());
            return rank == null ? null : rank + 1;
        }
    }

    public UUID getPlayerAtRank(Elements element, int rank) {
        if (!config.isMaintainLeaderboards() || rank <= 0) {
            return null;
        }

        try (Jedis jedis = pool.getResource()) {
            long idx = rank - 1L;
            List<String> ids = jedis.zrevrange(leaderboardKey(element), idx, idx);
            if (ids == null || ids.isEmpty()) {
                return null;
            }

            return parseUuid(ids.iterator().next());
        }
    }

    public UUID getGlobalPlayerAtRank(int rank) {
        if (!config.isMaintainLeaderboards() || !config.isUseGlobalAverage() || rank <= 0) {
            return null;
        }

        try (Jedis jedis = pool.getResource()) {
            long idx = rank - 1L;
            List<String> ids = jedis.zrevrange(globalLeaderboardKey(), idx, idx);
            if (ids == null || ids.isEmpty()) {
                return null;
            }

            return parseUuid(ids.iterator().next());
        }
    }

    private UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public void close() {
        try {
            this.pool.close();
        } catch (Exception ignored) {
        }
    }

    private String eloKey(UUID playerId, Elements element) {
        return prefix + ":elo:" + playerId + ":" + element.name().toLowerCase();
    }

    private String leaderboardKey(Elements element) {
        return prefix + ":leaderboard:" + element.name().toLowerCase();
    }

    private String globalLeaderboardKey() {
        return prefix + ":leaderboard:global";
    }
}
