package org.battleplugins.arena.module.restoration;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.ClipboardHolder;
import org.battleplugins.arena.Arena;
import org.battleplugins.arena.BattleArena;
import org.battleplugins.arena.competition.LiveCompetition;
import org.battleplugins.arena.competition.map.options.Bounds;
import org.battleplugins.arena.util.BlockUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class ArenaRestorationUtil {

    public static void restoreArena(ArenaRestoration module, Arena arena, LiveCompetition<?> competition, Bounds bounds) {
        Path path = module.getSchematicPath(arena, competition);
        if (Files.notExists(path)) {
            // No schematic found
            arena.getPlugin().warn("Could not restore map {} for arena {} as no schematic was found!", competition.getMap().getName(), arena.getName());
            return;
        }

        // Restore the arena
        Clipboard clipboard;
        ClipboardFormat format = ClipboardFormats.findByFile(path.toFile());
        if (format == null) {
            // Invalid format
            arena.getPlugin().warn("Could not restore map {} for arena {} as the schematic format is invalid!", competition.getMap().getName(), arena.getName());
            return;
        }

        try (ClipboardReader reader = format.getReader(Files.newInputStream(path))) {
            clipboard = reader.read();
        } catch (IOException e) {
            // Error reading schematic
            arena.getPlugin().error("Failed to restore map {} for arena {} due to an error reading the schematic!", competition.getMap().getName(), arena.getName(), e);
            return;
        }

        EditSession session = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(competition.getMap().getWorld()));
        Operation operation = new ClipboardHolder(clipboard).createPaste(session)
                .to(BlockVector3.at(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ()))
                .build();

        BlockUtil.runOperationSliced(BattleArena.getInstance(), operation, 7_000_000L, session::close);
    }
}
