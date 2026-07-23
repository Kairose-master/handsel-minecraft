package com.ledgermind.viz;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Every block this plugin writes into the world goes through here, so nothing
 * it builds is permanent damage: the ORIGINAL BlockData is remembered the
 * first time a location is touched, and {@link #restoreAll()} puts the world
 * back exactly as it was. MAIN THREAD ONLY.
 *
 * <p>A player's own build is never overwritten — {@link #place} only replaces
 * air, plants, snow and the like (see {@link #isReplaceable}), so dropping a
 * village or a mine on top of someone's house is impossible by construction.
 */
public final class BlockCanvas {

    private final Map<Location, BlockData> original = new HashMap<>();
    /** Restore order matters (supports, then attachments) — undo newest first. */
    private final Deque<Location> touched = new ArrayDeque<>();

    /**
     * Place a block, remembering whatever was there.
     *
     * @return false when the spot holds something worth keeping (and nothing was changed)
     */
    public boolean place(Location loc, Material material) {
        Block block = loc.getBlock();
        if (!original.containsKey(key(loc)) && !isReplaceable(block.getType())) return false;
        remember(loc, block);
        block.setType(material, false); // no physics: stops sand falling / redstone cascades
        return true;
    }

    /**
     * Place a block over ANYTHING (natural ground included), still recording the
     * original for a clean restore. Use only for deliberate terraforming — a
     * plaza floor, a building footprint — never scattered over a play area, since
     * unlike {@link #place} it will overwrite a player's blocks too.
     */
    public void placeForce(Location loc, Material material) {
        Block block = loc.getBlock();
        remember(loc, block);
        block.setType(material, false);
    }

    /** Place with explicit BlockData (facing, waterlogged, …). */
    public boolean place(Location loc, BlockData data) {
        Block block = loc.getBlock();
        if (!original.containsKey(key(loc)) && !isReplaceable(block.getType())) return false;
        remember(loc, block);
        block.setBlockData(data, false);
        return true;
    }

    private void remember(Location loc, Block block) {
        Location k = key(loc);
        if (!original.containsKey(k)) {
            original.put(k, block.getBlockData().clone());
            touched.push(k);
        }
    }

    /** True when this location is currently one of ours. */
    public boolean owns(Location loc) {
        return original.containsKey(key(loc));
    }

    /** Undo one location (used by the redstone pulse). */
    public void restore(Location loc) {
        Location k = key(loc);
        BlockData data = original.remove(k);
        if (data != null) {
            touched.remove(k);
            k.getBlock().setBlockData(data, false);
        }
    }

    /** Put every touched block back the way it was. */
    public void restoreAll() {
        while (!touched.isEmpty()) {
            Location k = touched.pop();
            BlockData data = original.remove(k);
            if (data != null) k.getBlock().setBlockData(data, false);
        }
        original.clear();
    }

    public int size() { return original.size(); }

    /** Block coordinates only — two Locations in the same block must be one key. */
    private static Location key(Location loc) {
        return new Location(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    /** Nothing a player would miss. Deliberately conservative. */
    static boolean isReplaceable(Material m) {
        if (m.isAir()) return true;
        return switch (m) {
            case SHORT_GRASS, TALL_GRASS, FERN, LARGE_FERN, DEAD_BUSH, SNOW,
                 VINE, SEAGRASS, TALL_SEAGRASS, WATER, LILY_PAD,
                 DANDELION, POPPY, BLUE_ORCHID, ALLIUM, AZURE_BLUET,
                 OXEYE_DAISY, CORNFLOWER, LILY_OF_THE_VALLEY, SUNFLOWER,
                 LILAC, ROSE_BUSH, PEONY, SWEET_BERRY_BUSH, MOSS_CARPET -> true;
            default -> false;
        };
    }
}
