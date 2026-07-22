package com.ledgermind.viz;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Makes mining an actual Minecraft activity instead of a status line: a seam
 * of gold ore sits in front of the rig, a boss bar fills while the model
 * works, and a finished task cracks the ore open and drops a real gold ingot
 * into a chest you can walk up to and open. The chest IS the earnings
 * display — `/lm wallet` still reports the authoritative USDC figure, but the
 * pile of gold is what people actually react to. MAIN THREAD ONLY.
 */
public final class MineShaft {

    /** Ore blocks in the seam, mined one per completed task then replenished. */
    private static final int SEAM = 4;

    private final Plugin plugin;
    private final BlockCanvas canvas;
    private final Location anchor;
    private final BossBar bar;
    private final int expectedSeconds;

    private long startedAt;
    private boolean built;

    public MineShaft(Plugin plugin, BlockCanvas canvas, Location anchor, int expectedSeconds) {
        this.plugin = plugin;
        this.canvas = canvas;
        this.anchor = anchor.clone();
        this.expectedSeconds = Math.max(5, expectedSeconds);
        this.bar = Bukkit.createBossBar("§6⛏ Ledgermind miner", BarColor.YELLOW, BarStyle.SEGMENTED_10);
        this.bar.setVisible(false);
    }

    /** Lay the seam and the collection chest. Safe to call repeatedly. */
    public void build() {
        if (built) return;
        World world = anchor.getWorld();
        if (world == null) return;
        for (int i = 0; i < SEAM; i++) {
            canvas.place(oreAt(i), Material.GOLD_ORE);
        }
        canvas.place(chestLoc(), Material.CHEST);
        built = true;
    }

    /** Called when the miner picks up a task. */
    public void onStart() {
        build();
        startedAt = System.currentTimeMillis();
        bar.setTitle("§6⛏ mining… §7(Ledgermind)");
        bar.setColor(BarColor.YELLOW);
        bar.setProgress(0.0);
        bar.setVisible(true);
        for (Player p : anchor.getWorld().getPlayers()) bar.addPlayer(p);
    }

    /**
     * Advance the bar while the model works. The task's real duration is
     * unknown, so this eases toward 95% over the expected time and only snaps
     * to full on an actual result — an honest "still going" rather than a
     * fake countdown that finishes before the work does.
     */
    public void tickProgress() {
        if (!bar.isVisible()) return;
        double elapsed = (System.currentTimeMillis() - startedAt) / 1000.0;
        bar.setProgress(Math.max(0, Math.min(0.95, elapsed / expectedSeconds)));
        World world = anchor.getWorld();
        if (world != null) {
            world.spawnParticle(Particle.CRIT, oreAt(0).clone().add(0.5, 0.5, 0.5), 4, .3, .3, .3, 0);
        }
    }

    /** A task finished: crack the ore open (or spit smoke) and pay out. */
    public void onFinish(boolean success, double earnedIngots) {
        World world = anchor.getWorld();
        bar.setProgress(1.0);
        bar.setColor(success ? BarColor.GREEN : BarColor.RED);
        bar.setTitle(success ? "§a⛏ task delivered" : "§c⛏ task failed");
        if (world == null) return;

        Location ore = oreAt(0).clone().add(0.5, 0.5, 0.5);
        if (success) {
            world.spawnParticle(Particle.BLOCK, ore, 60, .4, .4, .4, 0,
                    Material.GOLD_BLOCK.createBlockData());
            world.playSound(ore, Sound.BLOCK_STONE_BREAK, 1f, 0.8f);
            world.playSound(ore, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            deposit(Math.max(1, (int) Math.round(earnedIngots)));
        } else {
            world.spawnParticle(Particle.SMOKE, ore, 30, .3, .4, .3, .02);
            world.playSound(ore, Sound.BLOCK_ANVIL_LAND, 0.5f, 1.5f);
        }
        // hide the bar shortly after, so the result is readable first
        plugin.getServer().getScheduler().runTaskLater(plugin, this::idle, 60L);
    }

    public void idle() {
        bar.setVisible(false);
        bar.removeAll();
    }

    /** Put the reward in the chest players can open. */
    private void deposit(int ingots) {
        Block block = chestLoc().getBlock();
        if (block.getType() != Material.CHEST) {
            canvas.place(chestLoc(), Material.CHEST);
            block = chestLoc().getBlock();
        }
        if (block.getState() instanceof Chest chest) {
            chest.getInventory().addItem(new ItemStack(Material.GOLD_INGOT, ingots));
            chest.update();
        }
    }

    public void clear() {
        idle();
        built = false;
        // blocks are restored through the shared canvas
    }

    private Location oreAt(int i) {
        return anchor.clone().add(1 + (i % 2), 0, (i / 2));
    }

    private Location chestLoc() {
        return anchor.clone().add(-1, 0, 0);
    }
}
