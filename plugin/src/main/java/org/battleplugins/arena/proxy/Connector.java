package org.battleplugins.arena.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.competition.map.LiveCompetitionMap;
import net.kyori.adventure.text.Component;
import org.battleplugins.arena.messages.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class Connector {
    private static final Logger log = LoggerFactory.getLogger(Connector.class);
    private final BattleArena plugin;

    private JedisPool jedisPool;
    private Thread subscriberThread;
    private JedisPubSub subscriber;
    private volatile boolean running;
    private final String redisChannel;

    public Connector(BattleArena plugin) {
        this.plugin = plugin;
        this.redisChannel = plugin.getMainConfig().getRedisChannel();
    }

    public void connect() {
        log.info("Connecting BattleArena proxy connector to Redis channel '{}'.", redisChannel);

        var cfg = plugin.getMainConfig();

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(0);

        if (cfg.getRedisPassword() != null && !cfg.getRedisPassword().isEmpty()) {
            this.jedisPool = new JedisPool(
                    poolConfig,
                    cfg.getRedisHost(),
                    cfg.getRedisPort(),
                    2000,
                    cfg.getRedisPassword(),
                    cfg.getRedisDatabase()
            );
        } else {
            this.jedisPool = new JedisPool(
                    poolConfig,
                    cfg.getRedisHost(),
                    cfg.getRedisPort(),
                    2000,
                    null,
                    cfg.getRedisDatabase()
            );
        }

        this.running = true;

        this.subscriber = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                if (!redisChannel.equals(channel)) {
                    return;
                }

                log.info("Received message from Redis proxy channel: {}", message);

                // Preserve async handling semantics from the old TCP loop.
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        handleMessages(message);
                    } catch (IOException e) {
                        log.warn("Error handling proxy message", e);
                    }
                });
            }
        };

        this.subscriberThread = new Thread(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                log.info("Subscribing to Redis channel '{}'.", redisChannel);
                jedis.subscribe(subscriber, redisChannel);
            } catch (Exception ex) {
                if (running) {
                    log.warn("Redis subscriber loop for BattleArena connector stopped unexpectedly.", ex);
                } else {
                    log.info("Redis subscriber loop for BattleArena connector stopped.");
                }
            }
        }, "BattleArena-Redis-Subscriber");

        this.subscriberThread.setDaemon(true);
        this.subscriberThread.start();
    }

    public void sendToRouter(String msg) {
        if (!running || jedisPool == null) {
            log.warn("Attempted to send proxy message but Redis connector is not running.");
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                long receivers = jedis.publish(redisChannel, msg);
                log.debug("Published message to Redis channel {} ({} receivers): {}", redisChannel, receivers, msg);
            } catch (Exception ex) {
                log.warn("Failed to publish proxy message to Redis", ex);
            }
        });
    }

    private void disconnect() {
        log.info("Shutting down BattleArena Redis proxy connector on channel '{}'.", redisChannel);
        running = false;

        if (subscriber != null) {
            try {
                subscriber.unsubscribe();
            } catch (Exception ignored) {
            }
            subscriber = null;
        }

        if (subscriberThread != null) {
            subscriberThread.interrupt();
            subscriberThread = null;
        }

        if (jedisPool != null) {
            jedisPool.close();
            jedisPool = null;
        }
    }

    void handleMessages(String message) throws IOException {
        JsonObject object = JsonParser.parseString(message).getAsJsonObject();

        String type = object.get("type").getAsString();

        switch (type.toLowerCase()) {
            case "sync_config": {
                // Proxy host doesn't sync remote configs; it is the source of truth.
                if (plugin.getMainConfig().isProxyHost()) {
                    return;
                }

                log.info("Sync config proxy hosted!");

                // For backwards compatibility, fall back to "data" as maps only.
                if (object.has("maps")) {
                    byte[] mapsZip = Base64.getDecoder().decode(object.get("maps").getAsString());
                    Path mapsPath = plugin.getMapsPath();
                    unzipToDirectory(mapsZip, mapsPath);
                }

                if (object.has("arenas")) {
                    byte[] arenasZip = Base64.getDecoder().decode(object.get("arenas").getAsString());
                    Path arenasPath = plugin.getDataFolder().toPath().resolve("arenas");
                    unzipToDirectory(arenasZip, arenasPath);
                }

                for (List<LiveCompetitionMap> maps : plugin.getArenaMaps().values()) {
                    maps.removeIf(LiveCompetitionMap::isRemote);
                }

                plugin.loadArenaMaps(true);
                break;
            }
            case "sync_request": {
                // Only the proxy host should answer sync requests.
                if (!plugin.getMainConfig().isProxyHost()) {
                    return;
                }

                Path mapsPath = plugin.getMapsPath();
                if (!Files.exists(mapsPath)) {
                    log.warn("Received sync_request but maps directory {} does not exist.", mapsPath);
                    return;
                }

                Path arenasPath = plugin.getDataFolder().toPath().resolve("arenas");

                try {
                    JsonObject response = new JsonObject();
                    response.addProperty("type", "sync_config");

                    // If the request specified an origin, echo it back so the response
                    // is routed directly to the requester instead of broadcast.
                    if (object.has("origin")) {
                        response.addProperty("origin", object.get("origin").getAsString());
                    }

                    String mapsData = zipFolderToBase64(mapsPath);
                    response.addProperty("maps", mapsData);

                    if (Files.exists(arenasPath)) {
                        String arenasData = zipFolderToBase64(arenasPath);
                        response.addProperty("arenas", arenasData);
                    }

                    sendToRouter(response.toString());
                    log.info("Synced arenas and maps to proxy clients from {}, {}.", arenasPath, mapsPath);
            } catch (IOException e) {
                    log.warn("Failed to respond to sync_request: {}", e.getMessage());
                }

                break;
            }
            // Sent whenever a non-host server wants to enqueue or dequeue a player for a proxy-wide queue.
            case "queue_join": {
                if (!plugin.getMainConfig().isProxyHost()) {
                    return;
                }

                String arenaName = object.get("arena").getAsString();
                final String duelMapName = object.has("map") ? object.get("map").getAsString() : null;
                String mapName = object.has("map") ? object.get("map").getAsString() : null;
                org.battleplugins.arena.Arena arena = plugin.getArena(arenaName);
                if (arena == null) {
                    log.warn("Received queue_join for unknown arena '{}'.", arenaName);
                    return;
                }

                String origin = object.has("origin") ? object.get("origin").getAsString() : "";

                JsonObject playerObject = object.getAsJsonObject("player");

                // Build a SerializedPlayer instance from the JSON payload.
                String uuid = playerObject.get("uuid").getAsString();
                org.battleplugins.arena.proxy.SerializedPlayer serializedPlayer =
                        new org.battleplugins.arena.proxy.SerializedPlayer(uuid);

                if (playerObject.has("elements") && playerObject.get("elements").isJsonArray()) {
                    playerObject.getAsJsonArray("elements").forEach(elementEl -> {
                        try {
                            org.battleplugins.arena.proxy.Elements element = org.battleplugins.arena.proxy.Elements.valueOf(elementEl.getAsString());
                            serializedPlayer.getElements().add(element);
                        } catch (IllegalArgumentException ignored) {
                            // Ignore unknown elements
                        }
                    });
                }

                if (playerObject.has("abilities") && playerObject.get("abilities").isJsonObject()) {
                    JsonObject abilitiesObject = playerObject.getAsJsonObject("abilities");
                    abilitiesObject.entrySet().forEach(entry -> {
                        try {
                            int slot = Integer.parseInt(entry.getKey());
                            serializedPlayer.getAbilities().put(slot, entry.getValue().getAsString());
                        } catch (NumberFormatException ignored) {
                            // Ignore invalid ability slots
                        }
                    });
                }

                if (playerObject.has("origin") && playerObject.get("origin").isJsonPrimitive()) {
                    serializedPlayer.setOrigin(playerObject.get("origin").getAsString());
                } else if (!origin.isEmpty()) {
                    serializedPlayer.setOrigin(origin);
                }

                Bukkit.getScheduler().runTask(plugin, () ->
                        org.bukkit.Bukkit.getPluginManager().callEvent(
                                new org.battleplugins.arena.proxy.ProxyQueueJoinEvent(arena, origin, serializedPlayer)
                        )
                );
                break;
            }
            case "queue_leave": {
                if (!plugin.getMainConfig().isProxyHost()) {
                    return;
                }

                String uuid = object.get("uuid").getAsString();
                String origin = object.has("origin") ? object.get("origin").getAsString() : "";

                Bukkit.getScheduler().runTask(plugin, () ->
                        org.bukkit.Bukkit.getPluginManager().callEvent(
                                new org.battleplugins.arena.proxy.ProxyQueueLeaveEvent(origin, uuid)
                        )
                );

                break;
            }
            // Sent whenever the client wants to start a duel on the proxy host
            case "duel_req": {
                if (!plugin.getMainConfig().isProxyHost()) {
                    return;
                }

                String arenaName = object.get("arena").getAsString();
                final String duelMapName = object.has("map") ? object.get("map").getAsString() : null;
                JsonElement requesterElement = object.get("requester");
                JsonElement targetElement = object.get("target");

                org.battleplugins.arena.Arena arena = plugin.getArena(arenaName);
                if (arena == null) {
                    log.warn("Received proxy duel request for unknown arena '{}'.", arenaName);
                    return;
                }

                org.battleplugins.arena.proxy.SerializedPlayer requester;
                org.battleplugins.arena.proxy.SerializedPlayer target;

                // Backwards compatible format: requester/target as UUID strings
                if (requesterElement.isJsonPrimitive()) {
                    String requesterId = requesterElement.getAsString();
                    requester = new org.battleplugins.arena.proxy.SerializedPlayer(requesterId);
                } else {
                    JsonObject requesterObject = requesterElement.getAsJsonObject();
                    String requesterId = requesterObject.get("uuid").getAsString();
                    requester = new org.battleplugins.arena.proxy.SerializedPlayer(requesterId);

                    if (requesterObject.has("elements") && requesterObject.get("elements").isJsonArray()) {
                        requesterObject.getAsJsonArray("elements").forEach(elementEl -> {
                            try {
                                org.battleplugins.arena.proxy.Elements element = org.battleplugins.arena.proxy.Elements.valueOf(elementEl.getAsString());
                                requester.getElements().add(element);
                            } catch (IllegalArgumentException ignored) {
                                // Ignore unknown elements
                            }
                        });
                    }

                    if (requesterObject.has("abilities") && requesterObject.get("abilities").isJsonObject()) {
                        JsonObject abilitiesObject = requesterObject.getAsJsonObject("abilities");
                        abilitiesObject.entrySet().forEach(entry -> {
                            try {
                                int slot = Integer.parseInt(entry.getKey());
                                requester.getAbilities().put(slot, entry.getValue().getAsString());
                            } catch (NumberFormatException ignored) {
                                // Ignore invalid ability slots
                            }
                        });
                    }
                }

                if (targetElement.isJsonPrimitive()) {
                    String targetId = targetElement.getAsString();
                    target = new org.battleplugins.arena.proxy.SerializedPlayer(targetId);
                } else {
                    JsonObject targetObject = targetElement.getAsJsonObject();
                    String targetId = targetObject.get("uuid").getAsString();
                    target = new org.battleplugins.arena.proxy.SerializedPlayer(targetId);

                    if (targetObject.has("elements") && targetObject.get("elements").isJsonArray()) {
                        targetObject.getAsJsonArray("elements").forEach(elementEl -> {
                            try {
                                org.battleplugins.arena.proxy.Elements element = org.battleplugins.arena.proxy.Elements.valueOf(elementEl.getAsString());
                                target.getElements().add(element);
                            } catch (IllegalArgumentException ignored) {
                                // Ignore unknown elements
                            }
                        });
                    }

                    if (targetObject.has("abilities") && targetObject.get("abilities").isJsonObject()) {
                        JsonObject abilitiesObject = targetObject.getAsJsonObject("abilities");
                        abilitiesObject.entrySet().forEach(entry -> {
                            try {
                                int slot = Integer.parseInt(entry.getKey());
                                target.getAbilities().put(slot, entry.getValue().getAsString());
                            } catch (NumberFormatException ignored) {
                                // Ignore invalid ability slots
                            }
                        });
                    }
                }

                java.util.Set<java.util.UUID> requesterParty = parseRoster(object, "requesterParty", requester);
                java.util.Set<java.util.UUID> targetParty = parseRoster(object, "targetParty", target);
                java.util.List<org.battleplugins.arena.proxy.SerializedPlayer> players = parseSerializedPlayers(object);
                if (players.isEmpty()) {
                    players.add(requester);
                    players.add(target);
                }

                String origin = object.has("origin") ? object.get("origin").getAsString() : null;

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        org.bukkit.Bukkit.getPluginManager().callEvent(
                                new org.battleplugins.arena.proxy.ProxyDuelRequestEvent(arena, requester, target, requesterParty, targetParty, players, duelMapName, origin)
                        );
                    });

                break;
            }
            // Sent whenever the proxy host has matched enough queued players for an arena.
            // - On the host: this fires a ProxyArenaJoinRequestEvent backed by SerializedPlayer data.
            // - On non-host servers: this sends the involved players to the proxy host.
            case "queue_match": {
                String arenaName = object.get("arena").getAsString();
                String mapName = object.get("map").getAsString();
                boolean duel = object.has("duel") && object.get("duel").getAsBoolean();
                String origin = object.has("origin") ? object.get("origin").getAsString() : "";

                org.battleplugins.arena.Arena arena = plugin.getArena(arenaName);
                if (arena == null) {
                    log.warn("Received queue_match for unknown arena '{}'.", arenaName);
                    return;
                }

                JsonArray playersArray = object.getAsJsonArray("players");

                if (plugin.getMainConfig().isProxyHost()) {
                    if (duel) {
                        break;
                    }
                    java.util.List<org.battleplugins.arena.proxy.SerializedPlayer> players = new java.util.ArrayList<>();
                    playersArray.forEach(el -> {
                        JsonObject playerObject = el.getAsJsonObject();
                        String uuid = playerObject.get("uuid").getAsString();
                        org.battleplugins.arena.proxy.SerializedPlayer serializedPlayer =
                                new org.battleplugins.arena.proxy.SerializedPlayer(uuid);

                        if (playerObject.has("elements") && playerObject.get("elements").isJsonArray()) {
                            playerObject.getAsJsonArray("elements").forEach(elementEl -> {
                                try {
                                    org.battleplugins.arena.proxy.Elements element = org.battleplugins.arena.proxy.Elements.valueOf(elementEl.getAsString());
                                    serializedPlayer.getElements().add(element);
                                } catch (IllegalArgumentException ignored) {
                                    // Ignore unknown elements
                                }
                            });
                        }

                        if (playerObject.has("abilities") && playerObject.get("abilities").isJsonObject()) {
                            JsonObject abilitiesObject = playerObject.getAsJsonObject("abilities");
                            abilitiesObject.entrySet().forEach(entry -> {
                                try {
                                    int slot = Integer.parseInt(entry.getKey());
                                    serializedPlayer.getAbilities().put(slot, entry.getValue().getAsString());
                                } catch (NumberFormatException ignored) {
                                    // Ignore invalid ability slots
                                }
                            });
                        }

                        if (playerObject.has("origin") && playerObject.get("origin").isJsonPrimitive()) {
                            serializedPlayer.setOrigin(playerObject.get("origin").getAsString());
                        } else if (origin != null && !origin.isEmpty()) {
                            serializedPlayer.setOrigin(origin);
                        }

                        if (playerObject.has("origin") && playerObject.get("origin").isJsonPrimitive()) {
                            serializedPlayer.setOrigin(playerObject.get("origin").getAsString());
                        } else if (origin != null && !origin.isEmpty()) {
                            serializedPlayer.setOrigin(origin);
                        }

                        players.add(serializedPlayer);
                    });

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        org.bukkit.Bukkit.getPluginManager().callEvent(
                                new org.battleplugins.arena.proxy.ProxyArenaJoinRequestEvent(arena, mapName, players, origin)
                        );
                    });
                } else {
                    // Non-host servers: send any of the matched players that are currently on this backend
                    // to the proxy host server.
                    String thisOrigin = plugin.getMainConfig().getProxyServerName();
                    if (thisOrigin == null || thisOrigin.isEmpty()) {
                        break;
                    }

                    if (!origin.isEmpty() && !origin.equals(thisOrigin)) {
                        break;
                    }

                    playersArray.forEach(el -> {
                        JsonObject playerObject = el.getAsJsonObject();
                        String playerOrigin = playerObject.has("origin") ? playerObject.get("origin").getAsString() : origin;
                        if (playerOrigin != null && !playerOrigin.isEmpty() && !playerOrigin.equals(thisOrigin)) {
                            return;
                        }

                        String uuid = playerObject.get("uuid").getAsString();
                        java.util.UUID id = java.util.UUID.fromString(uuid);
                        org.bukkit.entity.Player player = Bukkit.getPlayer(id);
                        if (player != null) {
                            plugin.removePendingProxyJoin(player.getUniqueId());
                            plugin.sendPlayerToProxyHost(player);
                        }
                    });
                }

                break;
            }
            // Generic arena join on proxy host (e.g. queued games using remote maps)
            case "arena_join": {
                if (!plugin.getMainConfig().isProxySupport() || !plugin.getMainConfig().isProxyHost()) {
                    return;
                }

                String arenaName = object.get("arena").getAsString();
                String mapName = object.get("map").getAsString();
                boolean duel = object.has("duel") && object.get("duel").getAsBoolean();
                org.battleplugins.arena.Arena arena = plugin.getArena(arenaName);
                if (arena == null) {
                    log.warn("Received proxy arena_join for unknown arena '{}'.", arenaName);
                    return;
                }

                String origin = object.has("origin") ? object.get("origin").getAsString() : null;
                java.util.List<org.battleplugins.arena.proxy.SerializedPlayer> players = new java.util.ArrayList<>();
                final String joinOrigin = origin;
                object.getAsJsonArray("players").forEach(el -> {
                    if (el.isJsonPrimitive()) {
                        // Backwards compatible format: array of UUID strings
                        String uuid = el.getAsString();
                        org.battleplugins.arena.proxy.SerializedPlayer primitivePlayer = new org.battleplugins.arena.proxy.SerializedPlayer(uuid);
                        if (joinOrigin != null && !joinOrigin.isEmpty()) {
                            primitivePlayer.setOrigin(joinOrigin);
                        }
                        players.add(primitivePlayer);
                    } else if (el.isJsonObject()) {
                        JsonObject playerObject = el.getAsJsonObject();
                        String uuid = playerObject.get("uuid").getAsString();
                        org.battleplugins.arena.proxy.SerializedPlayer player = new org.battleplugins.arena.proxy.SerializedPlayer(uuid);

                        if (playerObject.has("elements") && playerObject.get("elements").isJsonArray()) {
                            playerObject.getAsJsonArray("elements").forEach(elementEl -> {
                                try {
                                    org.battleplugins.arena.proxy.Elements element = org.battleplugins.arena.proxy.Elements.valueOf(elementEl.getAsString());
                                    player.getElements().add(element);
                                } catch (IllegalArgumentException ignored) {
                                    // Ignore unknown elements for forwards compatibility
                                }
                            });
                        }

                        if (playerObject.has("abilities") && playerObject.get("abilities").isJsonObject()) {
                            JsonObject abilitiesObject = playerObject.getAsJsonObject("abilities");
                            abilitiesObject.entrySet().forEach(entry -> {
                                try {
                                    int slot = Integer.parseInt(entry.getKey());
                                    player.getAbilities().put(slot, entry.getValue().getAsString());
                                } catch (NumberFormatException ignored) {
                                    // Ignore invalid ability slots
                                }
                            });
                        }

                        if (playerObject.has("origin") && playerObject.get("origin").isJsonPrimitive()) {
                            player.setOrigin(playerObject.get("origin").getAsString());
                        } else if (joinOrigin != null && !joinOrigin.isEmpty()) {
                            player.setOrigin(joinOrigin);
                        }

                        players.add(player);
                    }
                });

                org.battleplugins.arena.competition.map.LiveCompetitionMap map =
                        plugin.getMap(arena, mapName);
                if (map == null) {
                    log.warn("Received arena_join for arena {} map {} but map was not found.", arenaName, mapName);
                    return;
                }

                if (!map.isRemote()) {
                    // Non-remote maps stay local on the host; fire the join event directly.
                    Bukkit.getScheduler().runTask(plugin, () ->
                            org.bukkit.Bukkit.getPluginManager().callEvent(
                                    new org.battleplugins.arena.proxy.ProxyArenaJoinRequestEvent(arena, mapName, players, origin)
                            )
                    );
                    break;
                }

                if (map.getType() == org.battleplugins.arena.competition.map.MapType.DYNAMIC) {
                    // Prepare the dynamic map first using FAWE, then signal players to move.
                    map.createDynamicCompetitionAsync(arena).whenComplete((competition, ex) -> {
                        if (ex != null || competition == null) {
                            log.warn("Failed to prepare dynamic competition for arena_join in arena {} map {}.", arenaName, mapName);
                            return;
                        }

                        sendQueueMatchForPlayers(arena, competition.getMap().getName(), origin, players, duel);
                    });
                } else {
                    // Static remote map: it's already present; just reuse the same queue_match
                    // pipeline so non-host servers only move players once the host is ready.
                    sendQueueMatchForPlayers(arena, map.getName(), origin, players, duel);
                }

                break;
            }
            case "spectate_request": {
                if (!plugin.getMainConfig().isProxySupport() || !plugin.getMainConfig().isProxyHost()) {
                    return;
                }

                ProxySpectateHandler handler = plugin.getProxySpectateHandler();
                if (handler == null) {
                    return;
                }

                String origin = object.has("origin") ? object.get("origin").getAsString() : null;
                JsonElement spectatorElement = object.get("spectator");
                org.battleplugins.arena.proxy.SerializedPlayer spectator = deserializePlayer(spectatorElement);
                if (spectator == null) {
                    log.warn("Received spectate_request without valid spectator payload.");
                    return;
                }

                if ((spectator.getOrigin() == null || spectator.getOrigin().isEmpty()) && origin != null && !origin.isEmpty()) {
                    spectator.setOrigin(origin);
                }

                ProxySpectateHandler.ProxySpectateRequest.Mode mode;
                try {
                    mode = ProxySpectateHandler.ProxySpectateRequest.Mode.valueOf(object.get("mode").getAsString().toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    log.warn("Unknown spectate_request mode: {}", object.get("mode"));
                    return;
                }

                org.battleplugins.arena.Arena arena = null;
                if (object.has("arena")) {
                    String arenaName = object.get("arena").getAsString();
                    arena = plugin.getArena(arenaName);
                }

                java.util.UUID targetId = null;
                if (object.has("target")) {
                    try {
                        targetId = java.util.UUID.fromString(object.get("target").getAsString());
                    } catch (IllegalArgumentException ignored) {
                        log.warn("Invalid target UUID in spectate_request: {}", object.get("target").getAsString());
                    }
                }

                String targetName = object.has("targetName") ? object.get("targetName").getAsString() : null;
                String mapName = object.has("map") ? object.get("map").getAsString() : null;

                ProxySpectateHandler.ProxySpectateRequest request =
                        new ProxySpectateHandler.ProxySpectateRequest(mode, arena, mapName, targetId, targetName, spectator, origin);

                Bukkit.getScheduler().runTask(plugin, () -> handler.handleRequest(request));
                break;
            }
            case "spectate_ready": {
                if (!plugin.getMainConfig().isProxySupport() || plugin.getMainConfig().isProxyHost()) {
                    return;
                }

                String origin = object.has("origin") ? object.get("origin").getAsString() : "";
                String thisOrigin = plugin.getMainConfig().getProxyServerName();
                if (thisOrigin == null || thisOrigin.isEmpty()) {
                    return;
                }

                if (!origin.isEmpty() && !origin.equals(thisOrigin)) {
                    return;
                }

                java.util.UUID id;
                try {
                    id = java.util.UUID.fromString(object.get("uuid").getAsString());
                } catch (IllegalArgumentException ex) {
                    return;
                }

                Player player = Bukkit.getPlayer(id);
                plugin.removePendingProxySpectate(id);

                if (player == null) {
                    return;
                }

                String mapName = object.has("map") ? object.get("map").getAsString() : "";
                if (!mapName.isEmpty()) {
                    Messages.PROXY_SPECTATE_READY.send(player, mapName);
                }
                plugin.sendPlayerToProxyHost(player);
                break;
            }
            case "spectate_reject": {
                if (!plugin.getMainConfig().isProxySupport() || plugin.getMainConfig().isProxyHost()) {
                    return;
                }

                String origin = object.has("origin") ? object.get("origin").getAsString() : "";
                String thisOrigin = plugin.getMainConfig().getProxyServerName();
                if (thisOrigin == null || thisOrigin.isEmpty()) {
                    return;
                }

                if (!origin.isEmpty() && !origin.equals(thisOrigin)) {
                    return;
                }

                java.util.UUID id;
                try {
                    id = java.util.UUID.fromString(object.get("uuid").getAsString());
                } catch (IllegalArgumentException ex) {
                    return;
                }

                Player player = Bukkit.getPlayer(id);
                plugin.removePendingProxySpectate(id);

                if (player != null && object.has("reason")) {
                    String reasonString = object.get("reason").getAsString();
                    Component reasonComponent = Messages.deserializeMiniMessage(reasonString);
                    player.sendMessage(reasonComponent);
                }

                break;
            }
        }
    }

    /**
     * Sends the current arenas and maps directories to the router so other
     * BattleArena instances can synchronize their arena configuration.
     * <p>
     * Intended to be called from the proxy host.
     */
    public void sendSyncConfig() {
        Path mapsPath = plugin.getMapsPath();
        if (!Files.exists(mapsPath)) {
            log.info("Skipping proxy sync: maps directory {} does not exist.", mapsPath);
            return;
        }

        Path arenasPath = plugin.getDataFolder().toPath().resolve("arenas");

        try {
            JsonObject response = new JsonObject();
            response.addProperty("type", "sync_config");

            String mapsData = zipFolderToBase64(mapsPath);
            response.addProperty("maps", mapsData);

            if (Files.exists(arenasPath)) {
                String arenasData = zipFolderToBase64(arenasPath);
                response.addProperty("arenas", arenasData);
            }

            sendToRouter(response.toString());
            log.info("Sent arenas and maps to proxy clients from {}, {}.", arenasPath, mapsPath);
        } catch (IOException e) {
            log.warn("Failed to send sync_config to router: {}", e.getMessage());
        }
    }

    public static String zipFolderToBase64(Path folderPath) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(baos);

        Files.walk(folderPath).forEach(path -> {
            try {
                if (Files.isDirectory(path)) return;

                ZipEntry zipEntry = new ZipEntry(folderPath.relativize(path).toString());
                zos.putNextEntry(zipEntry);
                zos.write(Files.readAllBytes(path));
                zos.closeEntry();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        zos.close();
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    public static void unzipToDirectory(byte[] zipBytes, Path outputDir) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(zipBytes);
             ZipInputStream zis = new ZipInputStream(bais)) {
            ZipEntry entry;

            while ((entry = zis.getNextEntry()) != null) {
                Path filePath = outputDir.resolve(entry.getName());

                if (entry.isDirectory()) {
                    Files.createDirectories(filePath);
                } else {
                    Files.createDirectories(filePath.getParent());
                    Files.copy(zis, filePath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    public void shutdown() {
        disconnect();
    }

    private static java.util.Set<java.util.UUID> parseRoster(JsonObject payload,
                                                            String field,
                                                            org.battleplugins.arena.proxy.SerializedPlayer fallback) {
        java.util.Set<java.util.UUID> roster = new LinkedHashSet<>();
        try {
            roster.add(java.util.UUID.fromString(fallback.getUuid()));
        } catch (IllegalArgumentException ignored) {
        }

        if (payload.has(field) && payload.get(field).isJsonArray()) {
            payload.getAsJsonArray(field).forEach(element -> {
                if (!element.isJsonPrimitive()) {
                    return;
                }

                try {
                    roster.add(java.util.UUID.fromString(element.getAsString()));
                } catch (IllegalArgumentException ignored) {
                }
            });
        }

        return roster;
    }

    private static java.util.List<org.battleplugins.arena.proxy.SerializedPlayer> parseSerializedPlayers(JsonObject payload) {
        java.util.List<org.battleplugins.arena.proxy.SerializedPlayer> players = new ArrayList<>();
        if (payload.has("players") && payload.get("players").isJsonArray()) {
            payload.getAsJsonArray("players").forEach(element -> {
                org.battleplugins.arena.proxy.SerializedPlayer player = deserializePlayer(element);
                if (player != null) {
                    players.add(player);
                }
            });
        }

        return players;
    }

    private static org.battleplugins.arena.proxy.SerializedPlayer deserializePlayer(JsonElement element) {
        if (element == null) {
            return null;
        }

        if (element.isJsonPrimitive()) {
            return new org.battleplugins.arena.proxy.SerializedPlayer(element.getAsString());
        }

        if (!element.isJsonObject()) {
            return null;
        }

        JsonObject playerObject = element.getAsJsonObject();
        if (!playerObject.has("uuid") || !playerObject.get("uuid").isJsonPrimitive()) {
            return null;
        }

        String uuid = playerObject.get("uuid").getAsString();
        org.battleplugins.arena.proxy.SerializedPlayer serialized = new org.battleplugins.arena.proxy.SerializedPlayer(uuid);

        if (playerObject.has("elements") && playerObject.get("elements").isJsonArray()) {
            playerObject.getAsJsonArray("elements").forEach(elementEl -> {
                try {
                    org.battleplugins.arena.proxy.Elements parsed = org.battleplugins.arena.proxy.Elements.valueOf(elementEl.getAsString());
                    serialized.getElements().add(parsed);
                } catch (IllegalArgumentException ignored) {
                }
            });
        }

        if (playerObject.has("abilities") && playerObject.get("abilities").isJsonObject()) {
            JsonObject abilitiesObject = playerObject.getAsJsonObject("abilities");
            abilitiesObject.entrySet().forEach(entry -> {
                try {
                    int slot = Integer.parseInt(entry.getKey());
                    serialized.getAbilities().put(slot, entry.getValue().getAsString());
                } catch (NumberFormatException ignored) {
                }
            });
        }

        if (playerObject.has("origin") && playerObject.get("origin").isJsonPrimitive()) {
            serialized.setOrigin(playerObject.get("origin").getAsString());
        }

        return serialized;
    }

    private void sendQueueMatchForPlayers(org.battleplugins.arena.Arena arena,
                                           String readyMapName,
                                           String origin,
                                           java.util.List<org.battleplugins.arena.proxy.SerializedPlayer> players,
                                           boolean duel) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "queue_match");
        payload.addProperty("arena", arena.getName());
        payload.addProperty("map", readyMapName);
        if (duel) {
            payload.addProperty("duel", true);
        }
        if (origin != null && !origin.isEmpty()) {
            payload.addProperty("origin", origin);
        }

        JsonArray playersArray = new JsonArray();
        for (org.battleplugins.arena.proxy.SerializedPlayer sp : players) {
            JsonObject playerObject = new JsonObject();
            playerObject.addProperty("uuid", sp.getUuid());

            if (!sp.getElements().isEmpty()) {
                JsonArray elementsArray = new JsonArray();
                sp.getElements().forEach(element -> elementsArray.add(element.name()));
                playerObject.add("elements", elementsArray);
            }

            if (!sp.getAbilities().isEmpty()) {
                JsonObject abilitiesObject = new JsonObject();
                sp.getAbilities().forEach((slot, ability) ->
                        abilitiesObject.addProperty(String.valueOf(slot), ability));
                playerObject.add("abilities", abilitiesObject);
            }

            if (sp.getOrigin() != null && !sp.getOrigin().isEmpty()) {
                playerObject.addProperty("origin", sp.getOrigin());
            }

            playersArray.add(playerObject);
        }

        payload.add("players", playersArray);
        sendToRouter(payload.toString());
    }
}
