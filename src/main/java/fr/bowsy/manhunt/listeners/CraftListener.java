package fr.bowsy.manhunt.listeners;

import fr.bowsy.manhunt.ManhuntPlugin;
import fr.bowsy.manhunt.managers.GameManager;
import fr.bowsy.manhunt.models.GameState;
import fr.bowsy.manhunt.models.ManhuntTeam;
import fr.bowsy.manhunt.utils.ChatUtils;
import fr.bowsy.manhunt.utils.StealthPotionRecipe;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class CraftListener implements Listener {

    private final ManhuntPlugin plugin;
    private final GameManager gm;

    private final Map<UUID, Boolean> stealthCrafted = new HashMap<>();

    public CraftListener(ManhuntPlugin plugin) {
        this.plugin = plugin;
        this.gm = plugin.getGameManager();
        plugin.getServer().addRecipe(StealthPotionRecipe.createRecipe(plugin));
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (event.getRecipe() == null) return;
        ItemStack result = event.getRecipe().getResult();
        if (!StealthPotionRecipe.isStealthPotion(result)) return;

        if (!(event.getView().getPlayer() instanceof Player player)) return;

        if (stealthCrafted.getOrDefault(player.getUniqueId(), false)) {
            event.getInventory().setResult(null);
            player.sendMessage(ChatUtils.colorComponent("&cVous avez déjà crafté la potion furtive !"));
        }
    }

    @EventHandler
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        ItemStack result = event.getRecipe().getResult();

        if (StealthPotionRecipe.isStealthPotion(result)) {
            ManhuntTeam team = gm.getTeamOfPlayer(player.getUniqueId());
            if (team != null && team.getState() == GameState.RUNNING
                    && player.getUniqueId().equals(team.getRunnerId())) {
                stealthCrafted.put(player.getUniqueId(), true);
                player.sendMessage(ChatUtils.colorComponent("&5✦ Potion furtive craftée ! Usage unique."));
            }
        }

        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                ManhuntTeam team = gm.getTeamOfPlayer(player.getUniqueId());
                if (team != null && team.getState() == GameState.RUNNING
                        && player.getUniqueId().equals(team.getRunnerId())) {
                    gm.checkRunnerObjective(team, player);
                }
            }
        }.runTaskLater(plugin, 1L);
    }

    public static Material getSmeltedResult(Material raw) {
        return switch (raw) {
            case IRON_ORE, DEEPSLATE_IRON_ORE, RAW_IRON -> Material.IRON_INGOT;
            case GOLD_ORE, DEEPSLATE_GOLD_ORE, NETHER_GOLD_ORE, RAW_GOLD -> Material.GOLD_INGOT;
            case COPPER_ORE, DEEPSLATE_COPPER_ORE, RAW_COPPER -> Material.COPPER_INGOT;
            case ANCIENT_DEBRIS -> Material.NETHERITE_SCRAP;
            case SAND -> Material.GLASS;
            case GRAVEL -> Material.FLINT;
            case COBBLESTONE -> Material.STONE;
            case COBBLED_DEEPSLATE -> Material.DEEPSLATE;
            default -> null;
        };
    }

    public void resetCrafts(UUID playerId) {
        stealthCrafted.remove(playerId);
    }
}