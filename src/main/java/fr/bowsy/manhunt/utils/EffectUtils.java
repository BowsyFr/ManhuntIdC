package fr.bowsy.manhunt.utils;

import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class EffectUtils {

    /** Applique les effets permanents du runner : Force I, Vitesse I, Haste I. */
    public static void applyRunnerPermanentEffects(Player runner) {
        int duration = 200; // 10s, réappliqué toutes les 4s (80 ticks)

        runner.addPotionEffect(new PotionEffect(
                PotionEffectType.STRENGTH, duration, 0, false, false, true));
        runner.addPotionEffect(new PotionEffect(
                PotionEffectType.SPEED, duration, 0, false, false, true));
        runner.addPotionEffect(new PotionEffect(
                PotionEffectType.HASTE, duration, 0, false, false, true));
    }

    /** Retire tous les effets permanents du runner (en fin de partie). */
    public static void removeRunnerEffects(Player runner) {
        runner.removePotionEffect(PotionEffectType.STRENGTH);
        runner.removePotionEffect(PotionEffectType.SPEED);
        runner.removePotionEffect(PotionEffectType.HASTE);
    }

    /** Lance un feu d'artifice à la position donnée. */
    public static void playEndFirework(Location location) {
        World world = location.getWorld();
        if (world == null) return;
        Firework fw = world.spawn(location, Firework.class);
        FireworkMeta meta = fw.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder()
                .with(FireworkEffect.Type.BALL_LARGE)
                .withColor(Color.ORANGE, Color.YELLOW)
                .withFade(Color.WHITE)
                .withFlicker()
                .build());
        meta.setPower(1);
        fw.setFireworkMeta(meta);
    }
}
