package fr.bowsy.manhunt.listeners;

import fr.bowsy.manhunt.ManhuntPlugin;
import fr.bowsy.manhunt.managers.GameManager;
import fr.bowsy.manhunt.models.GameState;
import fr.bowsy.manhunt.models.ManhuntTeam;
import fr.bowsy.manhunt.utils.ChatUtils;
import fr.bowsy.manhunt.utils.ManhuntCompass;
import fr.bowsy.manhunt.utils.StealthPotionRecipe;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

public class PlayerListener implements Listener {

    private final ManhuntPlugin plugin;
    private final GameManager gm;

    public PlayerListener(ManhuntPlugin plugin) {
        this.plugin = plugin;
        this.gm = plugin.getGameManager();
    }

    // ────────────────────────────────────────────────────────────
    //  INTERCEPTION DÉGÂTS FATALS HUNTERS (avant la mort réelle)
    // ────────────────────────────────────────────────────────────

    /**
     * Intercepte les dégâts AVANT la mort pour les hunters.
     * Si les dégâts finaux dépassent la vie courante → on annule et on gère nous-mêmes.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        ManhuntTeam team = gm.getTeamOfPlayer(player.getUniqueId());
        if (team == null || team.getState() != GameState.RUNNING) return;

        // Runner : mort normale gérée par onPlayerDeath
        if (player.getUniqueId().equals(team.getRunnerId())) return;

        // Hunters uniquement
        if (!team.getHunterIds().contains(player.getUniqueId())) return;

        // Hunter déjà éliminé (spectateur) → ignorer
        if (team.isHunterEliminated(player.getUniqueId())) return;

        // Hunter en mode pénalité (adventure) → annuler les dégâts
        if (player.getGameMode() == GameMode.ADVENTURE) {
            event.setCancelled(true);
            return;
        }

        double healthAfter = player.getHealth() - event.getFinalDamage();
        if (healthAfter <= 0) {
            // Va mourir → on annule et on gère
            event.setCancelled(true);
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isOnline() && team.getState() == GameState.RUNNING) {
                        gm.processHunterDeath(team, player);
                    }
                }
            }.runTask(plugin);
        }
    }

    /**
     * Sécurité : si un hunter meurt quand même (cas edge cases),
     * on intercepte et gère proprement.
     * Pour le runner : fin de partie normale.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        ManhuntTeam team = gm.getTeamOfPlayer(player.getUniqueId());
        if (team == null || team.getState() != GameState.RUNNING) return;

        if (player.getUniqueId().equals(team.getRunnerId())) {
            event.setDeathMessage(null);
            gm.onRunnerDeath(team);
        } else if (team.getHunterIds().contains(player.getUniqueId())
                && !team.isHunterEliminated(player.getUniqueId())) {
            // Fallback hunter : ne devrait pas arriver avec onEntityDamage
            event.setDeathMessage(null);
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setDroppedExp(0);
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isOnline()) {
                        player.setHealth(player.getMaxHealth());
                        gm.processHunterDeath(team, player);
                    }
                }
            }.runTaskLater(plugin, 1L);
        }
    }

    // ────────────────────────────────────────────────────────────
    //  RESPAWN
    // ────────────────────────────────────────────────────────────

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        ManhuntTeam team = gm.getTeamOfPlayer(player.getUniqueId());
        if (team == null || team.getState() != GameState.RUNNING) return;
        if (team.getOverworld() != null) {
            event.setRespawnLocation(team.getOverworld().getSpawnLocation());
        }
    }

    // ────────────────────────────────────────────────────────────
    //  POTION FURTIVE
    // ────────────────────────────────────────────────────────────

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
        }
    }

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

        event.setCancelled(true);
        if (item.getAmount() > 1) item.setAmount(item.getAmount() - 1);
        else player.getInventory().remove(item);
        gm.activateStealth(team, player);
    }

    // ────────────────────────────────────────────────────────────
    //  PROTECTION BOUSSOLE + HACHE (indroppable)
    // ────────────────────────────────────────────────────────────

    /** Bloque le drop via la touche Q. */
    @EventHandler
    public void onItemDrop(PlayerDropItemEvent event) {
        ItemStack dropped = event.getItemDrop().getItemStack();
        if (isProtectedItem(dropped)) {
            event.setCancelled(true);
        }
    }

    /** Bloque le drop via clic hors de l'inventaire. */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        if ((isProtectedItem(current) || isProtectedItem(cursor))
                && event.getSlotType() == InventoryType.SlotType.OUTSIDE) {
            event.setCancelled(true);
        }
    }

    /** Bloque le swap main/offhand (F) pour les items protégés. */
    @EventHandler
    public void onPlayerSwapHand(PlayerSwapHandItemsEvent event) {
        if (isProtectedItem(event.getMainHandItem())
                || isProtectedItem(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    // ────────────────────────────────────────────────────────────
    //  VÉRIFICATION OBJECTIF (pickup)
    // ────────────────────────────────────────────────────────────

    @EventHandler
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ManhuntTeam team = gm.getTeamOfPlayer(player.getUniqueId());
        if (team == null || team.getState() != GameState.RUNNING) return;
        if (!player.getUniqueId().equals(team.getRunnerId())) return;
        new BukkitRunnable() {
            @Override
            public void run() { gm.checkRunnerObjective(team, player); }
        }.runTaskLater(plugin, 1L);
    }

    // ────────────────────────────────────────────────────────────
    //  MOUVEMENT (update boussole runner si compass en main)
    // ────────────────────────────────────────────────────────────

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;
        Player player = event.getPlayer();
        ManhuntTeam team = gm.getTeamOfPlayer(player.getUniqueId());
        if (team == null || team.getState() != GameState.RUNNING) return;
        if (player.getUniqueId().equals(team.getRunnerId())) {
            if (ManhuntCompass.isManhuntCompass(player.getInventory().getItemInMainHand())) {
                gm.updateRunnerCompass(team);
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    //  DÉCONNEXION
    // ────────────────────────────────────────────────────────────

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        ManhuntTeam team = gm.getTeamOfPlayer(player.getUniqueId());
        if (team == null || team.getState() != GameState.RUNNING) return;

        if (player.getUniqueId().equals(team.getRunnerId())) {
            ChatUtils.broadcast(team, plugin, "&cLe chassé a quitté la partie !");
            gm.endGame(team, "hunters-win");
        } else if (team.getHunterIds().contains(player.getUniqueId())) {
            // Utiliser onHunterQuit (pas de freeze, joueur hors-ligne)
            gm.onHunterQuit(team, player);
        }
    }

    // ────────────────────────────────────────────────────────────
    //  BLOCAGE /pvp EN JEU
    // ────────────────────────────────────────────────────────────

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String cmd = event.getMessage().toLowerCase().trim();
        if (cmd.startsWith("/pvp")) {
            Player player = event.getPlayer();
            if (gm.isPlayerInActiveGame(player.getUniqueId())) {
                event.setCancelled(true);
                player.sendMessage(ChatUtils.colorComponent(
                        "&cLa commande &e/pvp&c est désactivée pendant une partie Manhunt !"));
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    //  UTILITAIRE
    // ────────────────────────────────────────────────────────────

    /**
     * Vérifie si un item est protégé contre le drop (boussole ou hache de départ).
     */
    private boolean isProtectedItem(ItemStack item) {
        if (item == null) return false;
        return ManhuntCompass.isManhuntCompass(item) || GameManager.isStarterAxe(item);
    }
}