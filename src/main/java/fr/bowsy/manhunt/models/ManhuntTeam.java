package fr.bowsy.manhunt.models;

import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.*;

public class ManhuntTeam {

    private final String teamId;
    private UUID runnerId;
    private final Set<UUID> hunterIds = new HashSet<>();
    private final Map<UUID, Integer> hunterLives = new HashMap<>();

    // Mondes dédiés à cette équipe
    private World overworld;
    private World nether;

    private GameState state = GameState.WAITING;
    private boolean netherEnabled;
    private boolean stealthActive = false;
    private long startTime = -1;
    private long stealthStartTime = -1;

    // Task IDs Bukkit pour annulation
    private int timerTaskId = -1;
    private int effectTaskId = -1;
    private int mobDebuffTaskId = -1;
    private int freezeTaskId = -1;
    private int compassTaskId = -1;

    public ManhuntTeam(String teamId, boolean netherEnabled) {
        this.teamId = teamId;
        this.netherEnabled = netherEnabled;
    }

    // ── Getters / Setters ────────────────────────────────────────────────────

    public String getTeamId() { return teamId; }

    public UUID getRunnerId() { return runnerId; }
    public void setRunnerId(UUID runnerId) { this.runnerId = runnerId; }

    public Set<UUID> getHunterIds() { return hunterIds; }

    public void addHunter(UUID uuid, int lives) {
        hunterIds.add(uuid);
        hunterLives.put(uuid, lives);
    }

    public void removeHunter(UUID uuid) {
        hunterIds.remove(uuid);
        hunterLives.remove(uuid);
    }

    public int getHunterLives(UUID uuid) {
        return hunterLives.getOrDefault(uuid, 0);
    }

    public void setHunterLives(UUID uuid, int lives) {
        hunterLives.put(uuid, lives);
    }

    public Map<UUID, Integer> getAllHunterLives() { return hunterLives; }

    public boolean isHunterEliminated(UUID uuid) {
        return hunterLives.getOrDefault(uuid, 0) <= 0;
    }

    public boolean areAllHuntersEliminated() {
        return hunterIds.stream().allMatch(this::isHunterEliminated);
    }

    public World getOverworld() { return overworld; }
    public void setOverworld(World overworld) { this.overworld = overworld; }

    public World getNether() { return nether; }
    public void setNether(World nether) { this.nether = nether; }

    public GameState getState() { return state; }
    public void setState(GameState state) { this.state = state; }

    public boolean isNetherEnabled() { return netherEnabled; }
    public void setNetherEnabled(boolean netherEnabled) { this.netherEnabled = netherEnabled; }

    public boolean isStealthActive() { return stealthActive; }
    public void setStealthActive(boolean stealthActive) { this.stealthActive = stealthActive; }

    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }

    public long getStealthStartTime() { return stealthStartTime; }
    public void setStealthStartTime(long stealthStartTime) { this.stealthStartTime = stealthStartTime; }

    public int getTimerTaskId() { return timerTaskId; }
    public void setTimerTaskId(int timerTaskId) { this.timerTaskId = timerTaskId; }

    public int getEffectTaskId() { return effectTaskId; }
    public void setEffectTaskId(int effectTaskId) { this.effectTaskId = effectTaskId; }

    public int getMobDebuffTaskId() { return mobDebuffTaskId; }
    public void setMobDebuffTaskId(int mobDebuffTaskId) { this.mobDebuffTaskId = mobDebuffTaskId; }

    public int getFreezeTaskId() { return freezeTaskId; }
    public void setFreezeTaskId(int freezeTaskId) { this.freezeTaskId = freezeTaskId; }

    public int getCompassTaskId() { return compassTaskId; }
    public void setCompassTaskId(int compassTaskId) { this.compassTaskId = compassTaskId; }

    /** Renvoie tous les UUIDs de l'équipe (runner + chasseurs) */
    public Set<UUID> getAllPlayerIds() {
        Set<UUID> all = new HashSet<>(hunterIds);
        if (runnerId != null) all.add(runnerId);
        return all;
    }
}
