package fr.bowsy.manhunt.utils;

import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Utilitaire pour créer les boussoles Manhunt identifiables et non-droppables.
 */
public class ManhuntCompass {

    private static final String HUNTER_COMPASS_NAME = "\u00a76\u00a7lBoussole du Chasseur";
    private static final String RUNNER_COMPASS_NAME = "\u00a7c\u00a7lBoussole du Chassé";

    /**
     * Crée la boussole des hunters.
     */
    public static ItemStack createHunterCompass() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(HUNTER_COMPASS_NAME);
            meta.setLore(List.of(
                    ChatUtils.color("&7Indique la position du runner."),
                    ChatUtils.color("&8Clic droit pour info.")
            ));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Crée la boussole du runner.
     */
    public static ItemStack createRunnerCompass() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(RUNNER_COMPASS_NAME);
            meta.setLore(List.of(
                    ChatUtils.color("&7Indique le chasseur le plus proche."),
                    ChatUtils.color("&8Clic droit pour info.")
            ));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Vérifie si un ItemStack est une boussole Manhunt (hunter ou runner).
     */
    public static boolean isManhuntCompass(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return false;
        String name = meta.getDisplayName();
        return name.equals(HUNTER_COMPASS_NAME) || name.equals(RUNNER_COMPASS_NAME);
    }

    /**
     * Vérifie si c'est spécifiquement la boussole hunter.
     */
    public static boolean isHunterCompass(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName() && meta.getDisplayName().equals(HUNTER_COMPASS_NAME);
    }
}