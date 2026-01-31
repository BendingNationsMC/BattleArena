package org.battleplugins.arena.duel;

import net.kyori.adventure.text.Component;
import org.battleplugins.arena.Arena;
import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.competition.map.LiveCompetitionMap;
import org.battleplugins.arena.competition.map.MapType;
import org.battleplugins.arena.messages.Messages;
import org.battleplugins.arena.util.Util;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Handles duel GUI rendering and interactions.
 */
public class DuelMenuService implements Listener {
    private static final String DUELS_MODULE_ID = "duels";

    private final BattleArena plugin;
    private DuelMenuConfig config;

    public DuelMenuService(BattleArena plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        try {
            Path dataFolder = this.plugin.getDataFolder().toPath();
            this.config = DuelMenuConfig.load(this.plugin, dataFolder);
        } catch (IOException e) {
            this.plugin.error("Failed to load duel-menu.yml. Using defaults.", e);
            this.config = DuelMenuConfig.fallback();
        }
    }

    public void shutdown() {
        HandlerList.unregisterAll(this);
    }

    public void openArenaMenu(Player viewer, @Nullable Player target) {
        this.openArenaMenu(viewer, target, 1);
    }

    public void openArenaMenu(Player viewer, @Nullable Player target, int rounds) {
        if (this.config == null) {
            Messages.ARENA_ERROR.send(viewer, "Duel menu not configured.");
            return;
        }

        Player duelTarget = target;
        if (duelTarget != null && duelTarget.getUniqueId().equals(viewer.getUniqueId())) {
            duelTarget = null;
        }

        int duelRounds = Math.max(1, rounds);
        DuelContext context = new DuelContext(viewer.getUniqueId(), duelTarget != null ? duelTarget.getUniqueId() : null,
                duelTarget != null ? duelTarget.getName() : null, duelRounds);
        DuelMenuConfig.MenuLayout layout = this.config.arenaMenu();

        List<Arena> arenas = this.plugin.getArenas().stream()
                .filter(arena -> !this.plugin.getMaps(arena).isEmpty())
                .filter(arena -> !this.config.isBlacklisted(arena.getName()))
                .sorted(Comparator.comparing(Arena::getName))
                .toList();
        if (arenas.isEmpty()) {
            Messages.NO_MAPS_FOR_ARENA.send(viewer);
            return;
        }

        int size = layout.rows() * 9;
        Component title = Util.deserializeFromLegacy(applyContextPlaceholders(layout.title(), context));

        ArenaMenuHolder holder = new ArenaMenuHolder(context);
        Inventory inventory = Bukkit.createInventory(holder, size, title);
        holder.setInventory(inventory);

        Set<Integer> occupiedSlots = new HashSet<>();
        for (Arena arena : arenas) {
            DuelMenuConfig.MenuEntry entry = layout.overrideFor(arena.getName());
            DuelMenuConfig.ItemTemplate template = entry != null && entry.template() != null
                    ? entry.template()
                    : layout.defaultItem();

            int slot = entry != null && entry.slot() != null
                    ? entry.slot()
                    : findNextAvailableSlot(size, occupiedSlots);
            if (slot < 0 || slot >= size || template == null) {
                continue;
            }

            Map<String, String> placeholders = buildArenaPlaceholders(arena, context);
            ItemStack icon = template.build(placeholders);
            inventory.setItem(slot, icon);
            holder.register(slot, arena.getName());
            occupiedSlots.add(slot);
        }

        Map<String, String> fillerPlaceholders = Map.of("target", context.targetName() != null ? context.targetName() : "None");
        fillWithFiller(inventory, layout.filler(), fillerPlaceholders);

        viewer.openInventory(inventory);
    }

