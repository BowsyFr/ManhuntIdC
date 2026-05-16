package fr.bowsy.manhunt.managers;

import fr.bowsy.manhunt.ManhuntPlugin;
import fr.bowsy.manhunt.models.ManhuntTeam;
import org.bukkit.*;
import org.bukkit.generator.ChunkGenerator;

import java.io.File;
import java.util.logging.Level;

public class WorldManager {

    private final ManhuntPlugin plugin;
    private final boolean multiverseAvailable;

    public WorldManager(ManhuntPlugin plugin) {
        this.plugin = plugin;
        this.multiverseAvailable = Bukkit.getPluginManager().getPlugin("Multiverse-Core") != null;
        if (multiverseAvailable) {
            plugin.getLogger().info("Multiverse-Core détecté, utilisation pour la gestion des mondes.");
        } else {
            plugin.getLogger().warning("Multiverse-Core non détecté, utilisation de l'API Bukkit native.");
        }
    }

    /**
     * Crée et charge l'Overworld et le Nether pour une équipe donnée.
     * Les mondes sont créés AVANT le démarrage de la partie.
     */
    public boolean createWorldsForTeam(ManhuntTeam team) {
        String teamId = team.getTeamId();
        String owName = "manhunt_" + teamId + "_overworld";
        String netherName = "manhunt_" + teamId + "_nether";

        // --- Overworld ---
        World overworld = loadOrCreateWorld(owName, World.Environment.NORMAL, false);
        if (overworld == null) {
            plugin.getLogger().severe("Impossible de créer le monde overworld pour l'équipe " + teamId);
            return false;
        }
        configureWorld(overworld, team);
        team.setOverworld(overworld);

        // --- Nether (si activé) ---
        if (team.isNetherEnabled()) {
            World nether = loadOrCreateWorld(netherName, World.Environment.NETHER, true);
            if (nether == null) {
                plugin.getLogger().severe("Impossible de créer le Nether pour l'équipe " + teamId);
                return false;
            }
            configureWorld(nether, team);
            team.setNether(nether);
        }

        return true;
    }

    /**
     * Charge un monde existant ou en crée un nouveau.
     * noStructures : true pour le Nether sans structures vanilla.
     */
    private World loadOrCreateWorld(String name, World.Environment env, boolean noStructures) {
        // Vérifier si déjà chargé
        World existing = Bukkit.getWorld(name);
        if (existing != null) return existing;

        // Vérifier si le dossier existe (monde déjà généré précédemment)
        File worldFolder = new File(Bukkit.getWorldContainer(), name);

        if (multiverseAvailable) {
            return createWithMultiverse(name, env, noStructures, worldFolder.exists());
        } else {
            return createWithBukkit(name, env, noStructures);
        }
    }

    /**
     * Création via Multiverse-Core.
     * On passe par l'API de commande car l'API Java de MV est peu stable entre versions.
     */
    private World createWithMultiverse(String name, World.Environment env, boolean noStructures, boolean alreadyExists) {
        if (!alreadyExists) {
            String envFlag = switch (env) {
                case NETHER -> "nether";
                case THE_END -> "end";
                default -> "normal";
            };

            // --no-structures n'existe pas en MV, on génère sans structures via seed + générateur void partiel
            // Pour le Nether sans structures : on utilise le generator "VoidGenerator" si dispo,
            // sinon on crée avec Bukkit directement (plus fiable pour noStructures)
            if (noStructures) {
                plugin.getLogger().info("Nether sans structures : utilisation de l'API Bukkit pour " + name);
                return createWithBukkit(name, env, true);
            }

            String cmd = "mv create " + name + " " + envFlag;
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);

            // Attendre que le monde soit chargé (max 10s)
            for (int i = 0; i < 100; i++) {
                World w = Bukkit.getWorld(name);
                if (w != null) return w;
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
            plugin.getLogger().warning("Timeout lors de la création Multiverse du monde " + name + ", fallback Bukkit.");
            return createWithBukkit(name, env, noStructures);
        } else {
            // Monde déjà existant, charger via MV
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv load " + name);
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            return Bukkit.getWorld(name);
        }
    }

    /**
     * Création via l'API Bukkit native.
     * Utilise WorldCreator avec generateStructures(false) pour supprimer les structures.
     */
    private World createWithBukkit(String name, World.Environment env, boolean noStructures) {
        try {
            WorldCreator creator = new WorldCreator(name)
                    .environment(env)
                    .generateStructures(!noStructures)
                    .seed(System.currentTimeMillis()); // seed aléatoire par partie

            World world = creator.createWorld();
            if (world != null) {
                plugin.getLogger().info("Monde créé (Bukkit) : " + name + " | Structures: " + !noStructures);
            }
            return world;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Erreur création monde " + name, e);
            return null;
        }
    }

    /** Configure la bordure, les règles de jeu et les paramètres du monde. */
    private void configureWorld(World world, ManhuntTeam team) {
        int borderSize = plugin.getConfig().getInt("settings.border-size", 1000);

        // Bordure de monde
        WorldBorder border = world.getWorldBorder();
        border.setCenter(0, 0);
        border.setSize(borderSize * 2); // diameter

        // Game rules
        world.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, false);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true);
        world.setGameRule(GameRule.NATURAL_REGENERATION, true);
        world.setDifficulty(Difficulty.NORMAL);

        // Spawn au centre
        world.setSpawnLocation(0, world.getHighestBlockYAt(0, 0) + 1, 0);
    }

    /**
     * Supprime les mondes d'une équipe après reset.
     * Ne supprime pas les fichiers pour permettre le rejeu (optionnel).
     */
    public void unloadTeamWorlds(ManhuntTeam team) {
        if (team.getOverworld() != null) {
            unloadWorld(team.getOverworld());
        }
        if (team.getNether() != null) {
            unloadWorld(team.getNether());
        }
    }

    private void unloadWorld(World world) {
        // Téléporter les joueurs encore présents vers le monde par défaut
        World fallback = Bukkit.getWorlds().stream()
                .filter(w -> !w.getName().startsWith("manhunt_"))
                .findFirst()
                .orElse(Bukkit.getWorlds().get(0));

        world.getPlayers().forEach(p -> p.teleport(fallback.getSpawnLocation()));

        if (multiverseAvailable) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv unload " + world.getName());
        } else {
            Bukkit.unloadWorld(world, true); // save=true
        }
    }

    public boolean isMultiverseAvailable() {
        return multiverseAvailable;
    }
}
