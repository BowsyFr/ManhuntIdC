package fr.bowsy.manhunt.utils;

import fr.bowsy.manhunt.ManhuntPlugin;
import fr.bowsy.manhunt.models.ManhuntTeam;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

public class ChatUtils {

    private static final LegacyComponentSerializer SERIALIZER =
            LegacyComponentSerializer.legacyAmpersand();

    /**
     * Convertit les codes couleur &x en codes §x.
     * Utilise la désérialisation puis re-sérialisation legacy § pour compatibilité.
     */
    public static String color(String msg) {
        // Désérialise les codes & puis re-sérialise en codes § (legacy)
        return LegacyComponentSerializer.legacySection()
                .serialize(SERIALIZER.deserialize(msg));
    }

    /**
     * Retourne un Component Adventure depuis une chaîne avec codes &.
     */
    public static Component colorComponent(String msg) {
        return SERIALIZER.deserialize(msg);
    }

    /** Broadcast à tous les joueurs d'une équipe. */
    public static void broadcast(ManhuntTeam team, ManhuntPlugin plugin, String msg) {
        String prefix = plugin.getConfig().getString("messages.prefix", "&8[&6Manhunt&8] &r");
        Component component = colorComponent(prefix + msg);
        for (UUID uid : team.getAllPlayerIds()) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null) p.sendMessage(component);
        }
    }

    /** Broadcast à tous les joueurs connectés. */
    public static void broadcastAll(ManhuntPlugin plugin, String msg) {
        String prefix = plugin.getConfig().getString("messages.prefix", "&8[&6Manhunt&8] &r");
        Component component = colorComponent(prefix + msg);
        Bukkit.broadcast(component);
    }

    public static void send(Player player, ManhuntPlugin plugin, String msg) {
        String prefix = plugin.getConfig().getString("messages.prefix", "&8[&6Manhunt&8] &r");
        player.sendMessage(colorComponent(prefix + msg));
    }
}