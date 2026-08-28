package com.handsel.viz;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One placed office: the room, its desks, and the live status that runs through
 * them. Built once by {@code /lm office place}, refreshed on every poll.
 *
 * <p>The office STRUCTURE never changes between polls — it is the config the
 * owner wrote. What changes is who is at their desk doing what, and that comes
 * from the same two feeds the rest of the plugin runs on: the agent leaderboard
 * for each desk's credit standing, and the all-status task feed for whether its
 * agent is working, waiting on a bounty it posted, or neither. A desk whose
 * agent is in neither feed says "피드에 없음" and stays dark; it never shows a
 * zero that could be read as a real score. MAIN THREAD ONLY.
 */
public final class OfficeFloor {

    private final BlockCanvas canvas;
    private final Office office;
    private final Location anchor;
    private final Map<String, OfficeDesk> desks = new LinkedHashMap<>();
    private TextDisplay sign;
    /** Last known live statuses — kept when a poll brings no job feed at all. */
    private Map<String, OfficeDesk.Status> lastLive = Map.of();
    private String signSignature = "";
    private String receptionSignature = "";
    private int working;

    public OfficeFloor(BlockCanvas canvas, Office office, Location anchor) {
        this.canvas = canvas;
        this.office = office;
        this.anchor = anchor.clone();
    }

    public Office office() { return office; }
    public String id() { return office.id(); }
    public Location anchor() { return anchor.clone(); }
    public int deskCount() { return desks.size(); }
    public int workingCount() { return working; }

    /** Put up the room. Blocks only — the desks appear on the first {@link #render}. */
    public void build() {
        if (anchor.getWorld() == null) return;
        new OfficeBuilder(canvas).build(anchor, office);
    }

    /**
     * Refresh every desk from the live feeds.
     *
     * @param agents the agent feed (any scope); desks match theirs by NAME
     * @param jobs   tasks across statuses, for who is working and who is waiting
     */
    public void render(List<Agent> agents, List<Job> jobs) {
        if (anchor.getWorld() == null) return;

        Map<String, Agent> byName = new LinkedHashMap<>();
        for (Agent a : agents) byName.put(a.name().toLowerCase(Locale.ROOT), a);

        // An empty job feed means "the poll brought nothing", which is not the
        // same as "nobody is working" — keep the last known statuses instead of
        // emptying every desk on one failed fetch (the village does the same).
        Map<String, OfficeDesk.Status> live = jobs.isEmpty() ? lastLive : statuses(jobs);
        lastLive = live;

        working = 0;
        for (int stage = 0; stage < office.stages(); stage++) {
            List<Office.Desk> row = office.atStage(stage);
            for (int i = 0; i < row.size(); i++) {
                Office.Desk role = row.get(i);
                Location spot = OfficeBuilder.deskSpot(anchor, stage, i, row.size());
                Agent agent = role.agent().isEmpty() ? null
                        : byName.get(role.agent().toLowerCase(Locale.ROOT));
                OfficeDesk.Status status = agent == null ? OfficeDesk.Status.OFFLINE
                        : live.getOrDefault(role.agent().toLowerCase(Locale.ROOT), OfficeDesk.Status.IDLE);
                if (status == OfficeDesk.Status.WORKING) working++;

                OfficeDesk desk = desks.get(role.role());
                if (desk == null || desk.isDead()) {
                    // A chunk unload can cull the villager; rebuilding it here is
                    // what keeps a floor that has been away from a player intact.
                    if (desk != null) desk.remove();
                    desk = new OfficeDesk(canvas, office, role, spot);
                    desks.put(role.role(), desk);
                }
                desk.update(role, agent, status);
            }
        }
        renderSign();
        renderReception();
    }

    /**
     * Which of this office's agents are working, and which are waiting on a
     * bounty they posted. Same rule the village routes its NPCs by
     * (AgentVillage#assignRoles), so a desk and its villager in the town agree.
     */
    private Map<String, OfficeDesk.Status> statuses(List<Job> jobs) {
        Map<String, OfficeDesk.Status> out = new LinkedHashMap<>();
        for (Job j : jobs) {
            String status = j.status() == null ? "" : j.status();
            if (!j.workerName().isBlank()
                    && (status.equalsIgnoreCase("Accepted") || status.equalsIgnoreCase("Submitted"))) {
                out.put(j.workerName().toLowerCase(Locale.ROOT), OfficeDesk.Status.WORKING);
            } else if (!j.requesterName().isBlank() && status.equalsIgnoreCase("Open")) {
                out.putIfAbsent(j.requesterName().toLowerCase(Locale.ROOT), OfficeDesk.Status.WAITING);
            }
        }
        return out;
    }

