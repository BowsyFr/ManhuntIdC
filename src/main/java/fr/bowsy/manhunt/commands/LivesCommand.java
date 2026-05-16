package fr.bowsy.manhunt.commands;

import fr.bowsy.manhunt.ManhuntPlugin;
import fr.bowsy.manhunt.managers.GameManager;
import fr.bowsy.manhunt.models.ManhuntTeam;
import fr.bowsy.manhunt.utils.ChatUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.UUID;

public class LivesCommand implements CommandExecutor {

    private final ManhuntPlugin plugin;
    private final GameManager gm;

    public LivesCommand(ManhuntPlugin plugin) {
        this.plugin = plugin;
        this.gm = plugin.getGameManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ManhuntTeam team = null;

        if (args.length >= 1) {
            team = gm.getTeam(args[0]);
            if (team == null) {
                sender.sendMessage(ChatUtils.color("&cÉquipe inconnue: &e" + args[0]));
                return true;
            }
        } else if (sender instanceof Player p) {
            team = gm.getTeamOfPlayer(p.getUniqueId());
        }

        if (team == null) {
            Collection<ManhuntTeam> teams = gm.getAllTeams();
            if (teams.size() == 1) team = teams.iterator().next();
        }

        if (team == null) {
            sender.sendMessage(ChatUtils.color("&cVous n'êtes dans aucune équipe. Précisez: /lives <equipe>"));
            return true;
        }

        sender.sendMessage(ChatUtils.color("&6&lVies des chasseurs - Équipe " + team.getTeamId()));
        for (UUID uid : team.getHunterIds()) {
            Player h = Bukkit.getPlayer(uid);
            String name = h != null ? h.getName() : uid.toString().substring(0, 8);
            int lives = team.getHunterLives(uid);
            String status = lives <= 0 ? "&cÉliminé" : "&a" + lives + " vie(s)";
            sender.sendMessage(ChatUtils.color("  &7" + name + ": " + status));
        }
        return true;
    }
}
