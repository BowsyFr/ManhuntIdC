package fr.bowsy.manhunt.utils;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionType;

import java.util.List;

/**
 * Recette de la Potion Furtive.
 *
 * Disposition :
 *   A B C
 *   D E D
 *     D
 *
 * A = Lily of the Valley (muguet)
 * B = Nether Quartz
 * C = Paper
 * D = Glass Bottle
 * E = Water Bucket
 */
public class StealthPotionRecipe {

    public static final String STEALTH_KEY = "manhunt_stealth_potion";

    public static ShapedRecipe createRecipe(Plugin plugin) {
        ItemStack result = createStealthPotion();
        NamespacedKey key = new NamespacedKey(plugin, STEALTH_KEY);

        ShapedRecipe recipe = new ShapedRecipe(key, result);
        recipe.shape(
                "ABC",
                "DED",
                " D "
        );
        recipe.setIngredient('A', Material.LILY_OF_THE_VALLEY);
        recipe.setIngredient('B', Material.QUARTZ);
        recipe.setIngredient('C', Material.PAPER);
        recipe.setIngredient('D', Material.GLASS_BOTTLE);
        recipe.setIngredient('E', Material.WATER_BUCKET);

        return recipe;
    }

    /**
     * Crée la Potion Furtive :
     * - Base WATER → potion blanche, aucun effet vanilla
     * - Couleur violette personnalisée
     * - Tous les flags cachés → aucun tooltip vanilla (effets, attributs...)
     * - Lore entièrement custom
     * Pas d'effet d'invisibilité appliqué : elle fait uniquement disparaître
     * le runner de la boussole des hunters.
     */
    public static ItemStack createStealthPotion() {
        ItemStack item = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) item.getItemMeta();
        if (meta != null) {
            // WATER = potion sans effet, couleur blanche de base
            meta.setBasePotionType(PotionType.WATER);
            // Teinte violette pour distinguer visuellement
            meta.setColor(Color.fromRGB(160, 50, 220));

            // Masquer TOUT ce que Minecraft injecte automatiquement
            meta.addItemFlags(
                    ItemFlag.HIDE_ATTRIBUTES,
                    ItemFlag.HIDE_ENCHANTS,
                    ItemFlag.HIDE_ADDITIONAL_TOOLTIP  // cache "Effets :" et la liste vanilla
            );

            // Nom et lore 100% custom (codes couleur en Unicode pour éviter bugs d'encodage)
            meta.setDisplayName("\u00a75\u00a7l\u2756 Potion Furtive");
            meta.setLore(List.of(
                    "\u00a77Fait dispara\u00eetre le chas\u00e9 de la",
                    "\u00a77boussole des chasseurs pendant",
                    "\u00a7d4 minutes\u00a77.",
                    "",
                    "\u00a78Usage unique \u2014 Craftable une seule fois"
            ));

            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Vérifie si un ItemStack est une Potion Furtive Manhunt.
     */
    public static boolean isStealthPotion(ItemStack item) {
        if (item == null || item.getType() != Material.POTION) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null
                && meta.hasDisplayName()
                && meta.getDisplayName().contains("Potion Furtive");
    }
}