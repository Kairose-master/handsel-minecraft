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

    /** Payday interrupt state — a score rise sends the NPC to the bank to celebrate. */
    private enum Pay { NONE, TO_BANK, CELEBRATE }
    /** What the agent's live status has it doing at its station. */
    public enum Role { NONE, WORKSHOP, BOARD }

    private final Villager villager;
    private final TextDisplay label;
    private double lastScore;
    private double shownDelta;
    private int deltaTtl;

    private Agent lastAgent;
    private int lastRank;
    private Location home;
    /** Where this agent's real job status says it should be (workshop/board), or null. */
    private Location station;
    private Role role = Role.NONE;
    private boolean distressed;      // worker of a Disputed job
    private double drawnUsd;         // outstanding credit drawn (debt), 0 if none
    private Location house;          // where it sleeps at night
    private Pay pay = Pay.NONE;
    private int celebrateTtl;
    private int wanderCooldown;
    private Location wanderGoal;

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
        lastAgent = a;
        lastRank = rank;
        write(a, rank);
        joinFx();
    }

    /** True if this NPC's villager is the clicked entity. */
    public boolean matches(org.bukkit.entity.Entity e) {
        return e != null && villager.getUniqueId().equals(e.getUniqueId());
    }

    /** The live profile lines for this agent, for a right-click readout. */
    public java.util.List<String> profileLines() {
        Agent a = lastAgent;
        if (a == null) return java.util.List.of("§7(정보 로딩 중)");
        String col = tierColor(a.creditRating(), a.creditScore());
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add("§6⛏ §f" + a.name() + " §8(순위 #" + (lastRank + 1) + ")");
        out.add("  §7신용점수 " + col + (long) a.creditScore() + " §8· 등급 " + col + a.creditRating());
        out.add("  §7처리한 일 §f" + a.jobsDone() + " §8· 총수익 §2$" + fmt(a.earnedUsd()));
        if (drawnUsd > 0) out.add("  §6💰 대출(미상환) $" + fmt(drawnUsd));
        String doing = distressed ? "§c분쟁 처리 대기 중"
                : role == Role.WORKSHOP ? "작업소에서 작업 중"
                : role == Role.BOARD ? "게시판에서 일감 대기 중"
                : pay != Pay.NONE ? "은행에서 정산 중"
                : "광장에서 쉬는 중";
        out.add("  §8지금: §7" + doing);
        return out;
    }

    /**
     * Update the villager's home plaza spot (ranking reshuffles each poll). If
     * it's just standing around, snap it there; if it's mid-journey, it'll head
     * back to the new spot when done — don't yank it out of a payday walk.
     */
    public void setHome(Location loc) {
        this.home = loc.clone();
        // Snap only if it's just standing at home with nothing else going on.
        if (pay == Pay.NONE && station == null && villager.getLocation().distanceSquared(loc) > 64) {
            place(loc);
        }
    }

    /**
     * Where this agent's real Ledgermind status wants it — the workshop while it
     * works a job, the board while it has an open bounty out, or null to send it
     * back home. Drives the town's live foot traffic.
     */
    public void setStation(Location loc, Role role) {
        this.station = loc == null ? null : loc.clone();
        this.role = role == null ? Role.NONE : role;
    }

    /** Worker of a Disputed job — shows agitation until the dispute clears. */
    public void setDistressed(boolean d) { this.distressed = d; }

    /** Outstanding credit this agent has drawn (real debt); 0 clears the marker. */
    public void setDrawn(double usd) { this.drawnUsd = Math.max(0, usd); }

    /** Where this NPC sleeps at night. */
    public void setHouse(Location loc) { this.house = loc == null ? null : loc.clone(); }

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
            pay = Pay.TO_BANK;
        } else if (delta < -0.5) {
            shownDelta = delta;
            deltaTtl = DELTA_POLLS;
        } else if (deltaTtl > 0) {
            deltaTtl--;
        }
        lastScore = a.creditScore();
        lastAgent = a;
        lastRank = rank;
        write(a, rank);
    }

    /**
     * One movement step. Called every couple of ticks by the village. Drives the
     * little state machine: walk to the bank on a payday, celebrate, walk home,
     * and otherwise stroll gently around the home spot so the plaza looks alive.
     */
    public void tickLife(Location bank, boolean night, int tickSeed) {
        var w = villager.getWorld();

        // A disputed worker is agitated wherever it is — angry sparks.
        if (distressed && w != null && (tickSeed & 7) == 0) {
            w.spawnParticle(Particle.ANGRY_VILLAGER, villager.getLocation().add(0, 2.3, 0), 2, .2, .2, .2, 0);
        }
        // Carrying debt shows a little dripping-gold reminder.
        if (drawnUsd > 0 && w != null && (tickSeed & 31) == 0) {
            w.spawnParticle(Particle.FALLING_DUST, villager.getLocation().add(0, 2.0, 0),
                    2, .2, .1, .2, 0, org.bukkit.Material.GOLD_BLOCK.createBlockData());
        }

        // Payday interrupt takes priority over everything.
        if (pay == Pay.TO_BANK) {
            if (stepToward(bank == null ? null : groundGoal(bank))) {
                pay = Pay.CELEBRATE;
                celebrateTtl = CELEBRATE_TICKS;
                if (w != null) {
                    Location at = villager.getLocation().add(0, 1.0, 0);
                    w.spawnParticle(Particle.HAPPY_VILLAGER, at, 20, .4, .5, .4, 0);
                    w.playSound(at, Sound.ENTITY_VILLAGER_CELEBRATE, 0.8f, 1.2f);
                }
            }
            return;
        }
        if (pay == Pay.CELEBRATE) {
            if (--celebrateTtl <= 0) pay = Pay.NONE;
            return;
        }

        // At night everyone turns in — the house wins over the day's station.
        if (night && house != null) {
            stepToward(house);
            return;
        }

        // Otherwise head to where our real job status says we belong.
        Location target = station != null ? station : home;
        if (!arrived(target)) {
            stepToward(target);
            wanderGoal = null;
            return;
        }
        // At our post: a worker hammers at the anvil, a waiting requester glances about.
        if (station != null) {
            if (w != null && role == Role.WORKSHOP && (tickSeed % 12) == 0) {
                Location at = villager.getLocation().add(0, 1.1, 0);
                w.spawnParticle(Particle.CRIT, at, 6, .3, .2, .3, .1);       // hammer sparks
                w.playSound(at, Sound.BLOCK_ANVIL_USE, 0.35f, 1.4f);
                villager.swingMainHand();
            } else if (w != null && role == Role.BOARD && (tickSeed & 31) == 0) {
                w.spawnParticle(Particle.ENCHANT, villager.getLocation().add(0, 1.4, 0), 3, .2, .3, .2, .3);
            }
            return;
        }
        if (wanderGoal != null) {
            if (stepToward(wanderGoal)) wanderGoal = null;
        } else if (--wanderCooldown <= 0) {
            wanderCooldown = 60 + (tickSeed % 80);           // ~3-7s between strolls
            double dx = ((tickSeed % 5) - 2) * 0.7;
            double dz = (((tickSeed / 5) % 5) - 2) * 0.7;
            wanderGoal = home.clone().add(dx, 0, dz);
        }
    }

    private boolean arrived(Location target) {
        if (target == null) return true;
        Location cur = villager.getLocation();
        double dx = target.getX() - cur.getX(), dz = target.getZ() - cur.getZ();
        return dx * dx + dz * dz < ARRIVE * ARRIVE;
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
        if (distressed) sb.append("\n§c⚠ 분쟁 중");
        if (drawnUsd > 0) sb.append("\n§6💰 대출 $").append(fmt(drawnUsd));
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
