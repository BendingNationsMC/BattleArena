// PasteGuard.java
package org.battleplugins.arena.util;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class PasteGuard implements Listener {
    private final Plugin plugin;
    private final World world;
    private final CuboidRegion region;
    private final int marginChunks;
    private volatile boolean active = false;

    private static final class State {
        final boolean allowFlight;
        final boolean flying;
        final float walkSpeed;
        final float flySpeed;
        State(boolean a, boolean f, float ws, float fs) {
            this.allowFlight = a; this.flying = f; this.walkSpeed = ws; this.flySpeed = fs;
        }
    }
    private final Map<UUID, State> saved = new ConcurrentHashMap<>();
    private final Set<UUID> protectedPlayers = ConcurrentHashMap.newKeySet();

    public PasteGuard(Plugin plugin, World world, CuboidRegion region, int marginChunks) {
        this.plugin = plugin; this.world = world; this.region = region; this.marginChunks = marginChunks;
    }

    public void enable() {
        if (active) return;
        active = true;
        Bukkit.getPluginManager().registerEvents(this, plugin);

        for (Player p : world.getPlayers()) {
            if (inPaddedRegion(p.getLocation())) {
                protect(p);
            }
        }
    }

    public void disable() {
        if (!active) return;
        active = false;
        HandlerList.unregisterAll(this);

        for (UUID id : protectedPlayers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) restore(p);
        }
        protectedPlayers.clear();
        saved.clear();
    }

    private boolean inPaddedRegion(Location loc) {
        if (!loc.getWorld().equals(world)) return false;
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        int pad = marginChunks * 16;

        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        return x >= (min.x() - pad) && x <= (max.x() + pad) &&
               y >= (min.y() - 4)   && y <= (max.y() + 4) && // small Y pad
               z >= (min.z() - pad) && z <= (max.z() + pad);
    }

    private void protect(Player p) {
        protectedPlayers.add(p.getUniqueId());
        saved.putIfAbsent(p.getUniqueId(), new State(
                p.getAllowFlight(), p.isFlying(), p.getWalkSpeed(), p.getFlySpeed()
        ));
        p.setAllowFlight(true);
        p.setFlying(true);
        p.setWalkSpeed(0.0f);
        p.setFlySpeed(0.0f);
        p.setFallDistance(0f);
        p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.SLOW_FALLING, 20, 0, false, false, false));
        p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.DAMAGE_RESISTANCE, 20, 4, false, false, false));
        p.sendActionBar(Component.text("Please wait while we generate the world..."));
    }

    private void restore(Player p) {
        State st = saved.remove(p.getUniqueId());
        if (st != null) {
            p.setAllowFlight(st.allowFlight);
            p.setFlying(st.flying && st.allowFlight);
            p.setWalkSpeed(st.walkSpeed);
            p.setFlySpeed(st.flySpeed);
        } else {
            p.setWalkSpeed(0.2f);
            p.setFlySpeed(0.1f);
        }
        p.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING);
        p.removePotionEffect(org.bukkit.potion.PotionEffectType.DAMAGE_RESISTANCE);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent e) {
        if (!active) return;
        Player p = e.getPlayer();
        if (!p.getWorld().equals(world)) return;

        // Keep players from entering the padded region while active
        if (inPaddedRegion(e.getTo())) {
            e.setTo(e.getFrom());
            protect(p);
            return;
        }

        // If leaving region and we had them protected, restore
        if (protectedPlayers.remove(p.getUniqueId())) {
            restore(p);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        if (!active) return;
        if (!(e.getEntity() instanceof Player p) || !p.getWorld().equals(world)) return;
        if (!inPaddedRegion(p.getLocation())) return;

        // Prevent the common hazards during rebuild
        switch (e.getCause()) {
            case FALL:
            case SUFFOCATION:
            case VOID:
                e.setCancelled(true);
                break;
            default:
                break;
        }
    }
}
