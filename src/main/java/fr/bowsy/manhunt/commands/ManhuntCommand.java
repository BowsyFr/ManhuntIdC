package fr.bowsy.manhunt.commands;

import fr.bowsy.manhunt.ManhuntPlugin;
import fr.bowsy.manhunt.managers.GameManager;
import fr.bowsy.manhunt.models.GameState;
import fr.bowsy.manhunt.models.ManhuntTeam;
import fr.bowsy.manhunt.utils.ChatUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.*;

public class ManhuntCommand implements CommandExecutor, TabCompleter {

    private final ManhuntPlugin plugin;
    private final GameManager gm;

    public ManhuntCommand(ManhuntPlugin plugin) {
        this.plugin = plugin;
        this.gm = plugin.getGameManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "create" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatUtils.colorComponent("&cUsage: /manhunt create <id>"));
                    return true;
                }
                String id = args[1];
                ManhuntTeam team = gm.createTeam(id);
                if (team == null) {
                    sender.sendMessage(ChatUtils.colorComponent("&cUne équipe avec l'id &e" + id + " &cexiste déjà."));
                } else {
                    sender.sendMessage(ChatUtils.colorComponent("&aÉquipe &6" + id + " &acréée !"));
                    sender.sendMessage(ChatUtils.colorComponent("&7Génération des mondes en cours..."));
                    // Création sur le thread principal (obligatoire pour Bukkit/Multiverse)
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        boolean ok = plugin.getWorldManager().createWorldsForTeam(team);
                        if (ok) {
                            sender.sendMessage(ChatUtils.colorComponent("&aMondes générés pour l'équipe &6" + id + "&a !"));
                        } else {
                            sender.sendMessage(ChatUtils.colorComponent("&cErreur lors de la génération des mondes !"));
                        }
                    });
                }
            }
            case "delete" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatUtils.colorComponent("&cUsage: /manhunt delete <id>"));
                    return true;
                }
                if (gm.deleteTeam(args[1])) {
                    sender.sendMessage(ChatUtils.colorComponent("&aÉquipe &6" + args[1] + " &asupprimée."));
                } else {
                    sender.sendMessage(ChatUtils.colorComponent("&cÉquipe inconnue: &e" + args[1]));
                }
            }
            case "list" -> {
                Collection<ManhuntTeam> teams = gm.getAllTeams();
                if (teams.isEmpty()) {
                    sender.sendMessage(ChatUtils.colorComponent("&7Aucune équipe créée."));
                    return true;
                }
                sender.sendMessage(ChatUtils.colorComponent("&6&lÉquipes Manhunt :"));
                for (ManhuntTeam team : teams) {
                    String runnerName = team.getRunnerId() != null
                            ? Optional.ofNullable(Bukkit.getPlayer(team.getRunnerId()))
                            .map(Player::getName).orElse("?")
                            : "Non assigné";
                    sender.sendMessage(ChatUtils.colorComponent("  &e" + team.getTeamId()
                            + " &7| État: &f" + team.getState().name()
                            + " &7| Runner: &f" + runnerName
                            + " &7| Chasseurs: &f" + team.getHunterIds().size()));
                }
            }
            case "addplayer" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatUtils.colorComponent("&cUsage: /manhunt addplayer <equipe> <joueur>"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[2]);
                if (target == null) {
                    sender.sendMessage(ChatUtils.colorComponent("&cJoueur introuvable: &e" + args[2]));
                    return true;
                }
                if (gm.addPlayerToTeam(args[1], target)) {
                    sender.sendMessage(ChatUtils.colorComponent("&a" + target.getName() + " ajouté à l'équipe &6" + args[1] + "&a."));
                } else {
                    sender.sendMessage(ChatUtils.colorComponent("&cImpossible d'ajouter le joueur. Équipe inconnue ou joueur déjà dans une équipe."));
                }
            }
            case "info" -> {
                String teamId = args.length >= 2 ? args[1] : null;
                if (teamId == null && sender instanceof Player p) {
                    ManhuntTeam t = gm.getTeamOfPlayer(p.getUniqueId());
                    if (t != null) teamId = t.getTeamId();
                }
                if (teamId == null) {
                    sender.sendMessage(ChatUtils.colorComponent("&cUsage: /manhunt info <equipe>"));
                    return true;
                }
                ManhuntTeam team = gm.getTeam(teamId);
                if (team == null) {
                    sender.sendMessage(ChatUtils.colorComponent("&cÉquipe inconnue: &e" + teamId));
                    return true;
                }
                printTeamInfo(sender, team);
            }
            default -> sendHelp(sender);
        }

        return true;
    }

    private void printTeamInfo(CommandSender sender, ManhuntTeam team) {
        sender.sendMessage(ChatUtils.colorComponent("&8&m-------------------------------"));
        sender.sendMessage(ChatUtils.colorComponent("&6&lInfo - Équipe " + team.getTeamId()));
        sender.sendMessage(ChatUtils.colorComponent("&eÉtat: &f" + team.getState().name()));
        sender.sendMessage(ChatUtils.colorComponent("&eNether: &f" + (team.isNetherEnabled() ? "Activé" : "Désactivé")));

        String runnerName = "Non assigné";
        if (team.getRunnerId() != null) {
            Player r = Bukkit.getPlayer(team.getRunnerId());
            runnerName = r != null ? r.getName() : "[hors-ligne]";
        }
        sender.sendMessage(ChatUtils.colorComponent("&eSpeedrunner: &f" + runnerName));

        sender.sendMessage(ChatUtils.colorComponent("&eChasseurs (&f" + team.getHunterIds().size() + "&e):"));
        for (java.util.UUID uid : team.getHunterIds()) {
            Player h = Bukkit.getPlayer(uid);
            String name = h != null ? h.getName() : uid.toString().substring(0, 8) + "...";
            int lives = team.getHunterLives(uid);
            sender.sendMessage(ChatUtils.colorComponent("  &7- &f" + name + " &7(&c" + lives + " vie(s)&7)"));
        }

        if (team.getStartTime() > 0) {
            long elapsed = (System.currentTimeMillis() - team.getStartTime()) / 1000;
            sender.sendMessage(ChatUtils.colorComponent("&eDurée: &f" + elapsed / 60 + "m " + elapsed % 60 + "s"));
        }
        sender.sendMessage(ChatUtils.colorComponent("&8&m-------------------------------"));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatUtils.colorComponent("&6&lManhunt - Commandes:"));
        sender.sendMessage(ChatUtils.colorComponent("  &e/manhunt create <id> &7- Créer une équipe"));
        sender.sendMessage(ChatUtils.colorComponent("  &e/manhunt delete <id> &7- Supprimer une équipe"));
        sender.sendMessage(ChatUtils.colorComponent("  &e/manhunt list &7- Lister les équipes"));
        sender.sendMessage(ChatUtils.colorComponent("  &e/manhunt addplayer <equipe> <joueur> &7- Ajouter un joueur"));
        sender.sendMessage(ChatUtils.colorComponent("  &e/manhunt info [equipe] &7- Info d'une équipe"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) return List.of("create", "delete", "list", "addplayer", "info");
        List<String> teams = new ArrayList<>();
        gm.getAllTeams().forEach(t -> teams.add(t.getTeamId()));
        if (args.length == 2 && !args[0].equalsIgnoreCase("create")) return teams;
        if (args.length == 3 && args[0].equalsIgnoreCase("addplayer")) {
            List<String> names = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(p -> names.add(p.getName()));
            return names;
        }
        return List.of();
    }
}