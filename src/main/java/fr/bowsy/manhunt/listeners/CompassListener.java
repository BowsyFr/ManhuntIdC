package fr.bowsy.manhunt.listeners;

import fr.bowsy.manhunt.ManhuntPlugin;
import fr.bowsy.manhunt.managers.GameManager;
import fr.bowsy.manhunt.models.GameState;
import fr.bowsy.manhunt.models.ManhuntTeam;
import fr.bowsy.manhunt.utils.ChatUtils;
import fr.bowsy.manhunt.utils.ManhuntCompass;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

public class CompassListener implements Listener {

    private final ManhuntPlugin plugin;
    private final GameManager gm;

    public CompassListener(ManhuntPlugin plugin) {
        this.plugin = plugin;
        this.gm = plugin.getGameManager();
    }

    @EventHandler
    public void onCompassUse(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;
        if (event.getItem() == null || event.getItem().getType() != Material.COMPASS) return;
        if (!ManhuntCompass.isManhuntCompass(event.getItem())) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ManhuntTeam team = gm.getTeamOfPlayer(player.getUniqueId());
        if (team == null || team.getState() != GameState.RUNNING) return;

        boolean isRunner = player.getUniqueId().equals(team.getRunnerId());
        boolean isHunter = team.getHunterIds().contains(player.getUniqueId());

        if (isRunner) {
            // Le runner voit le pseudo + distance du chasseur le plus proche
            gm.updateRunnerCompass(team);
        } else if (isHunter) {
            Player runner = plugin.getServer().getPlayer(team.getRunnerId());
            if (runner == null) {
                player.sendMessage(ChatUtils.colorComponent("&7Le runner est hors-ligne."));
                return;
            }
            if (team.isStealthActive()) {
                player.sendMessage(ChatUtils.colorComponent(
                        "&7Le runner est &5furtif&7 \u2014 localisation impossible pendant "
                                + getRemainingStealthSeconds(team) + "s."));
            } else if (runner.getWorld().equals(player.getWorld())) {
                // Hunters : pas de distance, juste confirmation que la boussole est active
                player.sendMessage(ChatUtils.colorComponent("&6\uD83E\uDDED Boussole active \u2014 le runner est dans votre dimension."));
            } else {
                player.sendMessage(ChatUtils.colorComponent("&7Le runner est dans une autre dimension : &e"
                        + runner.getWorld().getName()));
            }
        }
    }

    private int getRemainingStealthSeconds(ManhuntTeam team) {
        int duration = plugin.getConfig().getInt("settings.stealth-potion-duration", 240);
        long elapsed = (System.currentTimeMillis() - team.getStealthStartTime()) / 1000;
        return (int) Math.max(0, duration - elapsed);
    }
}