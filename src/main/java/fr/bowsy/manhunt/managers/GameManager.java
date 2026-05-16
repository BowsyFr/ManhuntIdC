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

    // Stocke l'état pvp AVANT la partie pour le restaurer (true = vulnérable)
    private final Map<UUID, Boolean> pvpStateBeforeGame = new ConcurrentHashMap<>();

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
        if (playerTeamMap.containsKey(player.getUniqueId())) return false;

        int lives = plugin.getConfig().getInt("settings.hunter-lives", 2);
        team.addHunter(player.getUniqueId(), lives);
        playerTeamMap.put(player.getUniqueId(), teamId);
        return true;
    }

    // ────────────────────────────────────────────────────────────
    //  ASSIGNATION DU RUNNER
    // ────────────────────────────────────────────────────────────

    public boolean setSpeedrunner(String teamId, Player player) {
        ManhuntTeam team = teams.get(teamId);
        if (team == null) return false;
        if (team.getState() != GameState.WAITING && team.getState() != GameState.READY) return false;

        UUID uuid = player.getUniqueId();

        if (!playerTeamMap.containsKey(uuid)) {
            playerTeamMap.put(uuid, teamId);
        }

        team.removeHunter(uuid);
        team.setRunnerId(uuid);
        team.setState(GameState.READY);

        ChatUtils.broadcast(team, plugin,
                "&6" + player.getName() + " &eest le speedrunner de l'équipe &6" + teamId + "&e !");
        return true;
    }

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

        if (team.getOverworld() == null) {
            ChatUtils.broadcast(team, plugin, "&eGénération des mondes en cours, veuillez patienter...");
            boolean ok = plugin.getWorldManager().createWorldsForTeam(team);
            if (!ok) {
                ChatUtils.broadcast(team, plugin, "&cErreur lors de la génération des mondes !");
                team.setState(GameState.READY);
                return false;
            }
        }

        // Vérifier et forcer PvP ON pour tous les joueurs de l'équipe
        ensurePvpEnabled(team);

        int briefingDuration = plugin.getConfig().getInt("settings.briefing-duration", 30);

        ChatUtils.broadcast(team, plugin,
                "&aBriefing : la partie commence dans &e" + briefingDuration + " secondes&a !");
        broadcastRules(team);

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

    /**
     * Vérifie via placeholder si PvP est activé pour chaque joueur de l'équipe.
     * Si le placeholder %pvp-toggle_boolvulnerable% est false, execute /pvp <pseudo> pour l'activer.
     * Stocke l'état avant modification pour restauration.
     */
    private void ensurePvpEnabled(ManhuntTeam team) {
        for (UUID uid : team.getAllPlayerIds()) {
            Player p = Bukkit.getPlayer(uid);
            if (p == null) continue;

            // Tenter de récupérer le placeholder via PlaceholderAPI si disponible
            boolean isPvpEnabled = isPvpEnabled(p);
            pvpStateBeforeGame.put(uid, isPvpEnabled);

            if (!isPvpEnabled) {
                // Toggle PvP ON
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pvp " + p.getName());
                plugin.getLogger().info("PvP activé pour " + p.getName() + " (était désactivé avant la partie)");
            }
        }

        // Bloquer la commande /pvp pendant la partie — géré dans PlayerListener via event
    }

    /**
     * Restaure l'état PvP après la partie.
     * Si le joueur était en PvP ON avant (isPvpEnabled=true), on ne fait rien (il est déjà ON).
     * Si le joueur était en PvP OFF avant (isPvpEnabled=false), on toggle pour revenir OFF.
     */
    private void restorePvpStates(ManhuntTeam team) {
        for (UUID uid : team.getAllPlayerIds()) {
            Player p = Bukkit.getPlayer(uid);
            Boolean wasEnabled = pvpStateBeforeGame.remove(uid);
            if (wasEnabled == null) continue;

            // Après la partie, PvP est ON (on l'a forcé). Si avant c'était OFF → retoggle
            if (!wasEnabled) {
                if (p != null) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pvp " + p.getName());
                }
                // Si hors-ligne, on ne peut pas retoggler — limitation connue
            }
        }
    }

    /**
     * Vérifie si le PvP est activé pour un joueur via PlaceholderAPI.
     * Retourne true (vulnérable) par défaut si PAPI n'est pas disponible.
     */
    private boolean isPvpEnabled(Player player) {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                String value = me.clip.placeholderapi.PlaceholderAPI
                        .setPlaceholders(player, "%pvp-toggle_boolvulnerable%");
                return "true".equalsIgnoreCase(value);
            } catch (Exception e) {
                // PAPI pas disponible ou placeholder non enregistré
            }
        }
        return true; // par défaut on considère PvP ON
    }

    public boolean isPlayerInActiveGame(UUID uuid) {
        ManhuntTeam team = getTeamOfPlayer(uuid);
        return team != null && (team.getState() == GameState.RUNNING || team.getState() == GameState.BRIEFING);
    }

    private void launchGame(ManhuntTeam team) {
        int freezeDuration = plugin.getConfig().getInt("settings.freeze-duration", 90);

        ChatUtils.broadcast(team, plugin, "&a&lLa partie commence ! Le chassé dispose de &e" + freezeDuration + "s&a&l !");

        team.setState(GameState.RUNNING);
        team.setStartTime(System.currentTimeMillis());

        giveHuntersCompasses(team);
        giveRunnerCompass(team);
        applyRunnerEffects(team);

        // Appliquer blindness aux chasseurs au départ
        for (UUID uid : team.getHunterIds()) {
            Player h = Bukkit.getPlayer(uid);
            if (h != null) {
                h.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, freezeDuration * 20 + 40, 0, false, false));
            }
        }

        Player runner = Bukkit.getPlayer(team.getRunnerId());
        if (runner != null) {
            runner.sendMessage(ChatUtils.color("&a&lVous êtes libre ! Foncez !"));
            runner.playSound(runner.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1f);
        }

        freezeHunters(team, freezeDuration);

        startEffectTask(team);
        startMobDebuffTask(team);
        startCompassTask(team);
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
        task.runTaskTimer(plugin, 0L, 80L);
        team.setEffectTaskId(task.getTaskId());
    }

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
     * Tâche boussole :
     * - Hunters : ActionBar sans distance, boussole pointée vers le runner (si pas furtif)
     * - Runner  : ActionBar avec pseudo + distance du chasseur le plus proche
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
                boolean stealth = team.isStealthActive();

                // --- Hunters ---
                for (UUID uid : team.getHunterIds()) {
                    Player hunter = Bukkit.getPlayer(uid);
                    if (hunter == null || team.isHunterEliminated(uid)) continue;

                    if (runner == null) {
                        hunter.sendActionBar(ChatUtils.color("&7Runner hors-ligne"));
                        continue;
                    }

                    if (stealth) {
                        hunter.sendActionBar(ChatUtils.color("&7Runner &8[Furtif] &7- localisation brouillée"));
                        // Boussole vers une direction aléatoire (effet nether)
                        Location randomLoc = hunter.getLocation().clone().add(
                                (Math.random() - 0.5) * 200, 0, (Math.random() - 0.5) * 200);
                        hunter.setCompassTarget(randomLoc);
                    } else if (runner.getWorld().equals(hunter.getWorld())) {
                        hunter.setCompassTarget(runner.getLocation());
                        // Pas de distance affichée pour les hunters
                        hunter.sendActionBar(ChatUtils.color("&6Runner &e➤ &fLocalisation active"));
                    } else {
                        hunter.sendActionBar(ChatUtils.color("&7Runner dans une autre dimension : &e" + runner.getWorld().getName()));
                    }
                }

                // --- Runner ---
                if (runner != null) {
                    updateRunnerCompass(team);
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
            int dist = (int) Math.sqrt(minDist);
            runner.sendActionBar(ChatUtils.color(
                    "&cChasseur le plus proche : &e" + nearest.getName() + " &c➤ &f" + dist + " blocs"));
        }
    }

    // ────────────────────────────────────────────────────────────
    //  GESTION DES VIES
    // ────────────────────────────────────────────────────────────

    /**
     * Appelé quand un chasseur meurt.
     * Au lieu de mourir, le chasseur est clear, immobilisé 2 min avec blindness.
     * Retourne true si le chasseur est éliminé (0 vies).
     */
    public boolean onHunterDeath(ManhuntTeam team, Player hunter) {
        int lives = team.getHunterLives(hunter.getUniqueId()) - 1;
        team.setHunterLives(hunter.getUniqueId(), lives);

        if (lives <= 0) {
            ChatUtils.broadcast(team, plugin,
                    plugin.getConfig().getString("messages.hunter-eliminated", "&c%s a été éliminé !")
                            .replace("%s", hunter.getName()));
            hunter.setGameMode(GameMode.SPECTATOR);
            if (team.areAllHuntersEliminated()) {
                endGame(team, "runners-win");
            }
            return true;
        } else {
            ChatUtils.broadcast(team, plugin,
                    plugin.getConfig().getString("messages.hunter-death", "&c%s est mort ! Il lui reste &c%d vie(s).")
                            .replace("%s", hunter.getName())
                            .replace("%d", String.valueOf(lives)));

            // Pénalité : clear l'inventaire (sauf boussole), immobiliser 2 min avec blindness
            applyHunterDeathPenalty(team, hunter);
            return false;
        }
    }

    /**
     * Clear l'inventaire d'un chasseur en préservant sa boussole manhunt,
     * puis l'immobilise 2 minutes avec blindness.
     */
    private void applyHunterDeathPenalty(ManhuntTeam team, Player hunter) {
        // Sauvegarder la boussole
        ItemStack compass = fr.bowsy.manhunt.utils.ManhuntCompass.createHunterCompass();
        boolean hadCompass = false;
        for (ItemStack item : hunter.getInventory().getContents()) {
            if (fr.bowsy.manhunt.utils.ManhuntCompass.isManhuntCompass(item)) {
                hadCompass = true;
                break;
            }
        }

        // Clear inventaire
        hunter.getInventory().clear();

        // Redonner la boussole
        if (hadCompass) {
            hunter.getInventory().addItem(compass);
        }

        // Clear effets potion existants sauf permanents runner
        hunter.clearActivePotionEffects();

        // Immobiliser 2 minutes + blindness
        int penaltyTicks = 2 * 60 * 20; // 2 minutes
        hunter.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, penaltyTicks + 40, 127, false, false));
        hunter.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, penaltyTicks + 40, 0, false, false));
        hunter.setWalkSpeed(0f);

        hunter.sendMessage(ChatUtils.color("&cVous avez perdu une vie ! Vous êtes immobilisé pendant &e2 minutes&c !"));

        // Verrouiller la position
        Location deathLoc = hunter.getLocation().clone();
        BukkitRunnable penaltyTask = new BukkitRunnable() {
            int remaining = penaltyTicks / 20; // en secondes
            final Location lockedPos = deathLoc;

            @Override
            public void run() {
                if (team.getState() != GameState.RUNNING || !hunter.isOnline()) {
                    cancel();
                    return;
                }
                if (remaining <= 0) {
                    cancel();
                    hunter.removePotionEffect(PotionEffectType.SLOWNESS);
                    hunter.removePotionEffect(PotionEffectType.BLINDNESS);
                    hunter.setWalkSpeed(0.2f);
                    hunter.sendMessage(ChatUtils.color("&aVous êtes de nouveau libre !"));
                    return;
                }
                // Verrouiller position (anti-mouvement pendant freeze)
                if (hunter.getLocation().distanceSquared(lockedPos) > 0.25) {
                    hunter.teleport(lockedPos);
                }
                if (remaining % 30 == 0 || remaining <= 10) {
                    hunter.sendActionBar(ChatUtils.color("&cPénalité : &e" + remaining + "s restantes"));
                }
                remaining--;
            }
        };
        penaltyTask.runTaskTimer(plugin, 0L, 20L);
    }

    public void onRunnerDeath(ManhuntTeam team) {
        endGame(team, "hunters-win");
    }

    // ────────────────────────────────────────────────────────────
    //  VÉRIFICATION DE L'OBJECTIF
    // ────────────────────────────────────────────────────────────

    public void checkRunnerObjective(ManhuntTeam team, Player runner) {
        if (team.getState() != GameState.RUNNING) return;
        if (!runner.getUniqueId().equals(team.getRunnerId())) return;

        boolean achieved;
        if (team.isNetherEnabled()) {
            achieved = hasNetheriteIngot(runner);
        } else {
            achieved = hasFullDiamond(runner);
        }

        if (achieved) {
            endGame(team, "runner-win");
        }
    }

    /**
     * Vérifie que le runner tient un lingot de Netherite en main.
     */
    private boolean hasNetheriteIngot(Player player) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        return (mainHand.getType() == Material.NETHERITE_INGOT)
                || (offHand.getType() == Material.NETHERITE_INGOT);
    }

    private boolean hasFullDiamond(Player player) {
        ItemStack[] armor = player.getInventory().getArmorContents();
        boolean helmet = armor[3] != null && armor[3].getType() == Material.DIAMOND_HELMET;
        boolean chest  = armor[2] != null && armor[2].getType() == Material.DIAMOND_CHESTPLATE;
        boolean legs   = armor[1] != null && armor[1].getType() == Material.DIAMOND_LEGGINGS;
        boolean boots  = armor[0] != null && armor[0].getType() == Material.DIAMOND_BOOTS;

        boolean pickaxe = false, sword = false;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null) continue;
            switch (item.getType()) {
                case DIAMOND_PICKAXE -> pickaxe = true;
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

        cancelTask(team.getTimerTaskId());
        cancelTask(team.getEffectTaskId());
        cancelTask(team.getMobDebuffTaskId());
        cancelTask(team.getFreezeTaskId());
        cancelTask(team.getCompassTaskId());

        String msg = switch (reason) {
            case "runner-win" -> plugin.getConfig().getString("messages.runner-win",
                    "&6Le chassé a accompli son objectif ! Victoire !");
            case "hunters-win", "runners-win" -> plugin.getConfig().getString("messages.hunters-win",
                    "&cLe chassé est mort ! Victoire des chasseurs !");
            case "time-up" -> plugin.getConfig().getString("messages.time-up",
                    "&cTemps écoulé ! Victoire des chasseurs !");
            default -> "&7Partie terminée.";
        };

        ChatUtils.broadcastAll(plugin, msg + " &7(Équipe &6" + team.getTeamId() + "&7)");

        for (UUID uid : team.getAllPlayerIds()) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null) {
                p.setGameMode(GameMode.SPECTATOR);
                p.clearActivePotionEffects();
                p.setWalkSpeed(0.2f);
                EffectUtils.playEndFirework(p.getLocation());
            }
        }

        if (team.getStartTime() > 0) {
            long elapsed = (System.currentTimeMillis() - team.getStartTime()) / 1000;
            long mins = elapsed / 60, secs = elapsed % 60;
            ChatUtils.broadcastAll(plugin,
                    "&7Durée de la partie (Équipe &6" + team.getTeamId() + "&7) : &f" + mins + "m " + secs + "s");
        }

        // Restaurer l'état PvP
        restorePvpStates(team);
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
    //  FREEZE (verrouillage de position)
    // ────────────────────────────────────────────────────────────

    private void freezeAllPlayers(ManhuntTeam team) {
        for (UUID uid : team.getAllPlayerIds()) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null) {
                team.setFrozenLocation(uid, p.getLocation().clone());
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Integer.MAX_VALUE, 127, false, false));
                p.setWalkSpeed(0f);
            }
        }

        // Tâche de verrouillage de position pendant le briefing
        BukkitRunnable lockTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (team.getState() != GameState.BRIEFING) {
                    cancel();
                    return;
                }
                for (UUID uid : team.getAllPlayerIds()) {
                    Player p = Bukkit.getPlayer(uid);
                    Location locked = team.getFrozenLocation(uid);
                    if (p != null && locked != null) {
                        if (p.getLocation().distanceSquared(locked) > 0.25) {
                            p.teleport(locked);
                        }
                    }
                }
            }
        };
        lockTask.runTaskTimer(plugin, 0L, 5L);
        team.setBriefingLockTaskId(lockTask.getTaskId());
    }

    private void freezeHunters(ManhuntTeam team, int seconds) {
        // Annuler le lock du briefing
        cancelTask(team.getBriefingLockTaskId());

        // Libérer le runner
        Player runner = Bukkit.getPlayer(team.getRunnerId());
        if (runner != null) {
            unfreezePlayer(runner);
            team.clearFrozenLocation(runner.getUniqueId());
        }

        // Freeze hunters avec verrouillage de position
        for (UUID uid : team.getHunterIds()) {
            Player h = Bukkit.getPlayer(uid);
            if (h != null) {
                team.setFrozenLocation(uid, h.getLocation().clone());
                h.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, seconds * 20 + 40, 127, false, false));
                h.setWalkSpeed(0f);
                h.sendMessage(ChatUtils.color("&cVous êtes gelé pendant &e" + seconds + " secondes&c !"));
            }
        }

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
                            team.clearFrozenLocation(uid);
                            // Retirer la blindness du départ
                            h.removePotionEffect(PotionEffectType.BLINDNESS);
                            h.sendMessage(ChatUtils.color("&a&lChasse ! Le chassé a de l'avance, rattrapez-le !"));
                            h.playSound(h.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.2f);
                        }
                    }
                    return;
                }

                // Verrouillage de position des hunters
                for (UUID uid : team.getHunterIds()) {
                    Player h = Bukkit.getPlayer(uid);
                    Location locked = team.getFrozenLocation(uid);
                    if (h != null && locked != null) {
                        if (h.getLocation().distanceSquared(locked) > 0.25) {
                            h.teleport(locked);
                        }
                    }
                    if (h != null && (remaining <= 10 || remaining % 30 == 0)) {
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
                h.getInventory().addItem(fr.bowsy.manhunt.utils.ManhuntCompass.createHunterCompass());
            }
        }
    }

    private void giveRunnerCompass(ManhuntTeam team) {
        Player runner = Bukkit.getPlayer(team.getRunnerId());
        if (runner != null) {
            runner.getInventory().addItem(fr.bowsy.manhunt.utils.ManhuntCompass.createRunnerCompass());
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

        // Appliquer l'invisibilité au runner
        runner.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, duration * 20, 0, false, false));

        ChatUtils.broadcast(team, plugin,
                "&5Le chassé a bu une &dPotion Furtive &5! Il est invisible pendant &d"
                        + (duration / 60) + " minutes&5 !");

        // Désactiver après durée
        new BukkitRunnable() {
            @Override
            public void run() {
                if (team.getState() != GameState.RUNNING) return;
                team.setStealthActive(false);
                runner.removePotionEffect(PotionEffectType.INVISIBILITY);
                ChatUtils.broadcast(team, plugin,
                        plugin.getConfig().getString("messages.stealth-expired",
                                "&cPotion furtive expirée ! Les chasseurs peuvent vous localiser."));
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

        ManhuntTeam newTeam = createTeam(teamId);
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

    private void broadcastRules(ManhuntTeam team) {
        String obj = team.isNetherEnabled()
                ? "Tenir un Lingot de Netherite en main"
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