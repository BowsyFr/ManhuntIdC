package fr.bowsy.manhunt.commands;

import fr.bowsy.manhunt.ManhuntPlugin;
import fr.bowsy.manhunt.managers.GameManager;
import fr.bowsy.manhunt.models.ManhuntTeam;
import fr.bowsy.manhunt.utils.ChatUtils;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

public class StartCommand implements CommandExecutor {

    private final ManhuntPlugin plugin;
    private final GameManager gm;

    public StartCommand(ManhuntPlugin plugin) {
        this.plugin = plugin;
        this.gm = plugin.getGameManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String teamId = resolveTeamId(sender, args);
        if (teamId == null) return true;

        boolean ok = gm.startGame(teamId);
        if (!ok) {
            sender.sendMessage(ChatUtils.color(
                    "&cImpossible de démarrer. Vérifiez que l'équipe existe, qu'un speedrunner est assigné et que la partie n'est pas déjà lancée."));
        }
        return true;
    }

    private String resolveTeamId(CommandSender sender, String[] args) {
        if (args.length >= 1) {
            String id = args[0];
            if (gm.getTeam(id) != null) return id;
            sender.sendMessage(ChatUtils.color("&cÉquipe inconnue: &e" + id));
            return null;
        }
        if (sender instanceof Player p) {
            ManhuntTeam team = gm.getTeamOfPlayer(p.getUniqueId());
            if (team != null) return team.getTeamId();
        }
        Collection<ManhuntTeam> teams = gm.getAllTeams();
        if (teams.size() == 1) return teams.iterator().next().getTeamId();
        sender.sendMessage(ChatUtils.color("&cPlusieurs équipes. Précisez: /start <equipe>"));
        return null;
    }
}
