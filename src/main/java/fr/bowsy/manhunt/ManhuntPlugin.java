package fr.bowsy.manhunt;

import fr.bowsy.manhunt.commands.*;
import fr.bowsy.manhunt.listeners.*;
import fr.bowsy.manhunt.managers.GameManager;
import fr.bowsy.manhunt.managers.WorldManager;
import org.bukkit.plugin.java.JavaPlugin;

public class ManhuntPlugin extends JavaPlugin {

    private static ManhuntPlugin instance;
    private GameManager gameManager;
    private WorldManager worldManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        this.worldManager = new WorldManager(this);
        this.gameManager = new GameManager(this);

        // Commandes
        getCommand("speedrunner").setExecutor(new SpeedrunnerCommand(this));
        getCommand("speedrunner").setTabCompleter(new SpeedrunnerCommand(this));
        getCommand("start").setExecutor(new StartCommand(this));
        getCommand("reset").setExecutor(new ResetCommand(this));
        getCommand("manhunt").setExecutor(new ManhuntCommand(this));
        getCommand("manhunt").setTabCompleter(new ManhuntCommand(this));
        getCommand("lives").setExecutor(new LivesCommand(this));

        // Listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new CraftListener(this), this);
        getServer().getPluginManager().registerEvents(new CompassListener(this), this);
        getServer().getPluginManager().registerEvents(new BlockListener(this), this);

        getLogger().info("Manhunt plugin activé !");
    }

    @Override
    public void onDisable() {
        if (gameManager != null) {
            gameManager.shutdownAll();
        }
        getLogger().info("Manhunt plugin désactivé.");
    }

    public static ManhuntPlugin getInstance() {
        return instance;
    }

    public GameManager getGameManager() {
        return gameManager;
    }

    public WorldManager getWorldManager() {
        return worldManager;
    }
}
