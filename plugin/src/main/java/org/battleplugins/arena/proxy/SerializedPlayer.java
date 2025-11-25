package org.battleplugins.arena.proxy;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.board.BendingBoard;
import com.projectkorra.projectkorra.board.BendingBoardManager;
import com.projectkorra.projectkorra.event.PlayerChangeSubElementEvent;
import com.projectkorra.projectkorra.object.EarthCosmetic;
import org.battleplugins.arena.BattleArena;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class SerializedPlayer {
    private HashMap<Integer, String> abilities = new HashMap<>();
    private List<Elements> elements = new ArrayList<>();
    private final String uuid;

    public SerializedPlayer(String uuid) {
        this.uuid = uuid;
    }

    public SerializedPlayer(String uuid, List<Elements> elements, HashMap<Integer, String> abilities) {
        this.uuid = uuid;
        this.elements = elements;
        this.abilities = abilities;
    }

    public List<Elements> getElements() {
        return elements;
    }

    public HashMap<Integer, String> getAbilities() {
        return abilities;
    }

    public String getUuid() {
        return uuid;
    }

    public void start(Player player) {
        Bukkit.getScheduler().runTaskLater(BattleArena.getInstance(), () -> {
            BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);

            bPlayer.removeUnusableAbilities();
            bPlayer.toggleBending();
            bPlayer.toggleBending();
            BendingBoardManager.getBoard(player).ifPresent(board -> {
                BattleArena.getInstance().info("Refreshing board");
                board.updateAll();
            });
        }, 20);
    }

    public static SerializedPlayer toSerializedPlayer(final Player player) {
        List<Elements> elements = new ArrayList<>();

        BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);

        for (Element element : bPlayer.getElements()) {
            if (element == Element.AIR) {
                elements.add(Elements.AIR);
            } else if (element == Element.EARTH) {
                elements.add(Elements.EARTH);

            } else if (element == Element.WATER) {
                elements.add(Elements.WATER);
            } else if (element == Element.FIRE) {
                elements.add(Elements.FIRE);
            } else if (element == Element.CHI) {
                elements.add(Elements.CHI);
            }
        }

        HashMap<Integer, String> abilities = new HashMap<>(bPlayer.getAbilities());

        return new SerializedPlayer(player.getUniqueId().toString(), elements, abilities);
    }
}
