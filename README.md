# 🎯 Manhunt Plugin — fr.bowsy.manhunt
Plugin Manhunt **multi-équipes** pour **Paper 1.21.1**  
_(Développé pour l'événement Brickin Studio / Bowsy & Cypho)_

---

## 📦 Compilation

```bash
# Prérequis : JDK 21, Maven 3.6+
chmod +x build.sh
./build.sh
```

Le jar est généré dans `target/manhunt-1.0.0.jar`.  
Placer dans votre dossier `plugins/`.

---

## 🔌 Dépendances

| Dépendance | Obligatoire | Notes |
|---|---|---|
| Paper 1.21.1 | ✅ | API cible |
| Multiverse-Core | ✨ Recommandé | Meilleure gestion des mondes ; fallback natif Bukkit sinon |

---

## ⚙️ Configuration (`config.yml`)

```yaml
settings:
  max-duration: 120        # Durée max en minutes
  freeze-duration: 90      # Secondes de freeze des chasseurs
  briefing-duration: 30    # Secondes de briefing avant lancement
  hunter-lives: 2          # Vies par chasseur
  border-size: 1000        # Demi-côté de la bordure (→ 2000×2000 réels)
  nether-enabled: true     # false → objectif diamant, true → objectif Netherite
  stealth-potion-duration: 240  # Durée furtivité en secondes (4min)
  respawn-invincibility: 5 # Secondes d'invincibilité après respawn chasseur
  mob-debuff-radius: 10    # Rayon du debuff mobs autour du runner
```

---

## 🖥️ Commandes

### Gestion des équipes
```
/manhunt create <id>              Créer une équipe (génère les mondes)
/manhunt delete <id>              Supprimer une équipe
/manhunt list                     Lister toutes les équipes
/manhunt addplayer <equipe> <joueur>  Ajouter un joueur à une équipe
/manhunt info [equipe]            Afficher l'état d'une équipe
```

### Jeu
```
/speedrunner -p <pseudo> [equipe]  Définir le speedrunner
/speedrunner -roll [equipe]        Tirer un speedrunner au hasard
/start [equipe]                    Lancer la partie
/reset [equipe]                    Réinitialiser la partie
/lives [equipe]                    Voir les vies des chasseurs
```

> Si une seule équipe existe, le paramètre `[equipe]` est optionnel.

---

## 🚀 Workflow type (plusieurs équipes simultanées)

```bash
# 1. Créer les équipes (génère les mondes en arrière-plan)
/manhunt create A
/manhunt create B

# 2. Ajouter les joueurs dans chaque équipe
/manhunt addplayer A Steve
/manhunt addplayer A Alex
/manhunt addplayer A Notch
/manhunt addplayer B Jeb
/manhunt addplayer B Dinnerbone

# 3. Tirer les speedrunners au hasard
/speedrunner -roll A
/speedrunner -roll B

# 4. Lancer les deux parties simultanément
/start A
/start B

# 5. Reset après la partie
/reset A
/reset B
```

---

## 🌍 Gestion des mondes

- Chaque équipe obtient son propre **Overworld** et **Nether** dédiés
- Noms des mondes : `manhunt_<teamId>_overworld` / `manhunt_<teamId>_nether`
- La génération se fait au `/manhunt create` (asynchrone, affichage d'un message à la fin)
- Le **Nether** est créé **sans structures vanilla** (`generateStructures=false`)
- Les mondes sont déchargés (et sauvegardés) au `/reset`

---

## ✨ Avantages du Speedrunner

| Avantage | Description |
|---|---|
| **CutClean** | Les minerais sont auto-fondus au minage (fer→lingot, or→lingot, debris→scrap…) |
| **Haste permanent** | Effet Haste I maintenu en continu |
| **Force + Vitesse** | Force I et Speed I permanents |
| **Boussole intelligente** | Indique la distance et pointe vers le chasseur le plus proche |
| **Debuff mobs** | Faiblesse + Lenteur aux mobs hostiles dans un rayon de 10 blocs |
| **Potion Furtive** | 4 minutes de furtivité (boussole chasseurs brouillée) — craftable une seule fois |

### Recette Potion Furtive
```
[ ]  [🌸]  [ ]
[🌸] [🍶]  [🌸]
[ ]  [🔥]  [ ]

🌸 = Fleur de muguet (Lily of the Valley)
🍶 = Flacon en verre
🔥 = Poudre de Blaze
```

---

## 🎯 Objectifs

| Config Nether | Objectif du runner |
|---|---|
| `nether-enabled: true` | Obtenir **au moins un objet en Netherite** |
| `nether-enabled: false` | **Armure complète en diamant** + Épée diamant + Pioche diamant |

---

## 🏆 Conditions de fin

- **Victoire chasseurs** : Runner tué / Runner DC / Temps écoulé (2h)
- **Victoire runner** : Objectif accompli (Netherite ou full diamant)
- **Bonus** : Si tous les chasseurs sont éliminés (0 vies) → victoire runner automatique

---

## 📹 Conseil pour les modérateurs

Utiliser [FlashBack (Mod Minecraft)](https://modrinth.com/mod/flashback) ou OBS pour enregistrer l'intégralité de l'événement.
