package com.handsel.viz;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.List;

/**
 * Builds the office floor: a glass-walled room, a reception desk at the door,
 * one workstation per role laid out front-to-back in pipeline order, and
 * CABLES in the floor running from every upstream desk to the desk it feeds —
 * so the wiring an office roster describes in text is a thing you can follow
 * with your feet. Review edges (a REVISE that goes back to the desk it came
 * from) run in red, the other way down the room.
 *
 * <p>Everything goes through the shared {@link BlockCanvas}, so the whole floor
 * is remembered and put back by {@code /lm clear} and on shutdown. Furniture
 * uses {@link BlockCanvas#place} (air and plants only, never a player's build);
 * only the floor tiles and the cables drawn into them are forced, the same
 * deliberate footprint the plaza and the roads take. MAIN THREAD ONLY.
 */
public final class OfficeBuilder {

    /** Blocks between two desks in the same stage, and between two stages. */
    static final int DESK_PITCH_X = 5;
    static final int STAGE_PITCH_Z = 6;
    /** The first row of desks sits this far in from the door. */
    static final int FIRST_STAGE_Z = 5;
    private static final int SIDE_PADDING = 4;

    private final BlockCanvas canvas;

    public OfficeBuilder(BlockCanvas canvas) { this.canvas = canvas; }

    // --- the layout, shared with OfficeFloor so desks and cables agree -------

    /** Where desk {@code idx} of {@code inStage} desks at {@code stage} stands (block coords). */
    public static Location deskSpot(Location c, int stage, int idx, int inStage) {
        double offset = (idx - (inStage - 1) / 2.0) * DESK_PITCH_X;
        return c.clone().add(Math.round(offset), 0, FIRST_STAGE_Z + (long) stage * STAGE_PITCH_Z);
    }

    /** The desk spot for one role of an office, or null when it has no desk. */
    public static Location deskSpot(Location c, Office office, Office.Desk desk) {
        List<Office.Desk> row = office.atStage(desk.stage());
        int idx = row.indexOf(desk);
        return idx < 0 ? null : deskSpot(c, desk.stage(), idx, row.size());
    }

    /** Where the visitor stands when they walk in — reception, just inside the door. */
    public static Location receptionSpot(Location c) { return c.clone().add(0, 0, 2); }

    private static int halfWidth(Office office) {
        return Math.max(6, (office.width() * DESK_PITCH_X) / 2 + SIDE_PADDING);
    }

    private static int backZ(Office office) {
        return FIRST_STAGE_Z + (office.stages() - 1) * STAGE_PITCH_Z + 4;
    }

    // --- the build ----------------------------------------------------------

    public void build(Location c, Office office) {
        World w = c.getWorld();
        if (w == null) return;
        int cx = c.getBlockX(), cy = c.getBlockY(), cz = c.getBlockZ();
        int half = halfWidth(office);
        int zBack = backZ(office);

        floor(w, cx, cy, cz, half, zBack);
        cables(w, c, office);
        walls(w, cx, cy, cz, half, zBack);
        reception(w, cx, cy, cz);
        for (int s = 0; s < office.stages(); s++) {
            List<Office.Desk> row = office.atStage(s);
            for (int i = 0; i < row.size(); i++) {
                workstation(w, deskSpot(c, s, i, row.size()), cy, row.get(i));
            }
        }
    }

    /** Tiled floor one block below standing level, with the walkway kept clear. */
    private void floor(World w, int cx, int cy, int cz, int half, int zBack) {
        for (int x = -half; x <= half; x++) {
            for (int z = -1; z <= zBack; z++) {
                Material m = ((x + z) & 1) == 0 ? Material.POLISHED_ANDESITE : Material.SMOOTH_STONE;
                canvas.placeForce(at(w, cx + x, cy - 1, cz + z), m);
                canvas.place(at(w, cx + x, cy, cz + z), Material.AIR);      // clear grass, keep builds
                canvas.place(at(w, cx + x, cy + 1, cz + z), Material.AIR);
            }
        }
    }

    /**
     * The pipeline, drawn into the floor: an L-shaped run from each upstream
     * desk's back edge to the front edge of the desk it feeds. Light blue is
     * work moving forward; red is a review going back.
     */
    private void cables(World w, Location c, Office office) {
        for (Office.Desk d : office.desks()) {
            Location to = deskSpot(c, office, d);
            if (to == null) continue;
            for (String up : d.after()) {
                Office.Desk from = office.desk(up);
                Location fromSpot = from == null ? null : deskSpot(c, office, from);
                if (fromSpot == null) continue;
                cable(w, fromSpot.clone().add(0, 0, 2), to.clone().add(0, 0, -2),
                        Material.LIGHT_BLUE_CONCRETE);
            }
            if (!d.reviews().isEmpty()) {
                Office.Desk subject = office.desk(d.reviews());
                Location back = subject == null ? null : deskSpot(c, office, subject);
                if (back != null) {
                    // the REVISE lane runs beside the forward cable, not on top of it
                    cable(w, to.clone().add(2, 0, -2), back.clone().add(2, 0, 2), Material.RED_CONCRETE);
                }
            }
        }
    }

