package com.ledgermind.viz;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Stairs;

/**
 * Builds the town the agents live in — a tiled plaza, a gold-trimmed BANK at its
 * head, corner lamps, and side/back walls — so the village sits in an actual
 * place instead of empty flatland. Everything goes through the shared
 * {@link BlockCanvas}, so the whole town is remembered and restored by
 * {@code /lm clear} and on shutdown. MAIN THREAD ONLY.
 *
 * <p>Around the village CENTER (the {@code /lm village} anchor): the plaza runs
 * +Z (where the NPC grid stands), the bank sits at the −Z head with its door
 * facing the plaza, and a gold runway on the centre line leads to it — matching
 * the direction agents walk on a payday.
 */
public final class TownBuilder {

    // Plaza extent, relative to centre.
    private static final int HALF_X = 7;
    private static final int Z_FRONT = 0;    // plaza edge nearest the bank
    private static final int Z_BACK = 14;    // far edge

    private final BlockCanvas canvas;

    public TownBuilder(BlockCanvas canvas) { this.canvas = canvas; }

    // --- station spots agents walk to (shared with AgentVillage) ------------
    // The bank is at the plaza head; the workshop is east, the board kiosk west,
    // each reached by a road. AgentVillage uses these exact spots to route NPCs.

    public static Location bankSpot(Location c)     { return c.clone().add(0, 0, -1); }
    public static Location workshopSpot(Location c) { return c.clone().add(11, 0, 6); }
    public static Location boardSpot(Location c)    { return c.clone().add(-11, 0, 6); }

    // A row of little houses behind the plaza (south, +Z). Agents sleep here.
    private static final int HOUSE_COUNT = 8;
    private static final int HOUSE_GAP = 4;
    private static final int HOUSE_Z = 18;   // just south of the Z_BACK wall

    /** The doorstep of house i (agents cycle through them). */
    public static Location houseSpot(Location c, int i) {
        int idx = ((i % HOUSE_COUNT) - HOUSE_COUNT / 2);
        return c.clone().add(idx * HOUSE_GAP + 1, 0, HOUSE_Z + 1);
    }

    // --- spectacle feature positions (shared with Spectacle) ----------------
    // The power plant sits north, behind the bank; its front wall (facing the
    // plaza) is a marquee of redstone lamps the animator pulses.

    private static final int PLANT_Z = -13;   // front wall of the plant
    private static final int LAMP_W = 7;      // marquee width (odd)
    private static final int LAMP_H = 3;

    /** The redstone-lamp marquee grid, row-major (bottom row first). */
    public static java.util.List<Location> lampGrid(Location c) {
        java.util.List<Location> out = new java.util.ArrayList<>();
        for (int y = 0; y < LAMP_H; y++)
            for (int x = -(LAMP_W / 2); x <= LAMP_W / 2; x++)
                out.add(c.clone().add(x, 1 + y, PLANT_Z));
        return out;
    }

    /** The two chimney tops that puff smoke. */
    public static java.util.List<Location> chimneys(Location c) {
        return java.util.List.of(
                c.clone().add(-3, 6, PLANT_Z - 2),
                c.clone().add(3, 6, PLANT_Z - 2));
    }

    /** The FULL-POWER lever block (a lever on a pedestal at the plaza front). */
    public static Location leverSpot(Location c) { return c.clone().add(0, 1, 1); }

    /** The three beacon blocks (top-3 skyline), left to right. */
    public static java.util.List<Location> beaconSpots(Location c) {
        return java.util.List.of(
                c.clone().add(-4, 1, Z_BACK),
                c.clone().add(0, 1, Z_BACK),
                c.clone().add(4, 1, Z_BACK));
    }

    /** A stone-brick avenue linking two town centres into one city. L-shaped. */
    public void connectRoad(Location from, Location to) {
        World w = from.getWorld();
        if (w == null || to.getWorld() == null || !w.equals(to.getWorld())) return;
        int y = from.getBlockY() - 1;
        int x0 = from.getBlockX(), z0 = from.getBlockZ();
        int x1 = to.getBlockX(), z1 = to.getBlockZ();
        int sx = Integer.signum(x1 - x0), sz = Integer.signum(z1 - z0);
        // walk X then Z, laying a 3-wide avenue with the centre in quartz
        for (int x = x0; x != x1; x += (sx == 0 ? 1 : sx)) {
            avenueTile(w, x, y, z0);
            if (sx == 0) break;
        }
        for (int z = z0; z != z1 + sz; z += (sz == 0 ? 1 : sz)) {
            avenueTile(w, x1, y, z);
            if (sz == 0) break;
        }
    }

