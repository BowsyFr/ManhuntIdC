package fr.bowsy.manhunt.utils;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.List;

public class StealthPotionRecipe {

    public static final String STEALTH_KEY = "manhunt_stealth_potion";

    /**
     * Crée et retourne la recette de la potion furtive.
     * Recette :
     *   [  ][FD][  ]
     *   [FD][MU][FD]
     *   [  ][GT][  ]
     * FD = Fleur de muguet (Lily of the Valley)
     * MU = Bouteille de potion vide
     * GT = Poudre de Blaze (énergie)
     */
    public static ShapedRecipe createRecipe(Plugin plugin) {
        ItemStack result = createStealthPotion();
        NamespacedKey key = new NamespacedKey(plugin, STEALTH_KEY);

        ShapedRecipe recipe = new ShapedRecipe(key, result);
        recipe.shape(
                " L ",
                "LPL",
                " B "
        );
        recipe.setIngredient('L', Material.LILY_OF_THE_VALLEY); // Fleur de muguet
        recipe.setIngredient('P', Material.GLASS_BOTTLE);        // Flacon vide
        recipe.setIngredient('B', Material.BLAZE_POWDER);        // Poudre de Blaze

        return recipe;
    }

    /** Crée l'ItemStack représentant la potion furtive. */
    public static ItemStack createStealthPotion() {
        ItemStack item = new ItemStack(Material.GLASS_BOTTLE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatUtils.color("&5✦ Potion Furtive"));
            meta.setLore(List.of(
                    ChatUtils.color("&7Empêche les chasseurs de vous"),
                    ChatUtils.color("&7localiser via la boussole pendant"),
                    ChatUtils.color("&d4 minutes&7."),
                    ChatUtils.color(""),
                    ChatUtils.color("&8Usage unique - Craftable une seule fois")
            ));
            // Utiliser un tag persistant pour identifier cet item
            meta.getPersistentDataContainer(); // accessible via listeners
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Vérifie si un ItemStack est une potion furtive. */
    public static boolean isStealthPotion(ItemStack item) {
        if (item == null || item.getType() != Material.GLASS_BOTTLE) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null
                && meta.hasDisplayName()
                && meta.getDisplayName().contains("Potion Furtive");
    }
}
