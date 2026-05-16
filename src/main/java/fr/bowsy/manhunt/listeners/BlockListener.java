package fr.bowsy.manhunt.listeners;

import fr.bowsy.manhunt.ManhuntPlugin;
import fr.bowsy.manhunt.managers.GameManager;
import fr.bowsy.manhunt.models.GameState;
import fr.bowsy.manhunt.models.ManhuntTeam;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;

public class BlockListener implements Listener {

    private final ManhuntPlugin plugin;
    private final GameManager gm;

    public BlockListener(ManhuntPlugin plugin) {
        this.plugin = plugin;
        this.gm = plugin.getGameManager();
    }

    /**
     * CutClean : Si le runner mine un bloc fondable, on remplace les drops
     * par leur équivalent fondu.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ManhuntTeam team = gm.getTeamOfPlayer(player.getUniqueId());
        if (team == null || team.getState() != GameState.RUNNING) return;
        if (!player.getUniqueId().equals(team.getRunnerId())) return;

        Material blockType = event.getBlock().getType();
        Material smelted = CraftListener.getSmeltedResult(blockType);
        if (smelted == null) return;

        // Supprimer les drops vanilla et donner le résultat fondu
        event.setDropItems(false);

        // Calculer la quantité (silk touch = pas de CutClean)
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool.containsEnchantment(org.bukkit.enchantments.Enchantment.SILK_TOUCH)) return;

        // Fortune
        int amount = 1;
        int fortune = tool.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.FORTUNE);
        if (fortune > 0 && isOreThatBenefitsFromFortune(blockType)) {
            // Fortune donne +1 par niveau avec 1/3 de chance (simplifié : 1 + random(fortune))
            amount += (int)(Math.random() * (fortune + 1));
        }

        ItemStack drop = new ItemStack(smelted, amount);
        event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), drop);

        // Vérifier objectif après minage
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                gm.checkRunnerObjective(team, player);
            }
        }.runTaskLater(plugin, 1L);
    }

    private boolean isOreThatBenefitsFromFortune(Material mat) {
        return switch (mat) {
            case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE,
                 EMERALD_ORE, DEEPSLATE_EMERALD_ORE,
                 LAPIS_ORE, DEEPSLATE_LAPIS_ORE,
                 NETHER_QUARTZ_ORE, COAL_ORE, DEEPSLATE_COAL_ORE -> true;
            default -> false;
        };
    }
}