    private void avenueTile(World w, int x, int y, int z) {
        for (int d = -1; d <= 1; d++) {
            canvas.placeForce(at(w, x, y, z + d), d == 0 ? Material.SMOOTH_QUARTZ : Material.STONE_BRICKS);
            canvas.place(at(w, x, y + 1, z + d), Material.AIR);
        }
    }

    public void build(Location center) {
        World w = center.getWorld();
        if (w == null) return;
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
        plaza(w, cx, cy, cz);
        walls(w, cx, cy, cz);
        lamps(w, cx, cy, cz);
        bank(w, cx, cy, cz);
        roads(w, cx, cy, cz);
        workshop(w, cx, cy, cz);
        boardKiosk(w, cx, cy, cz);
        houses(w, cx, cy, cz);
        powerPlant(w, cx, cy, cz, center);
        beacons(w, cx, cy, cz, center);
        controlPedestal(w, cx, cy, cz, center);
    }

    /** The factory behind the bank: a lamp-marquee front wall, body, and chimneys. */
    private void powerPlant(World w, int cx, int cy, int cz, Location c) {
        int z0 = cz + PLANT_Z - 3, z1 = cz + PLANT_Z;   // body depth
        int x0 = cx - 4, x1 = cx + 4, y0 = cy, y1 = cy + 4;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                canvas.placeForce(at(w, x, y0 - 1, z), Material.POLISHED_BASALT);
                if (edge) {
                    for (int y = y0; y <= y1; y++) canvas.placeForce(at(w, x, y, z), Material.DEEPSLATE_BRICKS);
                } else {
                    for (int y = y0; y <= y1; y++) canvas.place(at(w, x, y, z), Material.AIR);
                }
                canvas.placeForce(at(w, x, y1 + 1, z), Material.DEEPSLATE_TILES);
            }
        }
        // marquee: redstone lamps set into the front wall (facing the plaza)
        for (Location lamp : lampGrid(c)) canvas.placeForce(lamp, Material.REDSTONE_LAMP);
        // chimneys
        for (Location top : chimneys(c)) {
            int bx = top.getBlockX(), bz = top.getBlockZ();
            for (int y = cy; y <= top.getBlockY(); y++) canvas.placeForce(at(w, bx, y, bz), Material.POLISHED_BASALT);
            canvas.placeForce(at(w, bx, top.getBlockY() + 1, bz), Material.CAMPFIRE); // gentle smoke source
        }
    }

    /** Three beacons on iron pyramids — top-3 skyline beams, gold/silver/bronze. */
    private void beacons(World w, int cx, int cy, int cz, Location c) {
        Material[] glass = { Material.ORANGE_STAINED_GLASS, Material.WHITE_STAINED_GLASS, Material.BROWN_STAINED_GLASS };
        java.util.List<Location> spots = beaconSpots(c);
        for (int i = 0; i < spots.size(); i++) {
            Location b = spots.get(i);
            int bx = b.getBlockX(), by = b.getBlockY(), bz = b.getBlockZ();
            // 3×3 iron base one level below powers the beam
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    canvas.placeForce(at(w, bx + dx, by - 1, bz + dz), Material.IRON_BLOCK);
            canvas.placeForce(at(w, bx, by, bz), Material.BEACON);
            canvas.placeForce(at(w, bx, by + 1, bz), glass[i]);  // beam colour
        }
    }

    /** A pedestal with the FULL-POWER lever at the plaza front. */
    private void controlPedestal(World w, int cx, int cy, int cz, Location c) {
        Location lever = leverSpot(c);
        int lx = lever.getBlockX(), ly = lever.getBlockY(), lz = lever.getBlockZ();
        canvas.placeForce(at(w, lx, ly - 1, lz), Material.CHISELED_QUARTZ_BLOCK); // pedestal
        canvas.place(at(w, lx, ly, lz), Material.LEVER);
    }

    /** A row of small houses south of the plaza — one per agent to sleep in at night. */
    private void houses(World w, int cx, int cy, int cz) {
        for (int i = 0; i < HOUSE_COUNT; i++) {
            int idx = i - HOUSE_COUNT / 2;
            int hx = cx + idx * HOUSE_GAP;
            int hz = cz + HOUSE_Z;
            house(w, hx, cy, hz);
        }
    }

    /** One 3×3 cabin with a door facing the plaza (−Z), a bed, and a lantern. */
    private void house(World w, int hx, int cy, int hz) {
        int x0 = hx - 1, x1 = hx + 1, z0 = hz, z1 = hz + 2;
        int y0 = cy, y1 = cy + 2;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                canvas.placeForce(at(w, x, y0 - 1, z), Material.SPRUCE_PLANKS); // floor
                if (edge) {
                    for (int y = y0; y <= y1; y++) {
                        Material m = (y == y1) ? Material.SPRUCE_LOG : Material.SPRUCE_PLANKS;
                        canvas.placeForce(at(w, x, y, z), m);
                    }
                } else {
                    for (int y = y0; y <= y1; y++) canvas.place(at(w, x, y, z), Material.AIR);
                }
                canvas.placeForce(at(w, x, y1 + 1, z), Material.SPRUCE_SLAB); // roof
            }
        }
        // door on the plaza-facing wall (−Z, z0), centred
        canvas.place(at(w, hx, y0, z0), Material.AIR);
        canvas.place(at(w, hx, y0 + 1, z0), Material.AIR);
        // interior: bed + lamp + window
        canvas.place(at(w, hx, y0, hz + 1), Material.RED_BED);
        canvas.place(at(w, hx, y1, hz + 1), Material.LANTERN);
        canvas.placeForce(at(w, x1, y0 + 1, hz + 1), Material.GLASS_PANE);
    }

    /** Tiled floor one block below plaza level; the centre line is a gold runway. */
    private void plaza(World w, int cx, int cy, int cz) {
        for (int x = -HALF_X; x <= HALF_X; x++) {
            for (int z = Z_FRONT; z <= Z_BACK; z++) {
                Material floor = x == 0 ? Material.SMOOTH_QUARTZ
                        : (((x + z) & 1) == 0 ? Material.SMOOTH_STONE : Material.POLISHED_ANDESITE);
                canvas.placeForce(at(w, cx + x, cy - 1, cz + z), floor);
                canvas.place(at(w, cx + x, cy, cz + z), Material.AIR); // clear tall grass etc.
            }
        }
    }

    /** Side and back low walls + corner lamps frame the plaza; the bank is the open front. */
    private void walls(World w, int cx, int cy, int cz) {
        for (int z = Z_FRONT; z <= Z_BACK; z++) {
            if (z >= 5 && z <= 7) continue; // gap where the roads leave the plaza
            post(w, cx - HALF_X, cy, cz + z);
            post(w, cx + HALF_X, cy, cz + z);
        }
        for (int x = -HALF_X; x <= HALF_X; x++) {
            post(w, cx + x, cy, cz + Z_BACK);
        }
    }

    private void lamps(World w, int cx, int cy, int cz) {
        int[][] corners = { {-HALF_X, Z_FRONT}, {HALF_X, Z_FRONT}, {-HALF_X, Z_BACK}, {HALF_X, Z_BACK} };
        for (int[] c : corners) {
            int x = cx + c[0], z = cz + c[1];
            canvas.place(at(w, x, cy, z), Material.OAK_FENCE);
            canvas.place(at(w, x, cy + 1, z), Material.OAK_FENCE);
            canvas.place(at(w, x, cy + 2, z), Material.LANTERN);
        }
    }

    /** A 5×5 gold-trimmed bank at the −Z head, door + steps facing the plaza (+Z). */
    private void bank(World w, int cx, int cy, int cz) {
        int zBack = cz - 5, zFront = cz - 1;   // front wall faces the plaza
        int x0 = cx - 2, x1 = cx + 2;
        int y0 = cy, y1 = cy + 3;

        for (int x = x0; x <= x1; x++) {
            for (int z = zBack; z <= zFront; z++) {
                boolean edge = x == x0 || x == x1 || z == zBack || z == zFront;
                if (edge) {
                    for (int y = y0; y <= y1; y++) {
                        Material m = (y == y0 || y == y1) ? Material.GOLD_BLOCK : Material.SMOOTH_QUARTZ;
                        canvas.placeForce(at(w, x, y, z), m);
                    }
                } else {
                    canvas.placeForce(at(w, x, y0 - 1, z), Material.POLISHED_DIORITE);
                    for (int y = y0; y <= y1; y++) canvas.place(at(w, x, y, z), Material.AIR);
                }
            }
        }
        // doorway (2 tall) in the front wall centre
        canvas.place(at(w, cx, y0, zFront), Material.AIR);
        canvas.place(at(w, cx, y0 + 1, zFront), Material.AIR);
        // windows either side of the door
        canvas.placeForce(at(w, cx - 1, y0 + 1, zFront), Material.GLASS_PANE);
        canvas.placeForce(at(w, cx + 1, y0 + 1, zFront), Material.GLASS_PANE);
        // flat roof + interior light
        for (int x = x0; x <= x1; x++)
            for (int z = zBack; z <= zFront; z++)
                canvas.placeForce(at(w, x, y1 + 1, z), Material.SMOOTH_QUARTZ_SLAB);
        canvas.place(at(w, cx, y1 - 1, cz - 3), Material.GLOWSTONE);
        // welcome step down onto the plaza runway
        BlockData step = Material.QUARTZ_STAIRS.createBlockData();
        if (step instanceof Stairs st) { st.setFacing(BlockFace.SOUTH); step = st; }
        canvas.place(at(w, cx, y0, cz), step);
    }

    /** Stone-brick roads east to the workshop and west to the board, with lamps. */
    private void roads(World w, int cx, int cy, int cz) {
        // East road: plaza edge (x=+7) out to the workshop (x=+13), 3 wide at z 5..7.
        for (int x = HALF_X; x <= 13; x++) roadStrip(w, cx + x, cy, cz);
        // West road to the board kiosk.
        for (int x = -13; x <= -HALF_X; x++) roadStrip(w, cx + x, cy, cz);
        // Road lamps.
        for (int x : new int[] { 9, 13, -9, -13 }) {
            int px = cx + x, pz = cz + 4;
            canvas.place(at(w, px, cy, pz), Material.OAK_FENCE);
            canvas.place(at(w, px, cy + 1, pz), Material.OAK_FENCE);
            canvas.place(at(w, px, cy + 2, pz), Material.LANTERN);
        }
    }

    private void roadStrip(World w, int x, int cy, int cz) {
        for (int z = 5; z <= 7; z++) {
            canvas.placeForce(at(w, x, cy - 1, cz + z), z == 6 ? Material.SMOOTH_QUARTZ : Material.STONE_BRICKS);
            canvas.place(at(w, x, cy, cz + z), Material.AIR);
        }
    }

    /** A 5×5 workshop (smithing/anvil) east of the plaza; agents work here. */
    private void workshop(World w, int cx, int cy, int cz) {
        int x0 = cx + 9, x1 = cx + 13, z0 = cz + 4, z1 = cz + 8;
        int y0 = cy, y1 = cy + 3;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                if (edge) {
                    for (int y = y0; y <= y1; y++) {
                        Material m = (y == y0 || y == y1) ? Material.DEEPSLATE_BRICKS : Material.COBBLESTONE;
                        canvas.placeForce(at(w, x, y, z), m);
                    }
                } else {
                    canvas.placeForce(at(w, x, y0 - 1, z), Material.STONE_BRICKS);
                    for (int y = y0; y <= y1; y++) canvas.place(at(w, x, y, z), Material.AIR);
                }
            }
        }
        // door facing the plaza (−X side, z=6)
        canvas.place(at(w, x0, y0, cz + 6), Material.AIR);
        canvas.place(at(w, x0, y0 + 1, cz + 6), Material.AIR);
        // workshop furniture + light
        canvas.place(at(w, cx + 11, y0, cz + 5), Material.ANVIL);
        canvas.place(at(w, cx + 12, y0, cz + 7), Material.SMITHING_TABLE);
        canvas.place(at(w, cx + 11, y0, cz + 7), Material.BLAST_FURNACE);
        canvas.place(at(w, cx + 11, y1 - 1, cz + 6), Material.GLOWSTONE);
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                canvas.placeForce(at(w, x, y1 + 1, z), Material.DEEPSLATE_TILE_SLAB);
    }

    /** A board kiosk west of the plaza — a wall of "listings" where requesters wait. */
    private void boardKiosk(World w, int cx, int cy, int cz) {
        int bx = cx - 11;
        // two pillars + a dark board wall behind the spot, glowstone-lit
        for (int y = cy; y <= cy + 3; y++) {
            canvas.placeForce(at(w, bx - 2, y, cz + 8), Material.STRIPPED_DARK_OAK_LOG);
            canvas.placeForce(at(w, bx + 2, y, cz + 8), Material.STRIPPED_DARK_OAK_LOG);
        }
        for (int x = bx - 2; x <= bx + 2; x++) {
            for (int y = cy + 1; y <= cy + 3; y++) {
                canvas.placeForce(at(w, x, y, cz + 9), Material.DARK_OAK_PLANKS);
            }
            canvas.placeForce(at(w, x, cy + 4, cz + 8), Material.DARK_OAK_SLAB); // little awning
        }
        // "postings" — item frames would need entities; use maps-less bookshelves + lanterns for readability
        canvas.placeForce(at(w, bx - 1, cy + 2, cz + 8), Material.BOOKSHELF);
        canvas.placeForce(at(w, bx + 1, cy + 2, cz + 8), Material.BOOKSHELF);
        canvas.place(at(w, bx, cy + 3, cz + 8), Material.LANTERN);
        // a small stone dais for the requester to stand on
        canvas.placeForce(at(w, bx, cy - 1, cz + 6), Material.CHISELED_STONE_BRICKS);
    }

    private void post(World w, int x, int y, int z) {
        canvas.place(at(w, x, y, z), Material.SMOOTH_QUARTZ_SLAB); // only over air/plants
    }

    private static Location at(World w, int x, int y, int z) {
        return new Location(w, x, y, z);
    }
}
