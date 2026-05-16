package fr.bowsy.manhunt.models;

public enum GameState {
    WAITING,    // Équipe créée, joueurs non assignés
    READY,      // Speedrunner assigné, prêt à lancer
    BRIEFING,   // Briefing en cours
    RUNNING,    // Partie en cours
    FINISHED    // Partie terminée
}
