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

/** One villager + its floating credit hologram, that walks the village alive. MAIN THREAD ONLY. */
public final class AgentNpc {
    /** How many polls the "▲ +12" score-change badge stays visible. */
    private static final int DELTA_POLLS = 4;
    private static final double STEP = 0.16;      // blocks per movement tick
    private static final double ARRIVE = 0.5;     // "close enough" to the goal
    private static final int CELEBRATE_TICKS = 40;

    /** What the villager is doing right now. */
    private enum Phase { IDLE, TO_BANK, CELEBRATE, TO_HOME, WANDER }

    private final Villager villager;
    private final TextDisplay label;
    private double lastScore;
    private double shownDelta;
    private int deltaTtl;

    private Location home;
    private Location goal;
    private Phase phase = Phase.IDLE;
    private int celebrateTtl;
    private int wanderCooldown;

    public AgentNpc(Location loc, Agent a, int rank) {
        var world = loc.getWorld();
        this.home = loc.clone();
        villager = (Villager) world.spawnEntity(loc, EntityType.VILLAGER);
        villager.setAI(false);
        villager.setInvulnerable(true);
        villager.setSilent(true);
        villager.setPersistent(false);
        villager.setGravity(false);       // we drive movement ourselves, no falling
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

    /**
     * Update the villager's home plaza spot (ranking reshuffles each poll). If
     * it's just standing around, snap it there; if it's mid-journey, it'll head
     * back to the new spot when done — don't yank it out of a payday walk.
     */
    public void setHome(Location loc) {
        this.home = loc.clone();
        if (phase == Phase.IDLE) place(loc);
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
            // Score rose = it just got paid → parade to the bank and celebrate.
            phase = Phase.TO_BANK;
        } else if (delta < -0.5) {
            shownDelta = delta;
            deltaTtl = DELTA_POLLS;
        } else if (deltaTtl > 0) {
            deltaTtl--;
        }
        lastScore = a.creditScore();
        write(a, rank);
    }

    /**
     * One movement step. Called every couple of ticks by the village. Drives the
     * little state machine: walk to the bank on a payday, celebrate, walk home,
     * and otherwise stroll gently around the home spot so the plaza looks alive.
     */
    public void tickLife(Location bank, int tickSeed) {
        switch (phase) {
            case TO_BANK -> {
                if (bank != null) goal = groundGoal(bank);
                if (stepToward(goal)) {
                    phase = Phase.CELEBRATE;
                    celebrateTtl = CELEBRATE_TICKS;
                    var w = villager.getWorld();
                    if (w != null) {
                        Location at = villager.getLocation().add(0, 1.0, 0);
                        w.spawnParticle(Particle.HAPPY_VILLAGER, at, 20, .4, .5, .4, 0);
                        w.playSound(at, Sound.ENTITY_VILLAGER_CELEBRATE, 0.8f, 1.2f);
                    }
                }
            }
            case CELEBRATE -> {
                if (--celebrateTtl <= 0) { phase = Phase.TO_HOME; goal = home; }
            }
            case TO_HOME -> {
                if (stepToward(home)) phase = Phase.IDLE;
            }
            case WANDER -> {
                if (stepToward(goal)) phase = Phase.IDLE;
            }
            case IDLE -> {
                // Occasionally stroll to a nearby spot so nobody stands frozen.
                if (--wanderCooldown <= 0) {
                    wanderCooldown = 60 + (tickSeed % 80);        // ~3-7s between strolls
                    double dx = ((tickSeed % 5) - 2) * 0.7;
                    double dz = (((tickSeed / 5) % 5) - 2) * 0.7;
                    goal = home.clone().add(dx, 0, dz);
                    phase = Phase.WANDER;
                }
            }
        }
    }

    /** Step toward a target; returns true on arrival. Faces the direction of travel. */
    private boolean stepToward(Location target) {
        if (target == null) return true;
        Location cur = villager.getLocation();
        double dx = target.getX() - cur.getX();
        double dz = target.getZ() - cur.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < ARRIVE) return true;
        double nx = cur.getX() + dx / dist * STEP;
        double nz = cur.getZ() + dz / dist * STEP;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        Location next = new Location(cur.getWorld(), nx, home.getY(), nz, yaw, 0f);
        place(next);
        return false;
    }

    /** Move both the villager and its floating label together. */
    private void place(Location loc) {
        villager.teleport(loc);
        label.teleport(loc.clone().add(0, 2.2, 0));
    }

    /** A bank anchor is usually above ground; walk to it at plaza level. */
    private Location groundGoal(Location bank) {
        return new Location(bank.getWorld(), bank.getX(), home.getY(), bank.getZ());
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
