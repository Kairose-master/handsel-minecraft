package com.ledgermind.viz;

import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.Firework;
import org.bukkit.inventory.meta.FireworkMeta;

import java.util.List;

/**
 * Turns the town into a live spectacle: the power-plant marquee runs a light
 * wave, chimneys puff, and the whole thing "ramps up" with real activity —
 * every completed job pulses the factory, and the FULL-POWER lever kicks off a
 * 30-second show of cascading fireworks and blazing lamps. MAIN THREAD ONLY.
 *
 * <p>Lamp animation writes block LIT state directly (not through BlockCanvas):
 * the lamps themselves were placed via the canvas, so {@code /lm clear} still
 * restores everything — the per-tick lit toggles just don't need recording.
 */
public final class Spectacle {

    private final org.bukkit.plugin.Plugin plugin;
    private final List<Location> lamps;
    private final List<Location> chimneys;
    private final Location center;

    private int fullPowerTicks;     // remaining ticks of the FULL POWER show
    private int burst;              // short activity boost (a job just completed)

    public Spectacle(org.bukkit.plugin.Plugin plugin, Location center) {
        this.plugin = plugin;
        this.center = center.clone();
        this.lamps = TownBuilder.lampGrid(center);
        this.chimneys = TownBuilder.chimneys(center);
    }

    /** Is a given block the FULL-POWER lever? */
    public boolean isLever(Location block) {
        Location lever = TownBuilder.leverSpot(center);
        return block.getWorld() != null && block.getWorld().equals(lever.getWorld())
                && block.getBlockX() == lever.getBlockX()
                && block.getBlockY() == lever.getBlockY()
                && block.getBlockZ() == lever.getBlockZ();
    }

    /** Kick off the 30-second FULL POWER show. */
    public void fullPower(int seconds) {
        fullPowerTicks = Math.max(fullPowerTicks, seconds * 20);
        World w = center.getWorld();
        if (w != null) w.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 1f, 0.8f);
    }

    /** A job completed — a short factory surge. */
    public void pulse() {
        burst = Math.max(burst, 40);
        launchFirework(center.clone().add(0, 6, 0));
    }

    /**
     * One animation step (called on a fast timer). {@code tick} advances the
     * marquee wave; higher intensity from a burst or FULL POWER speeds it up
     * and adds effects.
     */
    public void tick(int tick) {
        boolean full = fullPowerTicks > 0;
        boolean hot = full || burst > 0;
        if (fullPowerTicks > 0) fullPowerTicks--;
        if (burst > 0) burst--;

        animateMarquee(tick, full);
        smoke(hot);

        // FULL POWER: a rolling firework cascade over the town + agent cheer.
        if (full && (tick % 6) == 0) {
            double a = (tick % 60) / 60.0 * Math.PI * 2;
            launchFirework(center.clone().add(Math.cos(a) * 8, 5, Math.sin(a) * 8 + 6));
        }
        if (full && (tick % 20) == 0) {
            World w = center.getWorld();
            if (w != null) w.playSound(center, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.8f);
        }
    }

    /** Redstone-lamp wave; when hot, the whole board blazes and strobes. */
    private void animateMarquee(int tick, boolean full) {
        int cols = 7; // matches TownBuilder LAMP_W
        for (int i = 0; i < lamps.size(); i++) {
            int col = i % cols;
            boolean lit = full
                    ? ((tick / 2) & 1) == 0                       // strobe everything
                    : ((col + tick / 3) % cols) < 2;              // a 2-wide light band sweeping across
            setLit(lamps.get(i), lit);
        }
    }

    private void smoke(boolean hot) {
        World w = center.getWorld();
        if (w == null) return;
        for (Location top : chimneys) {
            Location at = top.clone().add(0.5, 1.2, 0.5);
            w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at, hot ? 4 : 1, .1, .1, .1, hot ? 0.02 : 0.005);
            if (hot) w.spawnParticle(Particle.LARGE_SMOKE, at, 2, .1, .1, .1, 0.02);
        }
    }

    /** Turn every lamp off (called on disable so nothing's left mid-strobe). */
    public void allOff() {
        for (Location l : lamps) setLit(l, false);
    }

    private void setLit(Location loc, boolean on) {
        Block b = loc.getBlock();
        if (b.getType() != Material.REDSTONE_LAMP) return;
        if (b.getBlockData() instanceof Lightable l && l.isLit() != on) {
            l.setLit(on);
            b.setBlockData(l, false);
        }
    }

    private void launchFirework(Location at) {
        World w = at.getWorld();
        if (w == null) return;
        w.spawn(at, Firework.class, fw -> {
            FireworkMeta m = fw.getFireworkMeta();
            m.addEffect(FireworkEffect.builder()
                    .with(FireworkEffect.Type.BALL_LARGE)
                    .withColor(Color.fromRGB(255, 200, 40), Color.fromRGB(80, 220, 120), Color.AQUA)
                    .withFade(Color.WHITE).trail(true).flicker(true).build());
            m.setPower(1);
            fw.setFireworkMeta(m);
        });
    }
}
