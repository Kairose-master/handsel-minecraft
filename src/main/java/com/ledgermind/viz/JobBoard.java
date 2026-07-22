package com.ledgermind.viz;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the live job board with TextDisplay entities.
 * MAIN THREAD ONLY — every method here touches world/entity state.
 */
public final class JobBoard {
    // legacy colour codes (§ == the section sign); TextDisplay#setText renders them on Paper
    private static final String Y = "§e";   // yellow
    private static final String G = "§a";   // green
    private static final String B = "§b";   // aqua
    private static final String GRY = "§8"; // dark grey
    private static final String W = "§f";   // white
    private static final String AMB = "§6"; // gold

    private static final double ROW_SPACING = 0.35;

    private final Location anchor;
    private TextDisplay header;
    private TextDisplay vaultLine;
    private TextDisplay emptyLine;
    private final Map<String, TextDisplay> rows = new HashMap<>();
    private Map<String, Job> lastJobs = new LinkedHashMap<>();

    public JobBoard(Location anchor) {
        this.anchor = anchor.clone();
    }

    public Location anchor() {
        return anchor.clone();
    }

    /**
     * Diff against the previous poll, then render.
     *
     * @return the jobs that left the Open feed since the last render (claimed/paid),
     *         for the caller to broadcast.
     */
    public List<Job> render(List<Job> jobs, String vaultText) {
        World world = anchor.getWorld();
        if (world == null) return List.of();

        if (header == null || header.isDead()) {
            header = spawn(anchor.clone().add(0, 0.4, 0),
                    AMB + "⛏ " + W + "LEDGERMIND — " + GRY + "live jobs (testnet)");
        }

        Map<String, Job> now = new LinkedHashMap<>();
        for (Job j : jobs) now.put(j.id(), j);

        boolean firstRender = lastJobs.isEmpty() && rows.isEmpty();

        // Newly appeared jobs -> blue "ding" (skip the very first render, everything is new then)
        if (!firstRender) {
            for (Job j : jobs) {
                if (!lastJobs.containsKey(j.id())) newJobFx(world);
            }
        }

        // Jobs that left the Open feed -> claimed/paid -> green "cha-ching"
        List<Job> filled = new ArrayList<>();
        for (Map.Entry<String, Job> prev : lastJobs.entrySet()) {
            if (!now.containsKey(prev.getKey())) {
                filled.add(prev.getValue());
                filledFx(world);
            }
        }

        // (Re)write + reposition one row per job
        int i = 0;
        for (Job j : jobs) {
            Location loc = anchor.clone().subtract(0, ROW_SPACING * (++i), 0);
            String col = "manual_review".equals(j.verification()) ? AMB : G;
            String text = Y + "#" + j.id() + "  " + col + "$" + fmt(j.rewardUsd())
                    + GRY + "  " + W + trim(j.title(), 34);
            TextDisplay td = rows.get(j.id());
            if (td == null || td.isDead()) {
                rows.put(j.id(), spawn(loc, text));
            } else {
                td.teleport(loc);
                td.setText(text);
            }
        }

        // Drop rows for jobs no longer in the feed
        rows.entrySet().removeIf(en -> {
            if (!now.containsKey(en.getKey())) {
                en.getValue().remove();
                return true;
            }
            return false;
        });

        // Honest empty state rather than a blank board
        Location emptyLoc = anchor.clone().subtract(0, ROW_SPACING, 0);
        if (jobs.isEmpty()) {
            if (emptyLine == null || emptyLine.isDead()) {
                emptyLine = spawn(emptyLoc, GRY + "no open jobs right now");
            } else {
                emptyLine.teleport(emptyLoc);
            }
        } else if (emptyLine != null) {
            emptyLine.remove();
            emptyLine = null;
        }

        // MiniVault gauge under the list
        Location vloc = anchor.clone().subtract(0, ROW_SPACING * (Math.max(jobs.size(), 1) + 1) + 0.15, 0);
        if (vaultText != null) {
            if (vaultLine == null || vaultLine.isDead()) {
                vaultLine = spawn(vloc, B + vaultText);
            } else {
                vaultLine.teleport(vloc);
                vaultLine.setText(B + vaultText);
            }
        } else if (vaultLine != null) {
            vaultLine.remove();
            vaultLine = null;
        }

        lastJobs = now;
        return filled;
    }

    /** Remove every entity this board owns. */
    public void clear() {
        if (header != null) { header.remove(); header = null; }
        if (vaultLine != null) { vaultLine.remove(); vaultLine = null; }
        if (emptyLine != null) { emptyLine.remove(); emptyLine = null; }
        rows.values().forEach(TextDisplay::remove);
        rows.clear();
        lastJobs = new LinkedHashMap<>();
    }

    private TextDisplay spawn(Location loc, String legacyText) {
        World world = loc.getWorld();
        return world.spawn(loc, TextDisplay.class, td -> {
            td.setText(legacyText);
            td.setBillboard(Display.Billboard.CENTER);
            td.setSeeThrough(true);
            td.setViewRange(1.0f);
            td.setBackgroundColor(Color.fromARGB(140, 8, 12, 22));
            td.setPersistent(false); // don't survive restart; the plugin re-renders
        });
    }

    /** A job was claimed/paid: green sparkle + cha-ching. */
    private void filledFx(World world) {
        Location loc = anchor.clone();
        world.spawnParticle(Particle.HAPPY_VILLAGER, loc, 24, 0.5, 0.5, 0.5, 0);
        world.playSound(loc, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.4f);
    }

    /** A new bounty appeared: blue enchant swirl + pling. */
    private void newJobFx(World world) {
        Location loc = anchor.clone();
        world.spawnParticle(Particle.ENCHANT, loc, 30, 0.6, 0.6, 0.6, 0.4);
        world.playSound(loc, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.6f);
    }

    private static String fmt(double v) {
        return v == Math.floor(v) && !Double.isInfinite(v)
                ? String.valueOf((long) v)
                : String.format("%.2f", v);
    }

    private static String trim(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