    private Map<String, String> buildArenaPlaceholders(Arena arena, DuelContext context) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("arena", arena.getName());
        placeholders.put("map_count", String.valueOf(this.plugin.getMaps(arena).size()));
        placeholders.put("target", context.targetName() != null ? context.targetName() : "None");
        return placeholders;
    }

    private Map<String, String> buildMapPlaceholders(Arena arena, LiveCompetitionMap map, DuelContext context) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("arena", arena.getName());
        placeholders.put("map", map.getName());
        placeholders.put("map_type", map.getType().name());
        placeholders.put("target", context.targetName() != null ? context.targetName() : "None");
        return placeholders;
    }

    private void fillWithFiller(Inventory inventory, @Nullable DuelMenuConfig.ItemTemplate filler, Map<String, String> placeholders) {
        if (filler == null) {
            return;
        }

        ItemStack fillerStack = filler.build(placeholders);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null || inventory.getItem(slot).getType() == Material.AIR) {
                inventory.setItem(slot, fillerStack.clone());
            }
        }
    }

    private int findNextAvailableSlot(int size, Set<Integer> occupied) {
        for (int slot = 0; slot < size; slot++) {
            if (!occupied.contains(slot)) {
                return slot;
            }
        }

        return -1;
    }

    private String applyContextPlaceholders(String input, DuelContext context) {
        if (input == null) {
            return "";
        }

        if (context.targetName() != null) {
            return input.replace("{target}", context.targetName());
        }

        return input.replace("{target}", "None");
    }

    private void openMapMenu(Player viewer, DuelContext context, Arena arena) {
        List<LiveCompetitionMap> maps = this.plugin.getMaps(arena);
        if (maps.isEmpty()) {
            Messages.NO_MAPS_FOR_ARENA.send(viewer);
            return;
        }

        DuelMenuConfig.MapMenuLayout layout = this.config.mapMenu();
        int size = layout.rows() * 9;
        Component title = Util.deserializeFromLegacy(
                applyContextPlaceholders(layout.title().replace("{arena}", arena.getName()), context)
        );

        MapMenuHolder holder = new MapMenuHolder(context, arena.getName());
        Inventory inventory = Bukkit.createInventory(holder, size, title);
        holder.setInventory(inventory);

        Set<Integer> occupiedSlots = new HashSet<>();
        for (LiveCompetitionMap map : maps.stream().sorted(Comparator.comparing(LiveCompetitionMap::getName)).toList()) {
            DuelMenuConfig.ItemTemplate template = layout.templateFor(arena.getName(), map.getName());
            if (template == null) {
                continue;
            }

            int slot = findNextAvailableSlot(size, occupiedSlots);
            if (slot < 0 || slot >= size) {
                break;
            }

            ItemStack icon = template.build(buildMapPlaceholders(arena, map, context));
            inventory.setItem(slot, icon);
            holder.register(slot, map.getName());
            occupiedSlots.add(slot);
        }

        DuelMenuConfig.MenuEntry backButton = layout.backButton();
        if (backButton != null && backButton.template() != null && backButton.slot() != null) {
            int slot = backButton.slot();
            if (slot >= 0 && slot < size) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("arena", arena.getName());
                placeholders.put("target", context.targetName() != null ? context.targetName() : "None");
                inventory.setItem(slot, backButton.template().build(placeholders));
                holder.setBackSlot(slot);
            }
        }

        Map<String, String> fillerPlaceholders = new HashMap<>();
        fillerPlaceholders.put("arena", arena.getName());
        fillerPlaceholders.put("target", context.targetName() != null ? context.targetName() : "None");
        fillWithFiller(inventory, layout.filler(), fillerPlaceholders);

        viewer.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!(event.getInventory().getHolder() instanceof DuelMenuHolder holder)) {
            return;
        }

        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int rawSlot = event.getRawSlot();
        if (holder instanceof ArenaMenuHolder arenaHolder) {
            if (!arenaHolder.context().viewerId().equals(player.getUniqueId())) {
                return;
            }

            String arenaName = arenaHolder.getArena(rawSlot);
            if (arenaName == null) {
                return;
            }

            Arena arena = this.plugin.getArena(arenaName);
            if (arena == null) {
                player.closeInventory();
                Messages.ARENA_DOES_NOT_EXIST.send(player, arenaName);
                return;
            }

            openMapMenu(player, arenaHolder.context(), arena);
        } else if (holder instanceof MapMenuHolder mapHolder) {
            if (!mapHolder.context().viewerId().equals(player.getUniqueId())) {
                return;
            }

            if (rawSlot == mapHolder.backSlot()) {
                openArenaMenu(player, mapHolder.context().target(), mapHolder.context().rounds());
                return;
            }

            String mapName = mapHolder.getMap(rawSlot);
            if (mapName == null) {
                return;
            }

            Arena arena = this.plugin.getArena(mapHolder.arenaName());
            if (arena == null) {
                player.closeInventory();
                Messages.ARENA_DOES_NOT_EXIST.send(player, mapHolder.arenaName());
                return;
            }

            LiveCompetitionMap map = this.plugin.getMap(arena, mapName);
            if (map == null) {
                player.closeInventory();
                Messages.NO_ARENA_WITH_NAME.send(player);
                return;
            }

            player.closeInventory();
            this.requestDuel(player, mapHolder.context(), arena, map);
        }
    }

    private void requestDuel(Player initiator, DuelContext context, Arena arena, LiveCompetitionMap map) {
        Player target = context.target();
        if (target == null) {
            Messages.PLAYER_NOT_ONLINE.send(initiator, context.targetName() != null ? context.targetName() : "target");
            return;
        }

        if (!arena.isModuleEnabled(DUELS_MODULE_ID)) {
            Messages.ARENA_ERROR.send(initiator, "Duels are not enabled for this arena.");
            return;
        }

        if (map.getType() != MapType.DYNAMIC) {
            Messages.ARENA_ERROR.send(initiator, "Only dynamic maps can be used for duels.");
            return;
        }

        if (this.plugin.getMainConfig().isProxySupport()
                && this.plugin.getMainConfig().isProxyHost()
                && !map.isRemote()) {
            Messages.ARENA_ERROR.send(initiator, "This map is not available for proxy duels.");
            return;
        }

        this.plugin.getDuelSelectionRegistry().storeSelection(
                initiator.getUniqueId(),
                target.getUniqueId(),
                arena.getName(),
                map.getName()
        );

        String duelCommand = arena.getName().toLowerCase(Locale.ROOT) + " duel " + target.getName();
        if (context.rounds() > 1) {
            duelCommand += " " + context.rounds();
        }
        String commandToRun = duelCommand;
        Bukkit.getScheduler().runTask(this.plugin, () -> initiator.performCommand(commandToRun));
    }

    private interface DuelMenuHolder extends InventoryHolder {
        void setInventory(Inventory inventory);

        DuelContext context();
    }

    private static final class ArenaMenuHolder implements DuelMenuHolder {
        private final DuelContext context;
        private final Map<Integer, String> arenaSlots = new HashMap<>();
        private Inventory inventory;

        private ArenaMenuHolder(DuelContext context) {
            this.context = context;
        }

        @Override
        public Inventory getInventory() {
            return this.inventory;
        }

        @Override
        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public DuelContext context() {
            return this.context;
        }

        public void register(int slot, String arenaName) {
            this.arenaSlots.put(slot, arenaName);
        }

        public String getArena(int slot) {
            return this.arenaSlots.get(slot);
        }
    }

    private static final class MapMenuHolder implements DuelMenuHolder {
        private final DuelContext context;
        private final String arenaName;
        private final Map<Integer, String> mapSlots = new HashMap<>();
        private Inventory inventory;
        private int backSlot = -1;

        private MapMenuHolder(DuelContext context, String arenaName) {
            this.context = context;
            this.arenaName = arenaName;
        }

        @Override
        public Inventory getInventory() {
            return this.inventory;
        }

        @Override
        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public DuelContext context() {
            return this.context;
        }

        public String arenaName() {
            return this.arenaName;
        }

        public void register(int slot, String mapName) {
            this.mapSlots.put(slot, mapName);
        }

        public String getMap(int slot) {
            return this.mapSlots.get(slot);
        }

        public void setBackSlot(int slot) {
            this.backSlot = slot;
        }

        public int backSlot() {
            return this.backSlot;
        }
    }

    private record DuelContext(UUID viewerId, @Nullable UUID targetId, @Nullable String targetName, int rounds) {
        public @Nullable Player target() {
            return this.targetId == null ? null : Bukkit.getPlayer(this.targetId);
        }
    }
}