    /** One L-shaped run of cable at floor level: along Z, then across X. */
    private void cable(World w, Location from, Location to, Material m) {
        int y = from.getBlockY() - 1;
        int x0 = from.getBlockX(), z0 = from.getBlockZ();
        int x1 = to.getBlockX(), z1 = to.getBlockZ();
        int sz = Integer.signum(z1 - z0);
        int z = z0;
        while (sz != 0 && z != z1) { canvas.placeForce(at(w, x0, y, z), m); z += sz; }
        int sx = Integer.signum(x1 - x0);
        int x = x0;
        while (sx != 0 && x != x1) { canvas.placeForce(at(w, x, y, z1), m); x += sx; }
        canvas.placeForce(at(w, x1, y, z1), m);
    }

    /** Low quartz base, glass above it, pillars and lanterns at the corners. */
    private void walls(World w, int cx, int cy, int cz, int half, int zBack) {
        for (int x = -half; x <= half; x++) {
            wallColumn(w, cx + x, cy, cz + zBack);
            if (Math.abs(x) > 1) wallColumn(w, cx + x, cy, cz - 1);   // 3-wide doorway at the front
        }
        for (int z = -1; z <= zBack; z++) {
            wallColumn(w, cx - half, cy, cz + z);
            wallColumn(w, cx + half, cy, cz + z);
        }
        int[][] corners = { {-half, -1}, {half, -1}, {-half, zBack}, {half, zBack} };
        for (int[] corner : corners) {
            int x = cx + corner[0], z = cz + corner[1];
            for (int y = cy; y <= cy + 3; y++) canvas.placeForce(at(w, x, y, z), Material.QUARTZ_PILLAR);
            canvas.placeForce(at(w, x, cy + 4, z), Material.SEA_LANTERN);
        }
        // ceiling lights down both sides, every few blocks — enough to read the room at night
        for (int z = 2; z < zBack; z += 5) {
            canvas.place(at(w, cx - half + 1, cy + 4, cz + z), Material.SEA_LANTERN);
            canvas.place(at(w, cx + half - 1, cy + 4, cz + z), Material.SEA_LANTERN);
        }
    }

    private void wallColumn(World w, int x, int cy, int z) {
        canvas.placeForce(at(w, x, cy, z), Material.SMOOTH_QUARTZ);
        canvas.place(at(w, x, cy + 1, z), Material.GLASS_PANE);
        canvas.place(at(w, x, cy + 2, z), Material.GLASS_PANE);
        canvas.place(at(w, x, cy + 3, z), Material.SMOOTH_QUARTZ_SLAB);
    }

    /** The counter you meet on the way in; its lectern holds the office brief. */
    private void reception(World w, int cx, int cy, int cz) {
        Location spot = receptionSpot(new Location(w, cx, cy, cz));
        int rx = spot.getBlockX(), rz = spot.getBlockZ();
        for (int dx = -2; dx <= 2; dx++) {
            canvas.place(at(w, rx + dx, cy, rz), Material.SMOOTH_QUARTZ);
            canvas.place(at(w, rx + dx, cy + 1, rz), Material.SMOOTH_QUARTZ_SLAB);
        }
        canvas.place(receptionLectern(new Location(w, cx, cy, cz)), Material.LECTERN);
        canvas.place(at(w, rx - 2, cy + 2, rz), Material.LANTERN);
        canvas.place(at(w, rx + 2, cy + 2, rz), Material.LANTERN);
    }

    /** The lectern on the reception counter — right-click it to read the whole office. */
    public static Location receptionLectern(Location c) {
        return receptionSpot(c).add(0, 1, 0);
    }

    /** One desk: counter, a lectern with the role brief, a status lamp, a back partition. */
    private void workstation(World w, Location spot, int cy, Office.Desk desk) {
        int dx = spot.getBlockX(), dz = spot.getBlockZ();
        for (int x = dx - 1; x <= dx + 1; x++) {
            canvas.place(at(w, x, cy, dz), Material.SMOOTH_QUARTZ);
        }
        canvas.place(deskLectern(spot), Material.LECTERN);
        canvas.place(statusLamp(spot), Material.SMOOTH_QUARTZ);   // OfficeDesk swaps this per status
        // a low partition behind the chair, so each role reads as its own desk
        for (int x = dx - 1; x <= dx + 1; x++) {
            canvas.place(at(w, x, cy, dz + 2), Material.LIGHT_GRAY_STAINED_GLASS_PANE);
            canvas.place(at(w, x, cy + 1, dz + 2), Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        }
        // a wired role gets a little server rack beside the desk
        if (desk.wired()) {
            canvas.place(at(w, dx + 2, cy, dz), Material.LODESTONE);
            canvas.place(at(w, dx + 2, cy + 1, dz), Material.OBSERVER);
        }
    }

    /** The lectern holding this desk's role brief. */
    public static Location deskLectern(Location spot) { return spot.clone().add(-1, 1, 0); }

    /** The block whose material shows what this desk is doing right now. */
    public static Location statusLamp(Location spot) { return spot.clone().add(1, 1, 0); }

    /** Where the agent at this desk stands. */
    public static Location chair(Location spot) { return spot.clone().add(0.5, 0, 1.5); }

    private static Location at(World w, int x, int y, int z) {
        return new Location(w, x, y, z);
    }
}
