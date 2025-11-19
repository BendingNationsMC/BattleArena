package org.battleplugins.arena.util;

import com.fastasyncworldedit.core.extent.processor.lighting.RelightMode;
import com.sk89q.worldedit.*;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.mask.ExistingBlockMask;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.function.pattern.BlockPattern;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.competition.map.options.Bounds;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class BlockUtil {
    public static boolean copyToWorld(
            World oldWorld, World newWorld,
            Bounds srcBounds, Bounds dstBounds
    ) {
        return copyToWorld(oldWorld, newWorld, srcBounds, dstBounds, null);
    }

    public static boolean copyToWorld(
            World oldWorld, World newWorld,
            Bounds srcBounds, Bounds dstBounds,
            Runnable onComplete
    ) {
        final Plugin plugin = BattleArena.getInstance();

        final CuboidRegion srcRegion = new CuboidRegion(
                BlockVector3.at(srcBounds.getMinX(), srcBounds.getMinY(), srcBounds.getMinZ()),
                BlockVector3.at(srcBounds.getMaxX(), srcBounds.getMaxY(), srcBounds.getMaxZ())
        );
        final CuboidRegion dstRegion = new CuboidRegion(
                BlockVector3.at(dstBounds.getMinX(), dstBounds.getMinY(), dstBounds.getMinZ()),
                BlockVector3.at(dstBounds.getMaxX(), dstBounds.getMaxY(), dstBounds.getMaxZ())
        );
        final BlockVector3 pasteAt = dstRegion.getMinimumPoint();

        PasteGuard guard = new PasteGuard(plugin, newWorld, dstRegion, 1);
        guard.enable();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (EditSession session = WorldEdit.getInstance()
                    .newEditSessionBuilder()
                    .world(BukkitAdapter.adapt(newWorld))
                    .fastMode(true)
                    .maxBlocks(-1)
                    .relightMode(RelightMode.OPTIMAL)
                    .build()
            ) {
                final var sourceExtent = BukkitAdapter.adapt(oldWorld);

                final ForwardExtentCopy fec = new ForwardExtentCopy(
                        sourceExtent,
                        srcRegion,
                        session,
                        pasteAt
                );
                fec.setCopyingEntities(false);
                fec.setRemovingEntities(false);
                fec.setCopyingBiomes(false);
                fec.setSourceMask(new ExistingBlockMask(sourceExtent)); // skip reading air

                Operations.complete(fec);

                session.flushQueue();

            } catch (Throwable t) {
                BattleArena.getInstance().error("Async FAWE copy failed", t);
            } finally {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    guard.disable();
                    if (onComplete != null) {
                        onComplete.run();
                    }
                });
            }
        });

        return true;
    }


    public static void runOperationSliced(Plugin plugin, Operation op, long nanosPerTick, Runnable onComplete) {
        final Operation[] cur = { op };
        new BukkitRunnable() {
            @Override public void run() {
                long start = System.nanoTime();
                try {
                    while (cur[0] != null && (System.nanoTime() - start) < nanosPerTick) {
                        cur[0] = cur[0].resume(null);
                    }
                    if (cur[0] == null) {
                        cancel();
                        if (onComplete != null) onComplete.run();
                    }
                } catch (WorldEditException ex) {
                    BattleArena.getInstance().error("WorldEdit operation failed while copying", ex);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);   // run every tick, not every 5 ticks
    }

    public static boolean pasteSchematic(String map, String arena, World world, Bounds bounds) {
        return pasteSchematic(map, arena, world, bounds, null);
    }

    public static boolean pasteSchematic(String map, String arena, World world, Bounds bounds, Runnable onComplete) {
        final Plugin plugin = BattleArena.getInstance();

        final CuboidRegion region = new CuboidRegion(
                BlockVector3.at(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ()),
                BlockVector3.at(bounds.getMaxX(), bounds.getMaxY(), bounds.getMaxZ())
        );

        Path path = plugin.getDataFolder().toPath()
                .resolve("schematics")
                .resolve(arena.toLowerCase(Locale.ROOT))
                .resolve(map.toLowerCase(Locale.ROOT) + "." +
                        BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getPrimaryFileExtension());

        if (Files.notExists(path)) {
            plugin.getLogger().warning("Schematic not found: " + path);
            path = plugin.getDataFolder().toPath()
                    .resolve("schematics")
                    .resolve(arena.toLowerCase(Locale.ROOT))
                    .resolve(map.toLowerCase(Locale.ROOT) + "." +
                            BuiltInClipboardFormat.MCEDIT_SCHEMATIC.getPrimaryFileExtension());
            if (Files.notExists(path)) {
                plugin.getLogger().warning("Schematic not found: " + path);
                return false;
            }
        }

        final Path schematicPath = path; // effectively final

        final PasteGuard guard = new PasteGuard(plugin, world, region, 1);
        guard.enable();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Clipboard clipboard;
            try (ClipboardReader reader = ClipboardFormats.findByFile(schematicPath.toFile())
                    .getReader(Files.newInputStream(schematicPath))) {
                clipboard = reader.read();
            } catch (IOException ex) {
                plugin.getLogger().severe("Failed to read schematic: " + ex.getMessage());
                Bukkit.getScheduler().runTask(plugin, guard::disable);
                return;
            }

            try (EditSession session = WorldEdit.getInstance()
                    .newEditSessionBuilder()
                    .world(BukkitAdapter.adapt(world))
                    .fastMode(true)
                    .maxBlocks(-1)
                    .relightMode(RelightMode.OPTIMAL)
                    .build()
            ) {
                Operation paste = new ClipboardHolder(clipboard)
                        .createPaste(session)
                        .to(BlockVector3.at(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ()))
                        .ignoreAirBlocks(true)
                        .build();

                Operations.complete(paste);
                session.flushQueue();
            } catch (Throwable t) {
                BattleArena.getInstance().error("Async FAWE paste failed", t);
            } finally {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    guard.disable();
                    if (onComplete != null) {
                        onComplete.run();
                    }
                });
            }
        });

        return true;
    }

    public static void unticketRegion(World world, Bounds b, Plugin plugin) {
        int minChunkX = Math.floorDiv(b.getMinX(), 16);
        int minChunkZ = Math.floorDiv(b.getMinZ(), 16);
        int maxChunkX = Math.floorDiv(b.getMaxX(), 16);
        int maxChunkZ = Math.floorDiv(b.getMaxZ(), 16);

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                org.bukkit.Chunk chunk = world.getChunkAt(cx, cz);
                // This removes *your* plugin’s ticket on that chunk
                chunk.removePluginChunkTicket(plugin);
            }
        }
    }


    public static void wipeRegionAsync(World world, Bounds b, Runnable onDone) {
        final Plugin plugin = BattleArena.getInstance();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
            final CuboidRegion region = new CuboidRegion(
                    BlockVector3.at(b.getMinX(), b.getMinY(), b.getMinZ()),
                    BlockVector3.at(b.getMaxX(), b.getMaxY(), b.getMaxZ())
            );

            try (EditSession session = WorldEdit.getInstance()
                    .newEditSessionBuilder()
                    .world(weWorld)
                    .maxBlocks(-1)
                    .fastMode(true)
                    .build()) {

                session.setSideEffectApplier(
                        SideEffectSet.none()
                                .with(SideEffect.LIGHTING,  SideEffect.State.OFF)
                                .with(SideEffect.NEIGHBORS, SideEffect.State.OFF)
                                .with(SideEffect.UPDATE,    SideEffect.State.OFF)
                );

                Pattern air = new BlockPattern(BlockTypes.AIR.getDefaultState());
                session.setBlocks((Region) region, air);
            } catch (Exception ignored) {
            }

            if (onDone != null) {
                // Call user callback back on the server thread
                Bukkit.getScheduler().runTask(plugin, onDone);
            }
        });
    }

}
