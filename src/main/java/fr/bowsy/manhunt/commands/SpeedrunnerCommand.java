package fr.bowsy.manhunt.commands;

import fr.bowsy.manhunt.ManhuntPlugin;
import fr.bowsy.manhunt.managers.GameManager;
import fr.bowsy.manhunt.models.ManhuntTeam;
import fr.bowsy.manhunt.utils.ChatUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

public class SpeedrunnerCommand implements CommandExecutor, TabCompleter {

    private final ManhuntPlugin plugin;
    private final GameManager gm;

    public SpeedrunnerCommand(ManhuntPlugin plugin) {
        this.plugin = plugin;
        this.gm = plugin.getGameManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(ChatUtils.color("&cUsage: /speedrunner -p <pseudo> [equipe] | /speedrunner -roll [equipe]"));
            return true;
        }

        String flag = args[0].toLowerCase();

        switch (flag) {
            case "-p" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatUtils.color("&cUsage: /speedrunner -p <pseudo> [equipe]"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(ChatUtils.color("&cJoueur introuvable: &e" + args[1]));
                    return true;
                }

                String teamId = resolveTeamId(sender, args, 2, target);
                if (teamId == null) return true;

                if (gm.setSpeedrunner(teamId, target)) {
                    ChatUtils.send((Player) sender, plugin,
                            "&a" + target.getName() + " est maintenant le speedrunner de l'équipe &6" + teamId + "&a !");
                } else {
                    sender.sendMessage(ChatUtils.color("&cImpossible de définir le speedrunner. Vérifiez l'état de la partie."));
                }
            }
            case "-roll" -> {
                String teamId = resolveTeamId(sender, args, 1, null);
                if (teamId == null) return true;

                if (gm.rollSpeedrunner(teamId)) {
                    ManhuntTeam team = gm.getTeam(teamId);
                    Player runner = Bukkit.getPlayer(team.getRunnerId());
                    ChatUtils.send(sender instanceof Player p ? p : null, plugin,
                            "&aSpeedrunner tiré au sort pour l'équipe &6" + teamId + "&a : &e"
                                    + (runner != null ? runner.getName() : "?") + " !");
                    if (sender instanceof Player p) {
                        ChatUtils.send(p, plugin,
                                "&aSpeedrunner tiré au sort pour l'équipe &6" + teamId + " !");
                    }
                } else {
                    sender.sendMessage(ChatUtils.color("&cImpossible de tirer au sort. L'équipe est-elle vide ?"));
                }
            }
            default -> sender.sendMessage(ChatUtils.color("&cDrapeau inconnu. Utilisez -p ou -roll."));
        }

        return true;
    }

    /**
     * Résout le teamId à partir des args ou en cherchant l'équipe du joueur.
     */
    private String resolveTeamId(CommandSender sender, String[] args, int argIndex, Player target) {
        if (args.length > argIndex) {
            String id = args[argIndex];
            if (gm.getTeam(id) == null) {
                sender.sendMessage(ChatUtils.color("&cÉquipe inconnue: &e" + id));
                return null;
            }
            return id;
        }

        // Essayer de trouver l'équipe du target ou du sender
        Player ref = target != null ? target : (sender instanceof Player p ? p : null);
        if (ref != null) {
            ManhuntTeam team = gm.getTeamOfPlayer(ref.getUniqueId());
            if (team != null) return team.getTeamId();
        }

        // Si une seule équipe, utiliser celle-là
        Collection<ManhuntTeam> teams = gm.getAllTeams();
        if (teams.size() == 1) {
            return teams.iterator().next().getTeamId();
        }

        sender.sendMessage(ChatUtils.color("&cPlusieurs équipes existent. Précisez l'équipe: /speedrunner " + args[0] + " ... <equipe>"));
        return null;
    }

    private void send(Player p, String msg) {
        if (p != null) ChatUtils.send(p, plugin, msg);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) return List.of("-p", "-roll");
        if (args.length == 2 && args[0].equalsIgnoreCase("-p")) {
            List<String> names = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(p -> names.add(p.getName()));
            return names;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("-p")
                || args.length == 2 && args[0].equalsIgnoreCase("-roll")) {
            List<String> teams = new ArrayList<>();
            gm.getAllTeams().forEach(t -> teams.add(t.getTeamId()));
            return teams;
        }
        return List.of();
    }
}
