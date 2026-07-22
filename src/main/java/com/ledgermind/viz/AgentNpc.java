package com.ledgermind.viz;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;

import java.util.Locale;

/** One villager + its floating credit hologram. MAIN THREAD ONLY. */
public final class AgentNpc {
    /** How many polls the "▲ +12" score-change badge stays visible. */
    private static final int DELTA_POLLS = 4;

    private final Villager villager;
    private final TextDisplay label;
    private double lastScore;
    private double shownDelta;
    private int deltaTtl;

    public AgentNpc(Location loc, Agent a, int rank) {
        var world = loc.getWorld();
        villager = (Villager) world.spawnEntity(loc, EntityType.VILLAGER);
        villager.setAI(false);
        villager.setInvulnerable(true);
        villager.setSilent(true);
        villager.setPersistent(false);
        villager.setGravity(false);       // uneven ground shouldn't scatter the plaza
        villager.setProfession(Villager.Profession.NITWIT); // neutral look
        label = world.spawn(loc.clone().add(0, 2.2, 0), TextDisplay.class, td -> {
            td.setBillboard(Display.Billboard.CENTER);
            td.setSeeThrough(true);
            td.setBackgroundColor(Color.fromARGB(140, 8, 12, 22));
            td.setPersistent(false);
        });
        lastScore = a.creditScore();
        write(a, rank);
        joinFx();
    }

    /** Reposition without respawning (village anchor moved / ranking reshuffled). */
    public void moveTo(Location loc) {
        villager.teleport(loc);
        label.teleport(loc.clone().add(0, 2.2, 0));
    }

    public void update(Agent a, int rank) {
        double delta = a.creditScore() - lastScore;
        if (delta > 0.5) {
            var w = villager.getWorld();
            Location at = villager.getLocation().add(0, 2.4, 0);
            w.spawnParticle(Particle.HAPPY_VILLAGER, at, 16, .3, .3, .3, 0);
            w.playSound(at, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.8f);
            shownDelta = delta;
            deltaTtl = DELTA_POLLS;
        } else if (delta < -0.5) {
            shownDelta = delta;
            deltaTtl = DELTA_POLLS;
        } else if (deltaTtl > 0) {
            deltaTtl--;
        }
        lastScore = a.creditScore();
        write(a, rank);
    }

    /** A brand-new agent walking into the village gets its own little moment. */
    private void joinFx() {
        var w = villager.getWorld();
        Location at = villager.getLocation().add(0, 1.2, 0);
        w.spawnParticle(Particle.ENCHANT, at, 30, .4, .6, .4, .5);
        w.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.2f);
    }

    public Location location() { return villager.getLocation(); }

    public boolean isDead() { return villager.isDead() || label.isDead(); }

    private void write(Agent a, int rank) {
        String col = tierColor(a.creditRating(), a.creditScore());
        StringBuilder sb = new StringBuilder();
        sb.append(medal(rank)).append("§f").append(a.name()).append('\n');
        sb.append(col).append((long) a.creditScore())
          .append(" §8· ").append(col).append(a.creditRating());
        if (deltaTtl > 0 && Math.abs(shownDelta) > 0.5) {
            sb.append(shownDelta > 0
                    ? "  §a▲ +" + (long) shownDelta
                    : "  §c▼ " + (long) shownDelta);
        }
        if (a.jobsDone() > 0) {
            sb.append("\n§2$").append(fmt(a.earnedUsd()))
              .append(" §8· §7").append(a.jobsDone())
              .append(a.jobsDone() == 1 ? " job" : " jobs");
        }
        label.setText(sb.toString());
    }

    public void remove() {
        villager.remove();
        label.remove();
    }

    /** Podium markers for the top three — the leaderboard read at a glance. */
    private static String medal(int rank) {
        return switch (rank) {
            case 0 -> "§6★ ";  // ★ gold
            case 1 -> "§7★ ";  // ★ silver
            case 2 -> "§c★ ";  // ★ bronze
            default -> "";
        };
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.2f", v);
    }

    // Match the app's cardTier bands: Gold A / Sapphire B / Bronze C / Graphite unrated.
    static String tierColor(String rating, double score) {
        String r = rating == null ? "" : rating.toUpperCase(Locale.ROOT);
        if (r.startsWith("A")) return "§6";   // gold
        if (r.startsWith("B")) return "§b";   // sapphire
        if (r.startsWith("C")) return "§c";   // bronze-ish
        return score > 0 ? "§a" : "§8";       // green / graphite (unrated)
    }
}
