package org.battleplugins.arena.duel;

import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.util.Util;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Represents the configurable duel menu layout.
 */
public class DuelMenuConfig {
    private final MenuLayout arenaMenu;
    private final MapMenuLayout mapMenu;
    private final Set<String> blacklistedArenas;

    private DuelMenuConfig(MenuLayout arenaMenu, MapMenuLayout mapMenu, Set<String> blacklistedArenas) {
        this.arenaMenu = arenaMenu;
        this.mapMenu = mapMenu;
        this.blacklistedArenas = blacklistedArenas;
    }

    public MenuLayout arenaMenu() {
        return this.arenaMenu;
    }

    public MapMenuLayout mapMenu() {
        return this.mapMenu;
    }

    public boolean isBlacklisted(String arenaName) {
        return this.blacklistedArenas.contains(arenaName.toLowerCase(Locale.ROOT));
    }

    public static DuelMenuConfig load(BattleArena plugin, Path dataFolder) throws IOException {
        File file = dataFolder.resolve("duel-menu.yml").toFile();
        if (!file.exists()) {
            throw new IOException("duel-menu.yml does not exist");
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        MenuLayout arenaMenu = MenuLayout.fromSection(plugin, config.getConfigurationSection("arena-menu"),
                "&6Select Arena Type", 3);
        MapMenuLayout mapMenu = MapMenuLayout.fromSection(plugin, config.getConfigurationSection("map-menu"),
                "&6Select Arena Map - {arena}", 4);

        Set<String> blacklist = new HashSet<>();
        for (String entry : config.getStringList("blacklisted-arenas")) {
            blacklist.add(entry.toLowerCase(Locale.ROOT));
        }

        return new DuelMenuConfig(arenaMenu, mapMenu, blacklist);
    }

    public static DuelMenuConfig fallback() {
        MenuLayout arenaMenu = new MenuLayout("&6Select Arena Type", 3,
                ItemTemplate.create(Material.GRAY_STAINED_GLASS_PANE, " ", List.of(), false, true, 1, null),
                ItemTemplate.create(Material.DIAMOND_SWORD, "&e{arena}", List.of(), false, true, 1, null),
                Map.of());

        MapMenuLayout mapMenu = new MapMenuLayout("&6Select Map - {arena}", 4,
                ItemTemplate.create(Material.BLACK_STAINED_GLASS_PANE, " ", List.of(), false, true, 1, null),
                ItemTemplate.create(Material.PAPER, "&f{map}", List.of(), false, true, 1, null),
                Map.of(), Map.of(), new MenuEntry(
                ItemTemplate.create(Material.ARROW, "&c« Back", List.of("&7Return to arena selection"), false, true, 1, null),
                27
        ));

        return new DuelMenuConfig(arenaMenu, mapMenu, Set.of());
    }

    public static class MenuLayout {
        private final String title;
        private final int rows;
        private final ItemTemplate filler;
        private final ItemTemplate defaultItem;
        private final Map<String, MenuEntry> overrides;

        MenuLayout(String title, int rows, ItemTemplate filler, ItemTemplate defaultItem, Map<String, MenuEntry> overrides) {
            this.title = title;
            this.rows = Math.max(1, Math.min(rows, 6));
            this.filler = filler;
            this.defaultItem = defaultItem;
            this.overrides = overrides;
        }

        public String title() {
            return this.title;
        }

        public int rows() {
            return this.rows;
        }

        public ItemTemplate filler() {
            return this.filler;
        }

        public ItemTemplate defaultItem() {
            return this.defaultItem;
        }

        public MenuEntry overrideFor(String name) {
            if (name == null) {
                return null;
            }

            return this.overrides.get(name.toLowerCase(Locale.ROOT));
        }

        public static MenuLayout fromSection(BattleArena plugin, @Nullable ConfigurationSection section,
                                             String defaultTitle, int defaultRows) {
            if (section == null) {
                return new MenuLayout(defaultTitle, defaultRows,
                        ItemTemplate.create(Material.GRAY_STAINED_GLASS_PANE, " ", List.of(), false, true, 1, null),
                        ItemTemplate.create(Material.DIAMOND_SWORD, "&e{arena}", List.of(), false, true, 1, null),
                        Map.of());
            }

            String title = section.getString("title", defaultTitle);
            int rows = section.getInt("rows", defaultRows);
            ItemTemplate filler = ItemTemplate.fromSection(plugin, section.getConfigurationSection("filler"));
            if (filler == null) {
                filler = ItemTemplate.create(Material.GRAY_STAINED_GLASS_PANE, " ", List.of(), false, true, 1, null);
            }

            ItemTemplate defaultItem = ItemTemplate.fromSection(plugin, section.getConfigurationSection("default-item"));
            if (defaultItem == null) {
                defaultItem = ItemTemplate.create(Material.DIAMOND_SWORD, "&e{arena}", List.of(), false, true, 1, null);
            }

            Map<String, MenuEntry> overrides = new HashMap<>();
            ConfigurationSection entries = section.getConfigurationSection("arenas");
            if (entries != null) {
                for (String key : entries.getKeys(false)) {
                    ConfigurationSection entrySection = entries.getConfigurationSection(key);
                    if (entrySection == null) {
                        continue;
                    }

                    ItemTemplate template = ItemTemplate.fromSection(plugin, entrySection);
                    Integer slot = entrySection.contains("slot") ? entrySection.getInt("slot") : null;
                    overrides.put(key.toLowerCase(Locale.ROOT), new MenuEntry(template, slot));
                }
            }

            return new MenuLayout(title, rows, filler, defaultItem, overrides);
        }
    }

    public static final class MapMenuLayout extends MenuLayout {
        private final Map<String, ItemTemplate> arenaOverrides;
        private final Map<String, Map<String, ItemTemplate>> mapOverrides;
        private final MenuEntry backButton;

        MapMenuLayout(String title, int rows, ItemTemplate filler, ItemTemplate defaultItem,
                      Map<String, ItemTemplate> arenaOverrides,
                      Map<String, Map<String, ItemTemplate>> mapOverrides,
                      @Nullable MenuEntry backButton) {
            super(title, rows, filler, defaultItem, Map.of());
            this.arenaOverrides = arenaOverrides;
            this.mapOverrides = mapOverrides;
            this.backButton = backButton;
        }

        public ItemTemplate templateFor(String arenaName, String mapName) {
            if (mapName != null) {
                Map<String, ItemTemplate> perArena = this.mapOverrides.get(arenaName.toLowerCase(Locale.ROOT));
                if (perArena != null) {
                    ItemTemplate override = perArena.get(mapName.toLowerCase(Locale.ROOT));
                    if (override != null) {
                        return override;
                    }
                }
            }

            ItemTemplate override = this.arenaOverrides.get(arenaName.toLowerCase(Locale.ROOT));
            if (override != null) {
                return override;
            }

            return this.defaultItem();
        }

        public MenuEntry backButton() {
            return this.backButton;
        }

        public static MapMenuLayout fromSection(BattleArena plugin, @Nullable ConfigurationSection section,
                                                String defaultTitle, int defaultRows) {
            if (section == null) {
                return new MapMenuLayout(defaultTitle, defaultRows,
                        ItemTemplate.create(Material.BLACK_STAINED_GLASS_PANE, " ", List.of(), false, true, 1, null),
                        ItemTemplate.create(Material.PAPER, "&f{map}", List.of(), false, true, 1, null),
                        Map.of(), Map.of(), new MenuEntry(
                        ItemTemplate.create(Material.ARROW, "&c« Back", List.of("&7Return to arena selection"), false, true, 1, null),
                        27
                ));
            }

            MenuLayout parent = MenuLayout.fromSection(plugin, section, defaultTitle, defaultRows);
            ItemTemplate filler = parent.filler();
            ItemTemplate defaultItem = parent.defaultItem();

            Map<String, ItemTemplate> arenaOverrides = new HashMap<>();
            ConfigurationSection arenaOverrideSection = section.getConfigurationSection("arena-map-defaults");
            if (arenaOverrideSection != null) {
                for (String key : arenaOverrideSection.getKeys(false)) {
                    ItemTemplate template = ItemTemplate.fromSection(plugin, arenaOverrideSection.getConfigurationSection(key));
                    arenaOverrides.put(key.toLowerCase(Locale.ROOT), template);
                }
            }

            Map<String, Map<String, ItemTemplate>> mapOverrides = new HashMap<>();
            ConfigurationSection perArenaMaps = section.getConfigurationSection("maps");
            if (perArenaMaps != null) {
                for (String arena : perArenaMaps.getKeys(false)) {
                    ConfigurationSection mapsSection = perArenaMaps.getConfigurationSection(arena);
                    if (mapsSection == null) {
                        continue;
                    }

                    Map<String, ItemTemplate> arenaMapOverrides = new HashMap<>();
                    for (String map : mapsSection.getKeys(false)) {
                        ItemTemplate template = ItemTemplate.fromSection(plugin, mapsSection.getConfigurationSection(map));
                        arenaMapOverrides.put(map.toLowerCase(Locale.ROOT), template);
                    }

                    mapOverrides.put(arena.toLowerCase(Locale.ROOT), arenaMapOverrides);
                }
            }

            MenuEntry backButton = null;
            ConfigurationSection backSection = section.getConfigurationSection("back-button");
            if (backSection != null) {
                ItemTemplate template = ItemTemplate.fromSection(plugin, backSection);
                int slot = backSection.getInt("slot", (parent.rows() - 1) * 9);
                backButton = new MenuEntry(template, slot);
            }

            return new MapMenuLayout(parent.title(), parent.rows(), filler, defaultItem, arenaOverrides, mapOverrides, backButton);
        }
    }

    public record MenuEntry(ItemTemplate template, @Nullable Integer slot) {
    }

    public static final class ItemTemplate {
        private final Material material;
        private final String displayName;
        private final List<String> lore;
        private final boolean glow;
        private final boolean hideTooltip;
        private final int amount;
        private final Integer customModelData;

        private ItemTemplate(Material material, String displayName, List<String> lore,
                             boolean glow, boolean hideTooltip, int amount, @Nullable Integer customModelData) {
            this.material = material;
            this.displayName = displayName;
            this.lore = lore;
            this.glow = glow;
            this.hideTooltip = hideTooltip;
            this.amount = Math.max(1, Math.min(amount, 64));
            this.customModelData = customModelData;
        }

        public static ItemTemplate fromSection(BattleArena plugin, @Nullable ConfigurationSection section) {
            if (section == null) {
                return null;
            }

            String materialName = section.getString("material", "STONE");
            Material material = Material.matchMaterial(materialName);
            if (material == null) {
                plugin.warn("Unknown material {} in duel-menu.yml. Defaulting to STONE.", materialName);
                material = Material.STONE;
            }

            String name = section.getString("name", null);
            List<String> lore = section.getStringList("lore");
            boolean glow = section.getBoolean("glow", false);
            boolean hideTooltip = section.getBoolean("hide-tooltip", true);
            int amount = section.getInt("amount", 1);
            Integer cmd = section.contains("custom-model-data") ? section.getInt("custom-model-data") : null;

            return new ItemTemplate(material, name, lore, glow, hideTooltip, amount, cmd);
        }

        public static ItemTemplate create(Material material, String name, List<String> lore,
                                          boolean glow, boolean hideTooltip, int amount, @Nullable Integer customModelData) {
            return new ItemTemplate(material, name, lore, glow, hideTooltip, amount, customModelData);
        }

        public ItemStack build(Map<String, String> placeholders) {
            ItemStack stack = new ItemStack(this.material, this.amount);
            ItemMeta meta = stack.getItemMeta();
            meta.lore(new ArrayList<>());

            if (this.displayName != null && !this.displayName.isEmpty()) {
                meta.displayName(Util.deserializeFromLegacy(applyPlaceholders(this.displayName, placeholders)));
            }

            if (!this.lore.isEmpty()) {
                List<net.kyori.adventure.text.Component> loreComponents = new ArrayList<>();
                for (String line : this.lore) {
                    loreComponents.add(Util.deserializeFromLegacy(applyPlaceholders(line, placeholders)));
                }
                meta.lore(loreComponents);
            }

            if (this.customModelData != null) {
                meta.setCustomModelData(this.customModelData);
            }

            if (this.glow) {
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }

            if (this.hideTooltip) {
                meta.addItemFlags(
                        ItemFlag.HIDE_ATTRIBUTES,
                        ItemFlag.HIDE_DESTROYS,
                        ItemFlag.HIDE_DYE,
                        ItemFlag.HIDE_ARMOR_TRIM,
                        ItemFlag.HIDE_ENCHANTS
                );
            }

            stack.setItemMeta(meta);
            return stack;
        }

        private static String applyPlaceholders(String input, Map<String, String> placeholders) {
            String output = input;
            if (placeholders != null) {
                for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                    output = output.replace("{" + entry.getKey() + "}", entry.getValue());
                }
            }

            return output;
        }
    }
}
