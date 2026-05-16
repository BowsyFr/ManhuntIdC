package fr.bowsy.manhunt.listeners;

import fr.bowsy.manhunt.ManhuntPlugin;
import fr.bowsy.manhunt.managers.GameManager;
import fr.bowsy.manhunt.models.GameState;
import fr.bowsy.manhunt.models.ManhuntTeam;
import fr.bowsy.manhunt.utils.ChatUtils;
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
        // Éviter double-appel (main + off-hand)
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;
        if (event.getItem() == null || event.getItem().getType() != Material.COMPASS) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ManhuntTeam team = gm.getTeamOfPlayer(player.getUniqueId());
        if (team == null || team.getState() != GameState.RUNNING) return;

        boolean isRunner = player.getUniqueId().equals(team.getRunnerId());
        boolean isHunter = team.getHunterIds().contains(player.getUniqueId());

        if (isRunner) {
            // Runner → afficher chasseur le plus proche
            gm.updateRunnerCompass(team);
            player.sendMessage(ChatUtils.color("&6🧭 Boussole mise à jour vers le chasseur le plus proche."));
        } else if (isHunter) {
            // Chasseur → afficher info runner
            Player runner = plugin.getServer().getPlayer(team.getRunnerId());
            if (runner == null) {
                player.sendMessage(ChatUtils.color("&7Le runner est hors-ligne."));
                return;
            }
            if (team.isStealthActive()) {
                player.sendMessage(ChatUtils.color("&7Le runner est &5furtif&7 — localisation impossible pendant "
                        + getRemainingStealthSeconds(team) + "s."));
            } else if (runner.getWorld().equals(player.getWorld())) {
                double dist = runner.getLocation().distance(player.getLocation());
                player.sendMessage(ChatUtils.color("&6🧭 Runner à &e" + (int) dist + " blocs &6de vous."));
            } else {
                player.sendMessage(ChatUtils.color("&7Le runner est dans une autre dimension : &e"
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
