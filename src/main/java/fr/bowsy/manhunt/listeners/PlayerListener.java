package fr.bowsy.manhunt.listeners;

import fr.bowsy.manhunt.ManhuntPlugin;
import fr.bowsy.manhunt.managers.GameManager;
import fr.bowsy.manhunt.models.GameState;
import fr.bowsy.manhunt.models.ManhuntTeam;
import fr.bowsy.manhunt.utils.ChatUtils;
import fr.bowsy.manhunt.utils.ManhuntCompass;
import fr.bowsy.manhunt.utils.StealthPotionRecipe;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
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

    /**
     * Mort d'un joueur.
     * - Runner : fin de partie
     * - Hunter : on annule la mort, on applique la pénalité (clear + immobilisation)
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        ManhuntTeam team = gm.getTeamOfPlayer(player.getUniqueId());
        if (team == null || team.getState() != GameState.RUNNING) return;

        if (player.getUniqueId().equals(team.getRunnerId())) {
            event.setDeathMessage(null);
            gm.onRunnerDeath(team);
        } else if (team.getHunterIds().contains(player.getUniqueId())) {
            // Annuler la mort : on garde les items dans l'event pour que rien ne tombe
            event.setDeathMessage(null);
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setDroppedExp(0);

            new BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isOnline()) {
                        // Soigner le joueur avant d'appeler la pénalité
                        player.setHealth(player.getMaxHealth());
                        player.setFoodLevel(20);
                        gm.onHunterDeath(team, player);
                    }
                }
            }.runTaskLater(plugin, 1L);
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

    /**
     * Utilisation de la Potion Furtive (clic droit / consommation).
     * La potion est désormais de type POTION (buvable), donc on intercepte
     * le clic droit ET la consommation.
     */
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

        // On laisse l'animation de boisson se faire, la consommation est gérée par onPlayerItemConsume
    }

    /**
     * Consommation de la Potion Furtive.
     */
    @EventHandler
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (!StealthPotionRecipe.isStealthPotion(item)) return;

        ManhuntTeam team = gm.getTeamOfPlayer(player.getUniqueId());
        if (team == null || team.getState() != GameState.RUNNING) return;

        if (!player.getUniqueId().equals(team.getRunnerId())) {
            event.setCancelled(true);
            player.sendMessage(ChatUtils.colorComponent("&cSeul le chassé peut utiliser cette potion !"));
            return;
        }

        // Annuler les effets par défaut de la potion d'invisibilité (on gère nous-mêmes)
        event.setCancelled(true);

        // Retirer la potion de l'inventaire manuellement
        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
        } else {
            player.getInventory().remove(item);
        }

        gm.activateStealth(team, player);
    }

    /**
     * Empêche le drop de la boussole Manhunt.
     */
    @EventHandler
    public void onItemDrop(PlayerDropItemEvent event) {
        if (ManhuntCompass.isManhuntCompass(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    /**
     * Empêche le drop de la boussole via l'inventaire (shift-click vers l'extérieur, etc.)
     */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        if (ManhuntCompass.isManhuntCompass(current) || ManhuntCompass.isManhuntCompass(cursor)) {
            // Autoriser les déplacements dans l'inventaire, bloquer uniquement le drop (slot -999)
            if (event.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.OUTSIDE) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * Pickup d'items — vérification de l'objectif.
     */
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

    /**
     * Bloquer la commande /pvp pendant une partie.
     */
    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String cmd = event.getMessage().toLowerCase().trim();
        if (cmd.startsWith("/pvp")) {
            Player player = event.getPlayer();
            if (gm.isPlayerInActiveGame(player.getUniqueId())) {
                event.setCancelled(true);
                player.sendMessage(ChatUtils.colorComponent("&cLa commande &e/pvp&c est désactivée pendant une partie Manhunt !"));
            }
        }
    }
}