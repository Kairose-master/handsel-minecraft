package com.ledgermind.viz;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;

/** The mining rig hologram — the miner's live status in-world. MAIN THREAD ONLY. */
public final class MinerRig {

    private final Location anchor;
    private TextDisplay panel;

    public MinerRig(Location anchor) { this.anchor = anchor.clone(); }

    public Location anchor() { return anchor.clone(); }

    public void render(Miner miner, String walletLine) {
        World world = anchor.getWorld();
        if (world == null) return;

        String status = switch (miner.state()) {
            case OFF -> "§8offline";
            case IDLE -> "§a● idle §8· waiting for work";
            case WORKING -> "§e⛏ working §8· task " + shorten(miner.currentTaskId());
            case ERROR -> "§c✖ " + shorten(miner.lastError());
        };

        String text = "§6⛏ §fLEDGERMIND MINER\n"
                + "§8agent " + miner.shortAgentId() + " §8· §7" + miner.model() + "\n"
                + status + "\n"
                + "§7done §f" + miner.tasksDone()
                + " §8· §7failed §f" + miner.tasksFailed()
                + (walletLine != null ? "\n§2" + walletLine : "");

        if (panel == null || panel.isDead()) {
            panel = world.spawn(anchor.clone().add(0, 1.2, 0), TextDisplay.class, td -> {
                td.setText(text);
                td.setBillboard(Display.Billboard.CENTER);
                td.setSeeThrough(true);
                td.setBackgroundColor(Color.fromARGB(160, 8, 12, 22));
                td.setPersistent(false);
            });
        } else {
            panel.setText(text);
        }
    }

    /** A task was picked up: the rig starts throwing sparks. */
    public void startFx() {
        World world = anchor.getWorld();
        if (world == null) return;
        Location at = anchor.clone().add(0, 1.0, 0);
        world.spawnParticle(Particle.ENCHANT, at, 40, .5, .6, .5, .6);
        world.playSound(at, Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, 0.8f);
    }

    /** A result was accepted (or rejected) by the platform. */
    public void doneFx(boolean success) {
        World world = anchor.getWorld();
        if (world == null) return;
        Location at = anchor.clone().add(0, 1.0, 0);
        if (success) {
            world.spawnParticle(Particle.HAPPY_VILLAGER, at, 30, .5, .6, .5, 0);
            world.playSound(at, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        } else {
            world.spawnParticle(Particle.SMOKE, at, 25, .4, .5, .4, .02);
            world.playSound(at, Sound.BLOCK_ANVIL_LAND, 0.5f, 1.6f);
        }
    }

    public void clear() {
        if (panel != null) { panel.remove(); panel = null; }
    }

    private static String shorten(String s) {
        if (s == null || s.isBlank()) return "-";
        return s.length() > 28 ? s.substring(0, 28) + "…" : s;
    }
}
