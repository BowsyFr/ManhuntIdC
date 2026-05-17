package fr.bowsy.manhunt.managers;

import fr.bowsy.manhunt.ManhuntPlugin;
import fr.bowsy.manhunt.models.GameState;
import fr.bowsy.manhunt.models.ManhuntTeam;
import fr.bowsy.manhunt.utils.ChatUtils;
import fr.bowsy.manhunt.utils.EffectUtils;
import fr.bowsy.manhunt.utils.ManhuntCompass;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GameManager {

    private final ManhuntPlugin plugin;
    private final Map<String, ManhuntTeam> teams = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerTeamMap = new ConcurrentHashMap<>();
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
        if (team.getState() == GameState.RUNNING) endGame(team, "reset");
        team.getAllPlayerIds().forEach(playerTeamMap::remove);
        plugin.getWorldManager().unloadTeamWorlds(team);
        teams.remove(teamId);
        return true;
    }

    public ManhuntTeam getTeam(String teamId) { return teams.get(teamId); }
    public Collection<ManhuntTeam> getAllTeams() { return teams.values(); }

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
        if (!playerTeamMap.containsKey(uuid)) playerTeamMap.put(uuid, teamId);
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

        ensurePvpEnabled(team);

        World ow = team.getOverworld();
        // Calcul du spawn : on prend Y au-dessus du bloc le plus haut
        int spawnX = 0, spawnZ = 0;
        int spawnY = ow.getHighestBlockYAt(spawnX, spawnZ) + 1;
        Location spawnLoc = new Location(ow, spawnX + 0.5, spawnY, spawnZ + 0.5);
        ow.setSpawnLocation(spawnX, spawnY, spawnZ);

        // Stocker le spawn dans l'équipe pour usage ultérieur
        team.setSpawnLocation(spawnLoc);

        // Plateforme bedrock 3x3 sous le spawn
        buildBedrockPlatform(ow, spawnLoc);

        // Worldborder 3x3 autour du spawn dans l'overworld
        WorldBorder owBorder = ow.getWorldBorder();
        owBorder.setCenter(spawnX, spawnZ);
        owBorder.setSize(3);

        // Worldborder 3x3 dans le nether aussi si activé
        if (team.isNetherEnabled() && team.getNether() != null) {
            WorldBorder netherBorder = team.getNether().getWorldBorder();
            netherBorder.setCenter(0, 0);
            netherBorder.setSize(3);
        }

        // Téléporter et préparer tous les joueurs
        for (UUID uid : team.getAllPlayerIds()) {
            Player p = Bukkit.getPlayer(uid);
            if (p != null) {
                p.teleport(spawnLoc);
                p.setGameMode(GameMode.ADVENTURE);
                p.getInventory().clear();
                p.setHealth(p.getMaxHealth());
                p.setFoodLevel(20);
                p.setSaturation(20f);
                // Freeze total : slowness 127 + jump_boost amplifier 128 (empêche saut)
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Integer.MAX_VALUE, 127, false, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, Integer.MAX_VALUE, 128, false, false));
                p.setWalkSpeed(0f);
            }
        }

        int briefingDuration = plugin.getConfig().getInt("settings.briefing-duration", 30);
        ChatUtils.broadcast(team, plugin,
                "&aBriefing : la partie commence dans &e" + briefingDuration + " secondes&a !");
        broadcastRules(team);

        startBriefingCountdown(team, briefingDuration);
        return true;
    }

    /**
     * Construit une plateforme 3x3 en bedrock sous le spawn.
     */
    private void buildBedrockPlatform(World world, Location spawn) {
        int baseY = spawn.getBlockY() - 1;
        int cx = spawn.getBlockX();
        int cz = spawn.getBlockZ();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                world.getBlockAt(cx + dx, baseY, cz + dz).setType(Material.BEDROCK);
            }
        }
    }

    /**
     * Supprime la plateforme bedrock 3x3.
     */
    private void removeBedrockPlatform(World world, Location spawn) {
        int baseY = spawn.getBlockY() - 1;
        int cx = spawn.getBlockX();
        int cz = spawn.getBlockZ();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Block block = world.getBlockAt(cx + dx, baseY, cz + dz);
                if (block.getType() == Material.BEDROCK) block.setType(Material.AIR);
            }
        }
    }

    /**
     * Countdown de briefing affiché sur l'actionbar de tous les joueurs.
     * À 0 : libère le runner.
     */
    private void startBriefingCountdown(ManhuntTeam team, int briefingDuration) {
        new BukkitRunnable() {
            int remaining = briefingDuration;

            @Override
            public void run() {
                if (team.getState() != GameState.BRIEFING) {
                    cancel();
                    return;
                }
                if (remaining <= 0) {
                    cancel();
                    releaseRunner(team);
                    return;
                }
                String bar = "&eDépart du chassé dans &6" + remaining + "s";
                for (UUID uid : team.getAllPlayerIds()) {
                    Player p = Bukkit.getPlayer(uid);
                    if (p != null) p.sendActionBar(ChatUtils.color(bar));
                }
                if (remaining <= 10 || remaining % 10 == 0) {
                    ChatUtils.broadcast(team, plugin, "&eDémarrage dans &6" + remaining + " &esecondes...");
                }
                remaining--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    /**
     * Libère le runner :
     * - Retire freeze runner
     * - Ouvre la worldborder à 2000x2000 (overworld + nether)
     * - Applique effets runner
     * - Donne boussole runner
     * - Lance le countdown de freeze hunters
     */
    private void releaseRunner(ManhuntTeam team) {
        team.setState(GameState.RUNNING);
        team.setStartTime(System.currentTimeMillis());

        World ow = team.getOverworld();
        Location spawnLoc = team.getSpawnLocation();
        int borderSize = plugin.getConfig().getInt("settings.border-size", 1000);

        // Ouvrir worldborder overworld → 2000x2000
        WorldBorder owBorder = ow.getWorldBorder();
        owBorder.setCenter(0, 0);
        owBorder.setSize(borderSize * 2);

        // Ouvrir worldborder nether → ratio 1:8 (250x250 pour 2000x2000)
        if (team.isNetherEnabled() && team.getNether() != null) {
            WorldBorder netherBorder = team.getNether().getWorldBorder();
            netherBorder.setCenter(0, 0);
            netherBorder.setSize(borderSize / 4); // nether scale 1:8 → demi-côté / 4 = diamètre / 4
        }

        // Libérer le runner
        Player runner = Bukkit.getPlayer(team.getRunnerId());
        if (runner != null) {
            runner.setGameMode(GameMode.SURVIVAL);
            runner.removePotionEffect(PotionEffectType.SLOWNESS);
            runner.removePotionEffect(PotionEffectType.JUMP_BOOST);
            runner.setWalkSpeed(0.2f);
            runner.getInventory().addItem(ManhuntCompass.createRunnerCompass());
            runner.sendMessage(ChatUtils.color("&a&lVous êtes libre ! Foncez !"));
            runner.playSound(runner.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1f);
        }

        int freezeDuration = plugin.getConfig().getInt("settings.freeze-duration", 90);
        ChatUtils.broadcast(team, plugin,
                "&a&lLe chassé est parti ! Les hunters sont bloqués pendant &e" + freezeDuration + "s&a&l !");

        // Hunters : blindness pendant le freeze
        for (UUID uid : team.getHunterIds()) {
            Player h = Bukkit.getPlayer(uid);
            if (h != null) {
                h.addPotionEffect(new PotionEffect(
                        PotionEffectType.BLINDNESS, (freezeDuration + 10) * 20, 0, false, false));
            }
        }

        applyRunnerEffects(team);
        startHunterFreezeCountdown(team, freezeDuration, spawnLoc);
        startEffectTask(team);
        startMobDebuffTask(team);
        startCompassTask(team);
        startGameTimer(team);
    }

    /**
     * Countdown freeze hunters avec verrouillage position (tp si sortent de la plateforme).
     * À 0 : donne boussole + hache, retire plateforme, libère hunters.
     */
    private void startHunterFreezeCountdown(ManhuntTeam team, int freezeDuration, Location spawnLoc) {
        BukkitRunnable task = new BukkitRunnable() {
            int remaining = freezeDuration;

            @Override
            public void run() {
                if (team.getState() != GameState.RUNNING) {
                    cancel();
                    return;
                }
                if (remaining <= 0) {
                    cancel();
                    releaseHunters(team, spawnLoc);
                    return;
                }

                for (UUID uid : team.getHunterIds()) {
                    Player h = Bukkit.getPlayer(uid);
                    if (h == null || team.isHunterEliminated(uid)) continue;

                    // Verrouillage position sur la plateforme
                    if (!h.getWorld().equals(spawnLoc.getWorld())) {
                        h.teleport(spawnLoc);
                    } else {
                        double dx = Math.abs(h.getLocation().getX() - spawnLoc.getX());
                        double dz = Math.abs(h.getLocation().getZ() - spawnLoc.getZ());
                        double dy = h.getLocation().getY() - spawnLoc.getY();
                        // Si trop loin horizontalement ou trop haut (saut)
                        if (dx > 1.5 || dz > 1.5 || dy > 1.5) {
                            h.teleport(spawnLoc);
                        }
                    }

                    h.sendActionBar(ChatUtils.color("&cLibération dans &e" + remaining + "s"));
                }

                // Runner : chrono en actionbar
                Player runner = Bukkit.getPlayer(team.getRunnerId());
                if (runner != null) {
                    long elapsed = (System.currentTimeMillis() - team.getStartTime()) / 1000;
                    runner.sendActionBar(ChatUtils.color("&f" + formatTime(elapsed)));
                }

                remaining--;
            }
        };
        task.runTaskTimer(plugin, 0L, 20L);
        team.setFreezeTaskId(task.getTaskId());
    }

    /**
     * Libère les hunters : retire freeze, donne boussole + hache de départ, supprime plateforme.
     */
    private void releaseHunters(ManhuntTeam team, Location spawnLoc) {
        removeBedrockPlatform(team.getOverworld(), spawnLoc);

        for (UUID uid : team.getHunterIds()) {
            Player h = Bukkit.getPlayer(uid);
            if (h == null || team.isHunterEliminated(uid)) continue;

            h.setGameMode(GameMode.SURVIVAL);
            h.removePotionEffect(PotionEffectType.SLOWNESS);
            h.removePotionEffect(PotionEffectType.JUMP_BOOST);
            h.removePotionEffect(PotionEffectType.BLINDNESS);
            h.setWalkSpeed(0.2f);

            h.getInventory().addItem(ManhuntCompass.createHunterCompass());
            h.getInventory().addItem(createStarterAxe());

            h.sendMessage(ChatUtils.color("&a&lChasse ! Le chassé a de l'avance, rattrapez-le !"));
            h.playSound(h.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.2f);
        }

        ChatUtils.broadcast(team, plugin, "&c&lLes chasseurs sont libres ! Bonne chasse !");
    }

    /**
     * Crée une hache en fer avec 15 de durabilité restante.
     * Marquée par son display name pour être identifiable (indroppable dans PlayerListener).
     */
    public static ItemStack createStarterAxe() {
        ItemStack axe = new ItemStack(Material.IRON_AXE);
        if (axe.getItemMeta() instanceof Damageable damageable) {
            int maxDur = Material.IRON_AXE.getMaxDurability(); // 250
            damageable.setDamage(maxDur - 15); // 235 de dommages → 15 durabilité restante
            axe.setItemMeta((ItemMeta) damageable);
        }
        ItemMeta meta = axe.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatUtils.color("&6Hache de départ"));
            meta.setLore(List.of(
                    ChatUtils.color("&715 utilisations restantes"),
                    ChatUtils.color("&8Ne peut pas être jetée")
            ));
            meta.addItemFlags(
                    org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES,
                    org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS,
                    org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE
            );
            axe.setItemMeta(meta);
        }
        return axe;
    }

    /**
     * Vérifie si un item est la hache de départ.
     */
    public static boolean isStarterAxe(ItemStack item) {
        if (item == null || item.getType() != Material.IRON_AXE) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName()
                && meta.getDisplayName().contains("Hache de départ");
    }

    // ────────────────────────────────────────────────────────────
    //  PVP
    // ────────────────────────────────────────────────────────────

    private void ensurePvpEnabled(ManhuntTeam team) {
        for (UUID uid : team.getAllPlayerIds()) {
            Player p = Bukkit.getPlayer(uid);
            if (p == null) continue;
            boolean isPvpEnabled = isPvpEnabled(p);
            pvpStateBeforeGame.put(uid, isPvpEnabled);
            if (!isPvpEnabled) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pvp " + p.getName());
            }
        }
    }

    private void restorePvpStates(ManhuntTeam team) {
        for (UUID uid : team.getAllPlayerIds()) {
            Boolean wasEnabled = pvpStateBeforeGame.remove(uid);
            if (wasEnabled == null || wasEnabled) continue;
            Player p = Bukkit.getPlayer(uid);
            if (p != null) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pvp " + p.getName());
            }
        }
    }

    private boolean isPvpEnabled(Player player) {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                String value = me.clip.placeholderapi.PlaceholderAPI
                        .setPlaceholders(player, "%pvp-toggle_boolvulnerable%");
                return "true".equalsIgnoreCase(value);
            } catch (Exception ignored) {}
        }
        return true;
    }

    public boolean isPlayerInActiveGame(UUID uuid) {
        ManhuntTeam team = getTeamOfPlayer(uuid);
        return team != null
                && (team.getState() == GameState.RUNNING || team.getState() == GameState.BRIEFING);
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
                if (team.getState() != GameState.RUNNING) { cancel(); return; }
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
                if (team.getState() != GameState.RUNNING) { cancel(); return; }
                Player runner = Bukkit.getPlayer(team.getRunnerId());
                if (runner == null) return;
                runner.getWorld().getNearbyEntities(
                        runner.getLocation(), radius, radius, radius,
                        e -> e instanceof Monster
                ).forEach(e -> {
                    LivingEntity mob = (LivingEntity) e;
                    mob.addPotionEffect(new PotionEffect(
                            PotionEffectType.WEAKNESS, interval + 20, 0, false, false));
                    mob.addPotionEffect(new PotionEffect(
                            PotionEffectType.SLOWNESS, interval + 20, 0, false, false));
                });
            }
        };
        task.runTaskTimer(plugin, 0L, interval);
        team.setMobDebuffTaskId(task.getTaskId());
    }

    // ────────────────────────────────────────────────────────────
    //  TÂCHE COMPASS + ACTIONBAR (chrono + distance si boussole en main)
    // ────────────────────────────────────────────────────────────

    private void startCompassTask(ManhuntTeam team) {
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (team.getState() != GameState.RUNNING) { cancel(); return; }

                long elapsedSec = (System.currentTimeMillis() - team.getStartTime()) / 1000;
                String chrono = formatTime(elapsedSec);
                boolean stealth = team.isStealthActive();
                Player runner = Bukkit.getPlayer(team.getRunnerId());

                // --- Hunters ---
                for (UUID uid : team.getHunterIds()) {
                    Player h = Bukkit.getPlayer(uid);
                    if (h == null || team.isHunterEliminated(uid)) continue;

                    boolean compassInHand = ManhuntCompass.isManhuntCompass(
                            h.getInventory().getItemInMainHand());

                    if (compassInHand) {
                        if (runner == null) {
                            h.sendActionBar(ChatUtils.color("&7Runner hors-ligne &8| &f" + chrono));
                        } else if (stealth) {
                            // Boussole vers direction aléatoire
                            Location rnd = h.getLocation().clone().add(
                                    (Math.random() - 0.5) * 200, 0, (Math.random() - 0.5) * 200);
                            h.setCompassTarget(rnd);
                            h.sendActionBar(ChatUtils.color("&5Runner furtif &8| &f" + chrono));
                        } else if (runner.getWorld().equals(h.getWorld())) {
                            h.setCompassTarget(runner.getLocation());
                            int dist = (int) runner.getLocation().distance(h.getLocation());
                            h.sendActionBar(ChatUtils.color(
                                    "&6Runner &e➤ &f" + dist + " blocs &8| &f" + chrono));
                        } else {
                            h.sendActionBar(ChatUtils.color(
                                    "&7Autre dimension &8| &f" + chrono));
                        }
                    } else {
                        // Pas de boussole en main → chrono seul
                        h.sendActionBar(ChatUtils.color("&f" + chrono));
                    }
                }

                // --- Runner ---
                if (runner != null) {
                    boolean compassInHand = ManhuntCompass.isManhuntCompass(
                            runner.getInventory().getItemInMainHand());
                    if (compassInHand) {
                        updateRunnerActionBar(team, runner, chrono);
                    } else {
                        runner.sendActionBar(ChatUtils.color("&f" + chrono));
                    }
                }
            }
        };
        task.runTaskTimer(plugin, 0L, 20L);
        team.setCompassTaskId(task.getTaskId());
    }

    // ────────────────────────────────────────────────────────────
    //  BOUSSOLE DU RUNNER
    // ────────────────────────────────────────────────────────────

    public void updateRunnerCompass(ManhuntTeam team) {
        Player runner = Bukkit.getPlayer(team.getRunnerId());
        if (runner == null) return;
        long elapsed = (System.currentTimeMillis() - team.getStartTime()) / 1000;
        updateRunnerActionBar(team, runner, formatTime(elapsed));
    }

    private void updateRunnerActionBar(ManhuntTeam team, Player runner, String chrono) {
        Player nearest = null;
        double minDist = Double.MAX_VALUE;
        for (UUID uid : team.getHunterIds()) {
            if (team.isHunterEliminated(uid)) continue;
            Player h = Bukkit.getPlayer(uid);
            if (h == null || !h.getWorld().equals(runner.getWorld())) continue;
            double d = h.getLocation().distanceSquared(runner.getLocation());
            if (d < minDist) { minDist = d; nearest = h; }
        }
        if (nearest != null) {
            runner.setCompassTarget(nearest.getLocation());
            int dist = (int) Math.sqrt(minDist);
            runner.sendActionBar(ChatUtils.color(
                    "&cChasseur : &e" + nearest.getName() + " &c➤ &f" + dist + " blocs &8| &f" + chrono));
        } else {
            runner.sendActionBar(ChatUtils.color("&7Aucun chasseur &8| &f" + chrono));
        }
    }

    // ────────────────────────────────────────────────────────────
    //  MORT DES HUNTERS (interception avant mort réelle)
    // ────────────────────────────────────────────────────────────

    /**
     * Appelé depuis PlayerListener via EntityDamageEvent quand les dégâts sont fatals.
     * L'event est déjà annulé, on gère tout ici.
     */
    public void processHunterDeath(ManhuntTeam team, Player hunter) {
        // Heal immédiat pour être sûr
        hunter.setHealth(hunter.getMaxHealth());
        hunter.setNoDamageTicks(60);
        hunter.setFoodLevel(20);

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
        } else {
            ChatUtils.broadcast(team, plugin,
                    plugin.getConfig().getString("messages.hunter-death",
                                    "&c%s est mort ! Il lui reste &c%d vie(s).")
                            .replace("%s", hunter.getName())
                            .replace("%d", String.valueOf(lives)));
            applyHunterDeathPenalty(team, hunter);
        }
    }

    /**
     * Pénalité hunter après mort interceptée :
     * - Clear inventaire sauf boussole + hache de départ
     * - Adventure + slowness 127 + jump_boost 128 + blindness
     * - Verrouillage de position via BukkitRunnable (tp si bouge)
     * - 1m30 de pénalité, puis retour survival
     */
    private void applyHunterDeathPenalty(ManhuntTeam team, Player hunter) {
        // Sauvegarder boussole et hache
        ItemStack savedCompass = null;
        ItemStack savedAxe = null;
        for (ItemStack item : hunter.getInventory().getContents()) {
            if (item == null) continue;
            if (savedCompass == null && ManhuntCompass.isManhuntCompass(item))
                savedCompass = item.clone();
            if (savedAxe == null && isStarterAxe(item))
                savedAxe = item.clone();
        }

        hunter.getInventory().clear();
        hunter.clearActivePotionEffects();

        if (savedCompass != null) hunter.getInventory().addItem(savedCompass);
        if (savedAxe != null) hunter.getInventory().addItem(savedAxe);

        // Freeze total
        hunter.setGameMode(GameMode.ADVENTURE);
        hunter.setWalkSpeed(0f);
        int penaltySeconds = 90; // 1m30
        int penaltyTicks = penaltySeconds * 20;
        hunter.addPotionEffect(new PotionEffect(
                PotionEffectType.SLOWNESS, penaltyTicks + 60, 127, false, false));
        hunter.addPotionEffect(new PotionEffect(
                PotionEffectType.JUMP_BOOST, penaltyTicks + 60, 128, false, false));
        hunter.addPotionEffect(new PotionEffect(
                PotionEffectType.BLINDNESS, penaltyTicks + 60, 0, false, false));
        hunter.setHealth(hunter.getMaxHealth());

        hunter.sendMessage(ChatUtils.color(
                "&cVous avez perdu une vie ! Pénalité de &e1m30&c. Vous êtes immobilisé !"));

        Location penaltyLoc = hunter.getLocation().clone();

        BukkitRunnable penaltyTask = new BukkitRunnable() {
            int remaining = penaltySeconds;

            @Override
            public void run() {
                if (!hunter.isOnline()) { cancel(); return; }
                if (team.getState() != GameState.RUNNING) {
                    cancel();
                    return;
                }
                if (remaining <= 0) {
                    cancel();
                    // Si le hunter est encore dans la partie (pas éliminé entre-temps)
                    if (!team.isHunterEliminated(hunter.getUniqueId())
                            && hunter.getGameMode() == GameMode.ADVENTURE) {
                        hunter.removePotionEffect(PotionEffectType.SLOWNESS);
                        hunter.removePotionEffect(PotionEffectType.JUMP_BOOST);
                        hunter.removePotionEffect(PotionEffectType.BLINDNESS);
                        hunter.setWalkSpeed(0.2f);
                        hunter.setGameMode(GameMode.SURVIVAL);
                        hunter.sendMessage(ChatUtils.color("&aVous êtes de nouveau libre !"));
                    }
                    return;
                }
                // Verrouillage de position : tp si bouge (saut inclus via dy)
                if (hunter.getLocation().distanceSquared(penaltyLoc) > 0.1) {
                    hunter.teleport(penaltyLoc);
                }
                hunter.sendActionBar(ChatUtils.color("&cPénalité : &e" + remaining + "s"));
                remaining--;
            }
        };
        penaltyTask.runTaskTimer(plugin, 0L, 20L);
    }

    /**
     * Utilisé quand un hunter quitte le jeu (pas besoin de freeze, il est hors-ligne).
     */
    public boolean onHunterQuit(ManhuntTeam team, Player hunter) {
        int lives = team.getHunterLives(hunter.getUniqueId()) - 1;
        team.setHunterLives(hunter.getUniqueId(), lives);
        if (lives <= 0) {
            ChatUtils.broadcast(team, plugin,
                    plugin.getConfig().getString("messages.hunter-eliminated", "&c%s a été éliminé !")
                            .replace("%s", hunter.getName()));
            if (team.areAllHuntersEliminated()) {
                endGame(team, "runners-win");
            }
            return true;
        } else {
            ChatUtils.broadcast(team, plugin,
                    "&c" + hunter.getName() + " a quitté ! Il lui reste &c" + lives + " vie(s).");
            return false;
        }
    }

    // Compatibilité (appelé depuis PlayerListener pour le quit runner)
    public void onRunnerDeath(ManhuntTeam team) {
        endGame(team, "hunters-win");
    }

    // ────────────────────────────────────────────────────────────
    //  VÉRIFICATION OBJECTIF RUNNER
    // ────────────────────────────────────────────────────────────

    public void checkRunnerObjective(ManhuntTeam team, Player runner) {
        if (team.getState() != GameState.RUNNING) return;
        if (!runner.getUniqueId().equals(team.getRunnerId())) return;
        boolean achieved = team.isNetherEnabled() ? hasNetheriteIngot(runner) : hasFullDiamond(runner);
        if (achieved) endGame(team, "runner-win");
    }

    private boolean hasNetheriteIngot(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off  = player.getInventory().getItemInOffHand();
        return main.getType() == Material.NETHERITE_INGOT
                || off.getType() == Material.NETHERITE_INGOT;
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
            if (item.getType() == Material.DIAMOND_PICKAXE) pickaxe = true;
            if (item.getType() == Material.DIAMOND_SWORD) sword = true;
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
        cancelTask(team.getBriefingLockTaskId());

        String msg = switch (reason) {
            case "runner-win"             -> plugin.getConfig().getString("messages.runner-win",
                    "&6Le chassé a accompli son objectif ! Victoire !");
            case "hunters-win", "runners-win" -> plugin.getConfig().getString("messages.hunters-win",
                    "&cLe chassé est mort ! Victoire des chasseurs !");
            case "time-up"                -> plugin.getConfig().getString("messages.time-up",
                    "&cTemps écoulé ! Victoire des chasseurs !");
            default                       -> "&7Partie terminée.";
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
            ChatUtils.broadcastAll(plugin,
                    "&7Durée (Équipe &6" + team.getTeamId() + "&7) : &f" + formatTime(elapsed));
        }

        restorePvpStates(team);
    }

    // ────────────────────────────────────────────────────────────
    //  TIMER
    // ────────────────────────────────────────────────────────────

    private void startGameTimer(ManhuntTeam team) {
        int maxSeconds = plugin.getConfig().getInt("settings.max-duration", 120) * 60;
        BukkitRunnable task = new BukkitRunnable() {
            int remaining = maxSeconds;
            @Override
            public void run() {
                if (team.getState() != GameState.RUNNING) { cancel(); return; }
                if (remaining <= 0) { cancel(); endGame(team, "time-up"); return; }
                if (remaining == 1800 || remaining == 900
                        || remaining == 300 || remaining == 60) {
                    ChatUtils.broadcast(team, plugin,
                            "&e⏳ Il reste &6" + remaining / 60 + " minute(s) !");
                }
                remaining--;
            }
        };
        task.runTaskTimer(plugin, 0L, 20L);
        team.setTimerTaskId(task.getTaskId());
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
        runner.addPotionEffect(new PotionEffect(
                PotionEffectType.INVISIBILITY, duration * 20, 0, false, false));
        ChatUtils.broadcast(team, plugin,
                "&5Le chassé a bu une &dPotion Furtive &5! Invisible pendant &d"
                        + (duration / 60) + " minutes&5 !");
        new BukkitRunnable() {
            @Override
            public void run() {
                if (team.getState() != GameState.RUNNING) return;
                team.setStealthActive(false);
                runner.removePotionEffect(PotionEffectType.INVISIBILITY);
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
            if (team.getState() == GameState.RUNNING) endGame(team, "reset");
        }
    }

    // ────────────────────────────────────────────────────────────
    //  UTILITAIRES
    // ────────────────────────────────────────────────────────────

    private void cancelTask(int taskId) {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
    }

    public String formatTime(long seconds) {
        long m = seconds / 60;
        long s = seconds % 60;
        return String.format("%02d:%02d", m, s);
    }

    private void broadcastRules(ManhuntTeam team) {
        String obj = team.isNetherEnabled()
                ? "Tenir un Lingot de Netherite en main"
                : "Obtenir un équipement complet en diamant";
        int maxDuration = plugin.getConfig().getInt("settings.max-duration", 120);
        int hunterLives = plugin.getConfig().getInt("settings.hunter-lives", 2);
        ChatUtils.broadcast(team, plugin, "&8&m------------------------------------");
        ChatUtils.broadcast(team, plugin, "&6&lRègles - Équipe " + team.getTeamId());
        ChatUtils.broadcast(team, plugin, "&e• Objectif : &f" + obj);
        ChatUtils.broadcast(team, plugin, "&e• Nether : &f"
                + (team.isNetherEnabled() ? "Activé" : "Désactivé"));
        ChatUtils.broadcast(team, plugin, "&e• Durée max : &f" + maxDuration + " minutes");
        ChatUtils.broadcast(team, plugin, "&e• Vies des chasseurs : &f" + hunterLives);
        ChatUtils.broadcast(team, plugin, "&e• Bordure : &f2000×2000 blocs");
        ChatUtils.broadcast(team, plugin, "&8&m------------------------------------");
    }
}