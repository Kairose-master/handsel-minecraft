package com.ledgermind.viz;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Credit score as architecture: every agent gets a tower behind its NPC, one
 * floor per 100 points, built from the material of its rating tier. Walking
 * through the village tells you the whole leaderboard without reading a single
 * number — and when an agent earns its way up a tier, the tower visibly
 * rebuilds in better material. MAIN THREAD ONLY.
 */
public final class CreditTower {

    private static final int POINTS_PER_FLOOR = 100;
    private static final int MAX_FLOORS = 10;      // 990 is the score ceiling

    private final BlockCanvas canvas;
    /** agent name -> floors currently standing, so we only rebuild on change. */
    private final Map<String, Integer> heights = new HashMap<>();
    private final Map<String, Material> materials = new HashMap<>();

    public CreditTower(BlockCanvas canvas) { this.canvas = canvas; }

    /**
     * Build or update one agent's tower at {@code base} (the NPC's feet).
     * The column rises directly behind the villager so it never blocks the
     * name hologram.
     */
    public void update(Location base, Agent agent) {
        int floors = Math.min(MAX_FLOORS, (int) (agent.creditScore() / POINTS_PER_FLOOR));
        Material material = tierMaterial(agent.creditRating(), agent.creditScore());

        Integer wasFloors = heights.get(agent.name());
        Material wasMaterial = materials.get(agent.name());
        if (wasFloors != null && wasFloors == floors && material == wasMaterial) return;

        Location column = base.clone().add(0, 0, -1.0); // one block behind the NPC
        World world = column.getWorld();
        if (world == null) return;

        // Clear the old column first so a shrinking score visibly demolishes.
        for (int y = 0; y < MAX_FLOORS; y++) {
            Location at = column.clone().add(0, y, 0);
            if (canvas.owns(at)) canvas.restore(at);
        }
        for (int y = 0; y < floors; y++) {
            canvas.place(column.clone().add(0, y, 0), material);
        }
        if (floors > 0) {
            // A cap block so a tower reads as finished, not cut off.
            canvas.place(column.clone().add(0, floors, 0), Material.LIGHT_GRAY_STAINED_GLASS);
        }

        boolean grew = wasFloors != null && floors > wasFloors;
        boolean upgraded = wasMaterial != null && material != wasMaterial;
        if (grew || upgraded) {
            Location top = column.clone().add(0.5, floors + 0.5, 0.5);
            world.spawnParticle(Particle.END_ROD, top, 25, .3, .4, .3, .02);
            world.playSound(top, upgraded ? Sound.BLOCK_BEACON_POWER_SELECT
                    : Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.4f);
        }

        heights.put(agent.name(), floors);
        materials.put(agent.name(), material);
    }

    /** Agents that left the feed shouldn't leave a tower standing. */
    public void forget(String agentName, Location base) {
        Location column = base.clone().add(0, 0, -1.0);
        for (int y = 0; y <= MAX_FLOORS; y++) {
            Location at = column.clone().add(0, y, 0);
            if (canvas.owns(at)) canvas.restore(at);
        }
        heights.remove(agentName);
        materials.remove(agentName);
    }

    public void clear() {
        heights.clear();
        materials.clear();
        // blocks themselves are restored by the shared canvas
    }

    /** Same tier bands as the hologram colours and the app's cardTier. */
    static Material tierMaterial(String rating, double score) {
        String r = rating == null ? "" : rating.toUpperCase(Locale.ROOT);
        if (r.startsWith("A")) return Material.GOLD_BLOCK;
        if (r.startsWith("B")) return Material.LAPIS_BLOCK;
        if (r.startsWith("C")) return Material.COPPER_BLOCK;
        return score > 0 ? Material.MOSS_BLOCK : Material.DIRT;
    }
}
