package fr.bowsy.manhunt.listeners;

import fr.bowsy.manhunt.ManhuntPlugin;
import fr.bowsy.manhunt.managers.GameManager;
import fr.bowsy.manhunt.models.GameState;
import fr.bowsy.manhunt.models.ManhuntTeam;
import fr.bowsy.manhunt.utils.ChatUtils;
import fr.bowsy.manhunt.utils.StealthPotionRecipe;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

public class PlayerListener implements Listener {

    private final ManhuntPlugin plugin;
    private final GameManager gm;

    public PlayerListener(ManhuntPlugin plugin) {
        this.plugin = plugin;
        this.gm = plugin.getGameManager();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        ManhuntTeam team = gm.getTeamOfPlayer(player.getUniqueId());
        if (team == null || team.getState() != GameState.RUNNING) return;

        if (player.getUniqueId().equals(team.getRunnerId())) {
            event.setDeathMessage(null);
            gm.onRunnerDeath(team);
        } else if (team.getHunterIds().contains(player.getUniqueId())) {
            boolean eliminated = gm.onHunterDeath(team, player);
            if (eliminated) {
                event.setDeathMessage(null);
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        player.setGameMode(GameMode.SPECTATOR);
                    }
                }.runTaskLater(plugin, 1L);
            }
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        ManhuntTeam team = gm.getTeamOfPlayer(player.getUniqueId());
        if (team == null || team.getState() != GameState.RUNNING) return;

        if (team.getOverworld() != null) {
            event.setRespawnLocation(team.getOverworld().getSpawnLocation());
        }

        if (!player.getUniqueId().equals(team.getRunnerId())) {
            int invincTicks = plugin.getConfig().getInt("settings.respawn-invincibility", 5) * 20;
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isOnline()) {
                        player.setNoDamageTicks(invincTicks);
                    }
                }
            }.runTaskLater(plugin, 2L);
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || !StealthPotionRecipe.isStealthPotion(item)) return;

        ManhuntTeam team = gm.getTeamOfPlayer(player.getUniqueId());
        if (team == null || team.getState() != GameState.RUNNING) return;

        if (!player.getUniqueId().equals(team.getRunnerId())) {
            player.sendMessage(ChatUtils.colorComponent("&cSeul le chassé peut utiliser cette potion !"));
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);

        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
        } else {
            player.getInventory().removeItem(item);
        }

        gm.activateStealth(team, player);
    }

    @EventHandler
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ManhuntTeam team = gm.getTeamOfPlayer(player.getUniqueId());
        if (team == null || team.getState() != GameState.RUNNING) return;
        if (!player.getUniqueId().equals(team.getRunnerId())) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                gm.checkRunnerObjective(team, player);
            }
        }.runTaskLater(plugin, 1L);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        Player player = event.getPlayer();
        ManhuntTeam team = gm.getTeamOfPlayer(player.getUniqueId());
        if (team == null || team.getState() != GameState.RUNNING) return;

        if (player.getUniqueId().equals(team.getRunnerId())) {
            gm.updateRunnerCompass(team);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        ManhuntTeam team = gm.getTeamOfPlayer(player.getUniqueId());
        if (team == null || team.getState() != GameState.RUNNING) return;

        if (player.getUniqueId().equals(team.getRunnerId())) {
            ChatUtils.broadcast(team, plugin, "&cLe chassé a quitté la partie !");
            gm.endGame(team, "hunters-win");
        } else {
            ChatUtils.broadcast(team, plugin, "&c" + player.getName() + " a quitté (compté comme une mort) !");
            gm.onHunterDeath(team, player);
        }
    }
}