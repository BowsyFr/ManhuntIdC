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

import java.util.UUID;

public class PlayerListener implements Listener {

    private final ManhuntPlugin plugin;
    private final GameManager gm;

    public PlayerListener(ManhuntPlugin plugin) {
        this.plugin = plugin;
        this.gm = plugin.getGameManager();
    }

    // ── Mort d'un joueur ─────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        ManhuntTeam team = gm.getTeamOfPlayer(player.getUniqueId());
        if (team == null || team.getState() != GameState.RUNNING) return;

        if (player.getUniqueId().equals(team.getRunnerId())) {
            // Runner mort → victoire chasseurs
            event.setDeathMessage(null);
            gm.onRunnerDeath(team);
        } else if (team.getHunterIds().contains(player.getUniqueId())) {
            // Chasseur mort → décompte vies
            boolean eliminated = gm.onHunterDeath(team, player);
            if (eliminated) {
                event.setDeathMessage(null);
                // Passer en spectateur au prochain tick (après le respawn)
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        player.setGameMode(GameMode.SPECTATOR);
                    }
                }.runTaskLater(plugin, 1L);
            }
        }
    }

    // ── Respawn ──────────────────────────────────────────────────────────────

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        ManhuntTeam team = gm.getTeamOfPlayer(player.getUniqueId());
        if (team == null || team.getState() != GameState.RUNNING) return;

        // Faire respawn dans le monde de l'équipe
        if (team.getOverworld() != null) {
            event.setRespawnLocation(team.getOverworld().getSpawnLocation());
        }

        // Invincibilité temporaire après respawn (seulement chasseurs)
        if (!player.getUniqueId().equals(team.getRunnerId())) {
            int invincTicks = plugin.getConfig().getInt("settings.respawn-invincibility", 5) * 20;
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isOnline()) {
                        // invincibilité gérée nativement par Minecraft (noDamageTicks)
                        player.setNoDamageTicks(invincTicks);
                    }
                }
            }.runTaskLater(plugin, 2L);
        }
    }

    // ── Utilisation de la potion furtive ─────────────────────────────────────

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || !StealthPotionRecipe.isStealthPotion(item)) return;

        ManhuntTeam team = gm.getTeamOfPlayer(player.getUniqueId());
        if (team == null || team.getState() != GameState.RUNNING) return;

        // Seul le runner peut utiliser la potion
        if (!player.getUniqueId().equals(team.getRunnerId())) {
            player.sendMessage(ChatUtils.color("&cSeul le chassé peut utiliser cette potion !"));
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);

        // Consommer l'item
        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
        } else {
            player.getInventory().removeItem(item);
        }

        // Activer la furtivité
        gm.activateStealth(team, player);
    }

    // ── Vérification objectif (pickup d'item) ────────────────────────────────

    @EventHandler
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ManhuntTeam team = gm.getTeamOfPlayer(player.getUniqueId());
        if (team == null || team.getState() != GameState.RUNNING) return;
        if (!player.getUniqueId().equals(team.getRunnerId())) return;

        // Vérifier après le pickup (tick suivant)
        new BukkitRunnable() {
            @Override
            public void run() {
                gm.checkRunnerObjective(team, player);
            }
        }.runTaskLater(plugin, 1L);
    }

    // ── Mise à jour boussole runner (mouvement) ───────────────────────────────

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        // Optimisation : seulement si déplacement significatif (bloc changé)
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        Player player = event.getPlayer();
        ManhuntTeam team = gm.getTeamOfPlayer(player.getUniqueId());
        if (team == null || team.getState() != GameState.RUNNING) return;

        // Mettre à jour boussole du runner si c'est lui qui bouge
        if (player.getUniqueId().equals(team.getRunnerId())) {
            gm.updateRunnerCompass(team);
        }
    }

    // ── Quitter le serveur ───────────────────────────────────────────────────

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        ManhuntTeam team = gm.getTeamOfPlayer(player.getUniqueId());
        if (team == null || team.getState() != GameState.RUNNING) return;

        if (player.getUniqueId().equals(team.getRunnerId())) {
            // Runner DC → victoire chasseurs
            ChatUtils.broadcast(team, plugin, "&cLe chassé a quitté la partie !");
            gm.endGame(team, "hunters-win");
        } else {
            // Chasseur DC → traiter comme une mort
            ChatUtils.broadcast(team, plugin, "&c" + player.getName() + " a quitté (compté comme une mort) !");
            gm.onHunterDeath(team, player);
        }
    }
}