    /** One movement/FX step for the floor. Called on the fast village timer. */
    public void tick(int tick) {
        for (OfficeDesk d : desks.values()) d.tick(tick);
    }

    /** The sign over the door: what this office is, and how much of it is busy. */
    private void renderSign() {
        Location loc = anchor.clone().add(0.5, 3.2, -1);
        String text = "§6🏢 §f" + office.name()
                + (office.budgetUsd() > 0 ? " §8· §2$" + fmt(office.budgetUsd()) : "")
                + "\n§7" + trim(office.tagline().isEmpty()
                        ? office.desks().size() + "명 · " + office.stages() + "단계 파이프라인"
                        : office.tagline(), 60)
                + "\n" + (working > 0 ? "§a작업 중 " + working + "명" : "§8지금 작업 중인 자리 없음");
        if (text.equals(signSignature) && sign != null && !sign.isDead()) return;
        if (sign == null || sign.isDead()) {
            World world = loc.getWorld();
            if (world == null) return;
            sign = world.spawn(loc, TextDisplay.class, td -> {
                td.setText(text);
                td.setBillboard(Display.Billboard.CENTER);
                td.setSeeThrough(true);
                td.setBackgroundColor(Color.fromARGB(170, 8, 12, 22));
                td.setPersistent(false);
            });
        } else {
            sign.teleport(loc);
            sign.setText(text);
        }
        signSignature = text;
    }

    /** The reception lectern: the whole office in one book, priced role by role. */
    private void renderReception() {
        Block block = OfficeBuilder.receptionLectern(anchor).getBlock();
        if (block.getType() != Material.LECTERN) return;
        String signature = office.id() + '|' + working + '|' + desks.size();
        if (signature.equals(receptionSignature)) return;
        if (block.getState() instanceof Lectern lectern) {
            lectern.getInventory().setItem(0, receptionBook());
            lectern.update(true, false);
            receptionSignature = signature;
        }
    }

    private ItemStack receptionBook() {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle(trim(office.name(), 30));
        meta.setAuthor("Handsel Office");

        List<String> pages = new ArrayList<>();
        StringBuilder cover = new StringBuilder("§0§l" + trim(office.name(), 40) + "\n\n");
        if (!office.tagline().isEmpty()) cover.append("§8").append(trim(office.tagline(), 220)).append("\n\n");
        cover.append("§0").append(office.desks().size()).append("명 · ")
             .append(office.stages()).append("단계\n");
        if (office.budgetUsd() > 0) cover.append("§2예산 $").append(fmt(office.budgetUsd())).append("\n");
        cover.append("§a작업 중 ").append(working).append("명");
        pages.add(cover.toString());

        StringBuilder roles = new StringBuilder("§0§l자리\n\n");
        int on = 0;
        for (Office.Desk d : office.desks()) {
            roles.append("§8").append(d.stage() + 1).append(". §0").append(trim(d.title(), 34)).append('\n');
            if (d.priceUsd() > 0) roles.append("   §2$").append(fmt(d.priceUsd()));
            if (!d.agent().isEmpty()) roles.append(" §8").append(trim(d.agent(), 16));
            roles.append("\n");
            if (++on % 5 == 0) { pages.add(roles.toString()); roles = new StringBuilder(); }
        }
        if (roles.length() > 0) pages.add(roles.toString());
        if (!office.scope().isEmpty()) pages.add("§0§l일감\n\n§0" + trim(office.scope(), 480));
        if (!office.source().isEmpty()) pages.add("§0§l공용 자료\n\n§8" + trim(office.source(), 200));

        meta.setPages(pages);
        book.setItemMeta(meta);
        return book;
    }

    /** The desk a clicked villager belongs to, or null. */
    public OfficeDesk deskForEntity(org.bukkit.entity.Entity e) {
        for (OfficeDesk d : desks.values()) if (d.matches(e)) return d;
        return null;
    }

    /** A one-line summary for /lm office list and /lm status. */
    public String summaryLine() {
        return "§f" + office.name() + " §8(" + office.id() + ") §7"
                + office.desks().size() + "자리 · " + office.stages() + "단계"
                + (working > 0 ? " §a작업 중 " + working : " §8유휴")
                + " §8@ " + (anchor.getWorld() == null ? "?" : anchor.getWorld().getName())
                + " " + anchor.getBlockX() + "," + anchor.getBlockY() + "," + anchor.getBlockZ();
    }

    /** Remove every entity this floor owns; its blocks go back through the canvas. */
    public void clear() {
        desks.values().forEach(OfficeDesk::remove);
        desks.clear();
        if (sign != null) { sign.remove(); sign = null; }
        signSignature = "";
        receptionSignature = "";
        lastLive = Map.of();
        working = 0;
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.2f", v);
    }

    private static String trim(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
