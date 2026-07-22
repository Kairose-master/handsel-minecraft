package com.ledgermind.viz;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Item;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Villager-per-agent plaza + the payment animation. MAIN THREAD ONLY. */
public final class AgentVillage {
    private static final int PER_ROW = 4;
    private static final double GAP = 2.5;

    private final Plugin plugin;
    private final Location center;
    private final Map<String, AgentNpc> npcs = new HashMap<>();
    private TextDisplay bank;

    public AgentVillage(Plugin plugin, Location center) {
        this.plugin = plugin;
        this.center = center.clone();
    }

    public Location center() { return center.clone(); }

    /**
     * @param agents   ranked best-first (the API returns them ordered by score)
     * @param vaultText MiniVault gauge line for the bank sign, or null to hide it
     */
    public void render(List<Agent> agents, String vaultText) {
        if (center.getWorld() == null) return;

        renderBank(vaultText);

        Set<String> now = new HashSet<>();
        int i = 0;
        for (Agent a : agents) {
            now.add(a.name());
            int row = i / PER_ROW, col = i % PER_ROW;
            Location loc = center.clone().add((col - (PER_ROW - 1) / 2.0) * GAP, 0, 3 + row * GAP);
            loc.setY(center.getY()); // keep the plaza flat regardless of terrain

            AgentNpc npc = npcs.get(a.name());
            if (npc == null || npc.isDead()) {
                npcs.put(a.name(), new AgentNpc(loc, a, i));
            } else {
                npc.moveTo(loc);
                npc.update(a, i);
            }
            i++;
        }
        npcs.entrySet().removeIf(e -> {
            if (!now.contains(e.getKey())) { e.getValue().remove(); return true; }
            return false;
        });
    }

    /** The §15 "BANK" marker at the head of the plaza, carrying the vault gauge. */
    private void renderBank(String vaultText) {
        Location loc = center.clone().add(0, 2.6, 0);
        String text = "§6🏦 §fLEDGERMIND BANK\n§8credit · lending · escrow"
                + (vaultText != null ? "\n§b" + vaultText : "");
        if (bank == null || bank.isDead()) {
            World world = loc.getWorld();
            if (world == null) return;
            bank = world.spawn(loc, TextDisplay.class, td -> {
                td.setText(text);
                td.setBillboard(Display.Billboard.CENTER);
                td.setSeeThrough(true);
                td.setBackgroundColor(Color.fromARGB(160, 8, 12, 22));
                td.setPersistent(false);
            });
        } else {
            bank.teleport(loc);
            bank.setText(text);
        }
    }

    /**
     * Animate a payment for a filled job: a gold nugget lerps from the requester's
     * NPC to the target (the worker's NPC when we can resolve one, otherwise the
     * job board), trailing particles, then a burst + cha-ching on arrival.
     *
     * <p>Job feeds carry {@code requesterLabel} (a shortened address like
     * {@code 0xea32…cB8A}) while NPCs are keyed by agent NAME, so a match often
     * isn't possible — see BUILD_PLAN §17. When either end doesn't resolve we
     * fall back to a burst at the board, which still reads fine on camera.
     */
    public void animatePayment(String requesterLabel, String workerLabel, Location boardLoc) {
        AgentNpc from = resolve(requesterLabel);
        AgentNpc to = resolve(workerLabel);

        Location start = from != null ? from.location().clone().add(0, 1.6, 0)
                : boardLoc.clone().add(0, 0.5, 0);
        Location end = to != null ? to.location().clone().add(0, 1.6, 0)
                : boardLoc.clone().add(0, 0.5, 0);

        World world = start.getWorld();
        if (world == null) return;

        if (start.distanceSquared(end) < 0.25) { // nothing resolved — burst in place
            burst(world, end);
            return;
        }

        ItemStack nugget = new ItemStack(Material.GOLD_NUGGET);
        Item coin = world.dropItem(start, nugget);
        coin.setGravity(false);
        coin.setVelocity(new Vector(0, 0, 0));
        coin.setPickupDelay(Integer.MAX_VALUE); // players can't grab the prop
        coin.setInvulnerable(true);
        coin.setPersistent(false);

        new BukkitRunnable() {
            private int tick = 0;
            private static final int DURATION = 20; // ~1s

            @Override public void run() {
                if (coin.isDead()) { cancel(); return; }
                double t = ++tick / (double) DURATION;
                if (t >= 1.0) {
                    coin.remove();
                    burst(world, end);
                    cancel();
                    return;
                }
                Location at = start.clone().add(end.clone().subtract(start).toVector().multiply(t));
                at.add(0, Math.sin(Math.PI * t) * 1.2, 0); // arc
                coin.teleport(at);
                world.spawnParticle(Particle.WAX_ON, at, 3, 0.05, 0.05, 0.05, 0);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private static void burst(World world, Location loc) {
        world.spawnParticle(Particle.HAPPY_VILLAGER, loc, 24, 0.4, 0.4, 0.4, 0);
        world.playSound(loc, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.4f);
    }

    /** NPCs are keyed by agent name; job labels rarely match, hence the null return. */
    private AgentNpc resolve(String label) {
        if (label == null || label.isBlank()) return null;
        AgentNpc exact = npcs.get(label);
        if (exact != null && !exact.isDead()) return exact;
        String needle = label.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, AgentNpc> e : npcs.entrySet()) {
            if (e.getKey().toLowerCase(Locale.ROOT).equals(needle) && !e.getValue().isDead()) {
                return e.getValue();
            }
        }
        return null;
    }

    public int size() { return npcs.size(); }

    public void clear() {
        npcs.values().forEach(AgentNpc::remove);
        npcs.clear();
        if (bank != null) { bank.remove(); bank = null; }
    }
}
