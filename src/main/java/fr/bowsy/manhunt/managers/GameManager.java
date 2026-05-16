package fr.bowsy.manhunt.managers;

import fr.bowsy.manhunt.ManhuntPlugin;
import fr.bowsy.manhunt.models.GameState;
import fr.bowsy.manhunt.models.ManhuntTeam;
import fr.bowsy.manhunt.utils.ChatUtils;
import fr.bowsy.manhunt.utils.EffectUtils;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GameManager {

    private final ManhuntPlugin plugin;
    // teamId → équipe
    private final Map<String, ManhuntTeam> teams = new ConcurrentHashMap<>();
    // playerId → teamId (pour lookup rapide)
    private final Map<UUID, String> playerTeamMap = new ConcurrentHashMap<>();

    public GameManager(ManhuntPlugin plugin) {
        this.plugin = plugin;
    }

    // ────────────────────────────────────────────────────────────
    //  GESTION DES ÉQUIPES
    // ────────────────────────────────────────────────────────────

    public ManhuntTeam createTeam(String teamId) {
        if (teams.containsKey(teamId)) return null;
        boolean netherEnabled = plugin.getConfig().getBoolean("settings.nether-enabled", true);
        ManhuntTeam team = new ManhuntTeam(teamId, netherEnabled);
        teams.put(teamId, team);
        return team;
    }

    public boolean deleteTeam(String teamId) {
        ManhuntTeam team = teams.get(teamId);
        if (team == null) return false;
        if (team.getState() == GameState.RUNNING) {
            endGame(team, "reset");
        }
        // Enlever du map joueurs
        team.getAllPlayerIds().forEach(playerTeamMap::remove);
        plugin.getWorldManager().unloadTeamWorlds(team);
        teams.remove(teamId);
        return true;
    }

    public ManhuntTeam getTeam(String teamId) {
        return teams.get(teamId);
    }

    public Collection<ManhuntTeam> getAllTeams() {
        return teams.values();
    }

    public ManhuntTeam getTeamOfPlayer(UUID uuid) {
        String teamId = playerTeamMap.get(uuid);
        return teamId != null ? teams.get(teamId) : null;
    }

    public boolean addPlayerToTeam(String teamId, Player player) {
        ManhuntTeam team = teams.get(teamId);
        if (team == null) return false;
        if (playerTeamMap.containsKey(player.getUniqueId())) return false; // déjà dans une équipe

        int lives = plugin.getConfig().getInt("settings.hunter-lives", 2);
        team.addHunter(player.getUniqueId(), lives);
        playerTeamMap.put(player.getUniqueId(), teamId);
        return true;
    }

    // ────────────────────────────────────────────────────────────
    //  ASSIGNATION DU RUNNER
    // ────────────────────────────────────────────────────────────

    /**
     * Définit un joueur précis comme speedrunner dans son équipe.
     */
    public boolean setSpeedrunner(String teamId, Player player) {
        ManhuntTeam team = teams.get(teamId);
        if (team == null) return false;
        if (team.getState() != GameState.WAITING && team.getState() != GameState.READY) return false;

        UUID uuid = player.getUniqueId();

        // S'assurer qu'il est dans l'équipe (l'ajouter si besoin)
        if (!playerTeamMap.containsKey(uuid)) {
            playerTeamMap.put(uuid, teamId);
        }

        // Retirer des chasseurs s'il y était
        team.removeHunter(uuid);
        team.setRunnerId(uuid);
        team.setState(GameState.READY);

        ChatUtils.broadcast(team, plugin,
                "&6" + player.getName() + " &eest le speedrunner de l'équipe &6" + teamId + "&e !");
        return true;
    }

    /**
     * Choisit un speedrunner aléatoire parmi les joueurs de l'équipe.
     */
    public boolean rollSpeedrunner(String teamId) {
        ManhuntTeam team = teams.get(teamId);
        if (team == null || team.getAllPlayerIds().isEmpty()) return false;

        List<UUID> players = new ArrayList<>(team.getAllPlayerIds());
        UUID chosen = players.get(new Random().nextInt(players.size()));
        Player player = Bukkit.getPlayer(chosen);
        if (player == null) return false;

        return setSpeedrunner(teamId, player);
    }

    // ────────────────────────────────────────────────────────────
    //  DÉMARRAGE DE PARTIE
    // ────────────────────────────────────────────────────────────

    public boolean startGame(String teamId) {
        ManhuntTeam team = teams.get(teamId);
        if (team == null || team.getState() != GameState.READY) return false;
        if (team.getRunnerId() == null) return false;

        team.setState(GameState.BRIEFING);

        // Créer les mondes si pas encore fait
        if (team.getOverworld() == null) {
            ChatUtils.broadcast(team, plugin, "&eGénération des mondes en cours, veuillez patienter...");
            boolean ok = plugin.getWorldManager().createWorldsForTeam(team);
            if (!ok) {
                ChatUtils.broadcast(team, plugin, "&cErreur lors de la génération des mondes !");
                team.setState(GameState.READY);
                return false;
            }
        }

        int briefingDuration = plugin.getConfig().getInt("settings.briefing-duration", 30);

        // Briefing countdown
        ChatUtils.broadcast(team, plugin,
                "&aBriefing : la partie commence dans &e" + briefingDuration + " secondes&a !");
        broadcastRules(team);

        // Téléporter tous les joueurs dans l'overworld au spawn
        World ow = team.getOverworld();
        Location spawn = ow.getSpawnLocation();

        for (UUID uid : team.getAllPlayerIds()) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null) {
                p.teleport(spawn);
                p.setGameMode(GameMode.SURVIVAL);
                p.getInventory().clear();
                p.setHealth(20);
                p.setFoodLevel(20);
            }
        }

        // Freeze tous les joueurs pendant le briefing
        freezeAllPlayers(team);

        new BukkitRunnable() {
            int remaining = briefingDuration;

            @Override
            public void run() {
                if (remaining <= 0) {
                    cancel();
                    launchGame(team);
                    return;
                }
                if (remaining <= 10 || remaining % 10 == 0) {
                    ChatUtils.broadcast(team, plugin, "&eDémarrage dans &6" + remaining + " &esecondes...");
                }
                remaining--;
            }
        }.runTaskTimer(plugin, 0L, 20L);

        return true;
    }

    /** Phase de lancement réelle : libère le runner d'abord, puis les chasseurs. */
    private void launchGame(ManhuntTeam team) {
        int freezeDuration = plugin.getConfig().getInt("settings.freeze-duration", 90);

        ChatUtils.broadcast(team, plugin, "&a&lLa partie commence ! Le chassé dispose de &e" + freezeDuration + "s&a&l !");

        team.setState(GameState.RUNNING);
        team.setStartTime(System.currentTimeMillis());

        // Donner boussole aux chasseurs
        giveHuntersCompasses(team);

        // Appliquer les effets du runner
        applyRunnerEffects(team);

        // Freeze chasseurs, libérer runner
        Player runner = Bukkit.getPlayer(team.getRunnerId());
        if (runner != null) {
            runner.sendMessage(ChatUtils.color("&a&lVous êtes libre ! Foncez !"));
            runner.playSound(runner.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1f);
        }

        freezeHunters(team, freezeDuration);

        // Démarrer les tâches périodiques
        startEffectTask(team);
        startMobDebuffTask(team);
        startCompassTask(team);

        // Timer max
        startGameTimer(team);
    }

    // ────────────────────────────────────────────────────────────
    //  EFFETS DU RUNNER
    // ────────────────────────────────────────────────────────────

    private void applyRunnerEffects(ManhuntTeam team) {
        Player runner = Bukkit.getPlayer(team.getRunnerId());
        if (runner == null) return;
        EffectUtils.applyRunnerPermanentEffects(runner);
    }

    /**
     * Tâche périodique pour maintenir les effets permanents du runner
     * (Force, Vitesse, Haste) et les actualiser.
     */
    private void startEffectTask(ManhuntTeam team) {
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (team.getState() != GameState.RUNNING) {
                    cancel();
                    return;
                }
                Player runner = Bukkit.getPlayer(team.getRunnerId());
                if (runner == null) return;
                EffectUtils.applyRunnerPermanentEffects(runner);
            }
        };
        task.runTaskTimer(plugin, 0L, 80L); // re-appliquer toutes les 4s
        team.setEffectTaskId(task.getTaskId());
    }

    /**
     * Tâche pour infliger Faiblesse + Lenteur aux mobs hostiles proches du runner.
     */
    private void startMobDebuffTask(ManhuntTeam team) {
        int radius = plugin.getConfig().getInt("settings.mob-debuff-radius", 10);
        int interval = plugin.getConfig().getInt("settings.mob-check-interval", 40);

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (team.getState() != GameState.RUNNING) {
                    cancel();
                    return;
                }
                Player runner = Bukkit.getPlayer(team.getRunnerId());
                if (runner == null) return;

                runner.getWorld().getNearbyEntities(runner.getLocation(), radius, radius, radius,
                        e -> e instanceof Monster).forEach(e -> {
                    LivingEntity mob = (LivingEntity) e;
                    mob.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, interval + 20, 0, false, false));
                    mob.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, interval + 20, 0, false, false));
                });
            }
        };
        task.runTaskTimer(plugin, 0L, interval);
        team.setMobDebuffTaskId(task.getTaskId());
    }

    /**
     * Tâche pour mettre à jour les boussoles des chasseurs (affiche distance).
     */
    private void startCompassTask(ManhuntTeam team) {
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (team.getState() != GameState.RUNNING) {
                    cancel();
                    return;
                }
                Player runner = Bukkit.getPlayer(team.getRunnerId());
                if (runner == null) return;

                // Si furtivité active → boussole ne pointe pas vers le runner
                boolean stealth = team.isStealthActive();

                for (UUID uid : team.getHunterIds()) {
                    Player hunter = Bukkit.getPlayer(uid);
                    if (hunter == null || team.isHunterEliminated(uid)) continue;

                    if (!stealth && runner.getWorld().equals(hunter.getWorld())) {
                        // Pointer la boussole vers le runner
                        hunter.setCompassTarget(runner.getLocation());
                        double dist = hunter.getLocation().distance(runner.getLocation());
                        // Actionbar avec distance
                        hunter.sendActionBar(ChatUtils.color(
                                "&6Runner &e➤ &f" + (int) dist + " blocs"));
                    } else if (stealth) {
                        hunter.sendActionBar(ChatUtils.color("&7Runner &8[Furtif] &7- localisation brouillée"));
                    } else {
                        // Dimensions différentes
                        hunter.sendActionBar(ChatUtils.color("&7Runner dans une autre dimension"));
                    }
                }
            }
        };
        task.runTaskTimer(plugin, 0L, 20L);
        team.setCompassTaskId(task.getTaskId());
    }

    // ────────────────────────────────────────────────────────────
    //  BOUSSOLE DU RUNNER (vers chasseur le plus proche)
    // ────────────────────────────────────────────────────────────

    public void updateRunnerCompass(ManhuntTeam team) {
        Player runner = Bukkit.getPlayer(team.getRunnerId());
        if (runner == null) return;

        Player nearest = null;
        double minDist = Double.MAX_VALUE;

        for (UUID uid : team.getHunterIds()) {
            if (team.isHunterEliminated(uid)) continue;
            Player h = Bukkit.getPlayer(uid);
            if (h == null || !h.getWorld().equals(runner.getWorld())) continue;
            double d = h.getLocation().distanceSquared(runner.getLocation());
            if (d < minDist) {
                minDist = d;
                nearest = h;
            }
        }

        if (nearest != null) {
            runner.setCompassTarget(nearest.getLocation());
            runner.sendActionBar(ChatUtils.color(
                    "&cChasseur le plus proche &e➤ &f" + (int) Math.sqrt(minDist) + " blocs"));
        }
    }

    // ────────────────────────────────────────────────────────────
    //  GESTION DES VIES
    // ────────────────────────────────────────────────────────────

    /**
     * Appelé quand un chasseur meurt. Décrémente ses vies.
     * Retourne true si le chasseur est éliminé.
     */
    public boolean onHunterDeath(ManhuntTeam team, Player hunter) {
        int lives = team.getHunterLives(hunter.getUniqueId()) - 1;
        team.setHunterLives(hunter.getUniqueId(), lives);

        String prefix = plugin.getConfig().getString("messages.prefix", "&8[&6Manhunt&8] &r");
        if (lives <= 0) {
            ChatUtils.broadcast(team, plugin,
                    plugin.getConfig().getString("messages.hunter-eliminated", "&c%s a été éliminé !")
                            .replace("%s", hunter.getName()));
            hunter.setGameMode(GameMode.SPECTATOR);
            // Vérifier si tous les chasseurs sont éliminés
            if (team.areAllHuntersEliminated()) {
                endGame(team, "runners-win");
            }
            return true;
        } else {
            ChatUtils.broadcast(team, plugin,
                    plugin.getConfig().getString("messages.hunter-death", "&c%s est mort ! Il lui reste &c%d vie(s).")
                            .replace("%s", hunter.getName())
                            .replace("%d", String.valueOf(lives)));
            return false;
        }
    }

    /**
     * Appelé quand le runner meurt.
     */
    public void onRunnerDeath(ManhuntTeam team) {
        endGame(team, "hunters-win");
    }

    // ────────────────────────────────────────────────────────────
    //  VÉRIFICATION DE L'OBJECTIF
    // ────────────────────────────────────────────────────────────

    /**
     * Vérifie si le runner a atteint son objectif.
     * Appelé périodiquement ou sur événement (craft/pickup).
     */
    public void checkRunnerObjective(ManhuntTeam team, Player runner) {
        if (team.getState() != GameState.RUNNING) return;
        if (!runner.getUniqueId().equals(team.getRunnerId())) return;

        boolean achieved;
        if (team.isNetherEnabled()) {
            achieved = hasNetherite(runner);
        } else {
            achieved = hasFullDiamond(runner);
        }

        if (achieved) {
            endGame(team, "runner-win");
        }
    }

    private boolean hasNetherite(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null) continue;
            String name = item.getType().name();
            if (name.startsWith("NETHERITE_")) return true;
        }
        return false;
    }

    private boolean hasFullDiamond(Player player) {
        ItemStack[] armor = player.getInventory().getArmorContents();
        boolean helmet = armor[3] != null && armor[3].getType() == Material.DIAMOND_HELMET;
        boolean chest  = armor[2] != null && armor[2].getType() == Material.DIAMOND_CHESTPLATE;
        boolean legs   = armor[1] != null && armor[1].getType() == Material.DIAMOND_LEGGINGS;
        boolean boots  = armor[0] != null && armor[0].getType() == Material.DIAMOND_BOOTS;

        boolean pickaxe = false, axe = false, sword = false;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null) continue;
            switch (item.getType()) {
                case DIAMOND_PICKAXE -> pickaxe = true;
                case DIAMOND_AXE -> axe = true;
                case DIAMOND_SWORD -> sword = true;
                default -> {}
            }
        }
        return helmet && chest && legs && boots && pickaxe && sword;
    }

    // ────────────────────────────────────────────────────────────
    //  FIN DE PARTIE
    // ────────────────────────────────────────────────────────────

    public void endGame(ManhuntTeam team, String reason) {
        if (team.getState() == GameState.FINISHED) return;
        team.setState(GameState.FINISHED);

        // Annuler toutes les tâches
        cancelTask(team.getTimerTaskId());
        cancelTask(team.getEffectTaskId());
        cancelTask(team.getMobDebuffTaskId());
        cancelTask(team.getFreezeTaskId());
        cancelTask(team.getCompassTaskId());

        String msg = switch (reason) {
            case "runner-win" -> plugin.getConfig().getString("messages.runner-win",
                    "&6Le chassé a accompli son objectif ! Victoire !");
            case "hunters-win" -> plugin.getConfig().getString("messages.hunters-win",
                    "&cLe chassé est mort ! Victoire des chasseurs !");
            case "time-up" -> plugin.getConfig().getString("messages.time-up",
                    "&cTemps écoulé ! Victoire des chasseurs !");
            default -> "&7Partie terminée.";
        };

        ChatUtils.broadcastAll(plugin, msg + " &7(Équipe &6" + team.getTeamId() + "&7)");

        // Mettre tous les joueurs en spectateur
        for (UUID uid : team.getAllPlayerIds()) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null) {
                p.setGameMode(GameMode.SPECTATOR);
                p.clearActivePotionEffects();
                EffectUtils.playEndFirework(p.getLocation());
            }
        }

        // Durée de la partie
        if (team.getStartTime() > 0) {
            long elapsed = (System.currentTimeMillis() - team.getStartTime()) / 1000;
            long mins = elapsed / 60, secs = elapsed % 60;
            ChatUtils.broadcastAll(plugin,
                    "&7Durée de la partie (Équipe &6" + team.getTeamId() + "&7) : &f" + mins + "m " + secs + "s");
        }
    }

    // ────────────────────────────────────────────────────────────
    //  TIMER
    // ────────────────────────────────────────────────────────────

    private void startGameTimer(ManhuntTeam team) {
        int maxMinutes = plugin.getConfig().getInt("settings.max-duration", 120);
        int maxSeconds = maxMinutes * 60;

        BukkitRunnable task = new BukkitRunnable() {
            int remaining = maxSeconds;

            @Override
            public void run() {
                if (team.getState() != GameState.RUNNING) {
                    cancel();
                    return;
                }
                if (remaining <= 0) {
                    cancel();
                    endGame(team, "time-up");
                    return;
                }
                // Avertissements à 30min, 15min, 5min, 1min
                if (remaining == 1800 || remaining == 900 || remaining == 300 || remaining == 60) {
                    long mins = remaining / 60;
                    ChatUtils.broadcast(team, plugin,
                            "&e⏳ Il reste &6" + mins + " minute(s) &eavant la fin de la partie !");
                }
                remaining--;
            }
        };
        task.runTaskTimer(plugin, 0L, 20L);
        team.setTimerTaskId(task.getTaskId());
    }

    // ────────────────────────────────────────────────────────────
    //  FREEZE
    // ────────────────────────────────────────────────────────────

    private void freezeAllPlayers(ManhuntTeam team) {
        for (UUID uid : team.getAllPlayerIds()) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null) {
                // Freeze via vitesse 0 + message
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Integer.MAX_VALUE, 127, false, false));
                p.setWalkSpeed(0f);
            }
        }
    }

    private void freezeHunters(ManhuntTeam team, int seconds) {
        // Libérer le runner
        Player runner = Bukkit.getPlayer(team.getRunnerId());
        if (runner != null) {
            unfreezePlayer(runner);
        }

        // Freeze hunters
        for (UUID uid : team.getHunterIds()) {
            Player h = Bukkit.getPlayer(uid);
            if (h != null) {
                h.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, seconds * 20 + 40, 127, false, false));
                h.setWalkSpeed(0f);
                h.sendMessage(ChatUtils.color("&cVous êtes gelé pendant &e" + seconds + " secondes&c !"));
            }
        }

        // Débloquer les chasseurs après le délai
        BukkitRunnable task = new BukkitRunnable() {
            int remaining = seconds;

            @Override
            public void run() {
                if (remaining <= 0) {
                    cancel();
                    for (UUID uid : team.getHunterIds()) {
                        Player h = Bukkit.getPlayer(uid);
                        if (h != null) {
                            unfreezePlayer(h);
                            h.sendMessage(ChatUtils.color("&a&lChasse ! Le chassé a de l'avance, rattrapez-le !"));
                            h.playSound(h.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.2f);
                        }
                    }
                    return;
                }
                if (remaining <= 10 || remaining % 30 == 0) {
                    for (UUID uid : team.getHunterIds()) {
                        Player h = Bukkit.getPlayer(uid);
                        if (h != null)
                            h.sendActionBar(ChatUtils.color("&cDéblocage dans &e" + remaining + "s"));
                    }
                }
                remaining--;
            }
        };
        task.runTaskTimer(plugin, 0L, 20L);
        team.setFreezeTaskId(task.getTaskId());
    }

    private void unfreezePlayer(Player p) {
        p.removePotionEffect(PotionEffectType.SLOWNESS);
        p.setWalkSpeed(0.2f);
    }

    // ────────────────────────────────────────────────────────────
    //  BOUSSOLE / INVENTAIRE
    // ────────────────────────────────────────────────────────────

    private void giveHuntersCompasses(ManhuntTeam team) {
        for (UUID uid : team.getHunterIds()) {
            Player h = Bukkit.getPlayer(uid);
            if (h != null) {
                h.getInventory().addItem(new ItemStack(Material.COMPASS));
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    //  POTION FURTIVE
    // ────────────────────────────────────────────────────────────

    public void activateStealth(ManhuntTeam team, Player runner) {
        if (team.isStealthActive()) {
            runner.sendMessage(ChatUtils.color("&cLa potion furtive est déjà active !"));
            return;
        }
        int duration = plugin.getConfig().getInt("settings.stealth-potion-duration", 240);
        team.setStealthActive(true);
        team.setStealthStartTime(System.currentTimeMillis());

        ChatUtils.broadcast(team, plugin,
                plugin.getConfig().getString("messages.stealth-active",
                        "&7Potion furtive activée pour &f%d minutes&7.")
                        .replace("%d", String.valueOf(duration / 60)));

        // Désactiver après durée
        new BukkitRunnable() {
            @Override
            public void run() {
                if (team.getState() != GameState.RUNNING) return;
                team.setStealthActive(false);
                ChatUtils.broadcast(team, plugin,
                        plugin.getConfig().getString("messages.stealth-expired",
                                "&cPotion furtive expirée !"));
            }
        }.runTaskLater(plugin, duration * 20L);
    }

    // ────────────────────────────────────────────────────────────
    //  RESET
    // ────────────────────────────────────────────────────────────

    public void resetTeam(String teamId) {
        ManhuntTeam team = teams.get(teamId);
        if (team == null) return;

        if (team.getState() == GameState.RUNNING || team.getState() == GameState.BRIEFING) {
            endGame(team, "reset");
        }

        Set<UUID> players = new HashSet<>(team.getAllPlayerIds());
        plugin.getWorldManager().unloadTeamWorlds(team);
        teams.remove(teamId);
        players.forEach(playerTeamMap::remove);

        // Recréer l'équipe vierge
        ManhuntTeam newTeam = createTeam(teamId);
        // Remettre les joueurs sans runner assigné
        int lives = plugin.getConfig().getInt("settings.hunter-lives", 2);
        for (UUID uid : players) {
            newTeam.addHunter(uid, lives);
            playerTeamMap.put(uid, teamId);
        }

        ChatUtils.broadcastAll(plugin, "&aÉquipe &6" + teamId + " &aréinitialisée !");
    }

    public void shutdownAll() {
        for (ManhuntTeam team : teams.values()) {
            if (team.getState() == GameState.RUNNING) {
                endGame(team, "reset");
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    //  UTILITAIRES
    // ────────────────────────────────────────────────────────────

    private void cancelTask(int taskId) {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
    }

    /** Broadcast de règles au démarrage */
    private void broadcastRules(ManhuntTeam team) {
        String obj = team.isNetherEnabled()
                ? "Obtenir un objet en Netherite"
                : "Obtenir un équipement complet en diamant";
        String nether = team.isNetherEnabled() ? "Activé" : "Désactivé";
        int maxDuration = plugin.getConfig().getInt("settings.max-duration", 120);
        int hunterLives = plugin.getConfig().getInt("settings.hunter-lives", 2);

        ChatUtils.broadcast(team, plugin, "&8&m------------------------------------");
        ChatUtils.broadcast(team, plugin, "&6&lRègles - Équipe " + team.getTeamId());
        ChatUtils.broadcast(team, plugin, "&e• Objectif : &f" + obj);
        ChatUtils.broadcast(team, plugin, "&e• Nether : &f" + nether);
        ChatUtils.broadcast(team, plugin, "&e• Durée max : &f" + maxDuration + " minutes");
        ChatUtils.broadcast(team, plugin, "&e• Vies des chasseurs : &f" + hunterLives);
        ChatUtils.broadcast(team, plugin, "&e• Bordure : &f2000×2000 blocs");
        ChatUtils.broadcast(team, plugin, "&8&m------------------------------------");
    }
}
