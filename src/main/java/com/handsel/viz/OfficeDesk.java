package com.handsel.viz;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One desk in an office: the villager sitting at it, the nameplate above it,
 * the status lamp beside it, and the written brief on its lectern.
 *
 * <p>The plate is the honest bit. A desk's role, price and MCP wiring come from
 * the roster the server owner configured; the credit score and the "working
 * now" lamp come from the live feeds, and when the desk's agent is not in them
 * the plate SAYS so rather than showing a zero that looks like a real number.
 * MAIN THREAD ONLY.
 */
public final class OfficeDesk {

    /** What the live feeds say this desk is doing. */
    public enum Status {
        /** Its agent is the worker on a job that is Accepted or Submitted. */
        WORKING,
        /** Its agent posted a bounty that is still Open — waiting on someone else. */
        WAITING,
        /** In the agent feed, with nothing live right now. */
        IDLE,
        /** No agent configured for this role, or that agent is not in the feed. */
        OFFLINE
    }

    private final Office office;
    private Office.Desk desk;
    private final Location spot;
    private final Villager villager;
    private final TextDisplay plate;
    private final BlockCanvas canvas;

    private Agent agent;
    private Status status = Status.OFFLINE;
    private String briefSignature = "";

    public OfficeDesk(BlockCanvas canvas, Office office, Office.Desk desk, Location spot) {
        this.canvas = canvas;
        this.office = office;
        this.desk = desk;
        this.spot = spot.clone();
        Location chair = OfficeBuilder.chair(spot);
        chair.setYaw(180f);          // spawned facing the door, i.e. whoever walks in
        World world = chair.getWorld();
        villager = (Villager) world.spawnEntity(chair, EntityType.VILLAGER);
        villager.setAI(false);
        villager.setInvulnerable(true);
        villager.setSilent(true);
        villager.setPersistent(true);
        villager.setGravity(false);
        villager.setProfession(professionFor(desk));
        villager.getPersistentDataContainer().set(AgentNpc.TAG, PersistentDataType.BYTE, (byte) 1);
        plate = world.spawn(chair.clone().add(0, 2.3, 0), TextDisplay.class, td -> {
            td.setBillboard(Display.Billboard.CENTER);
            td.setSeeThrough(true);
            td.setBackgroundColor(Color.fromARGB(150, 8, 12, 22));
            td.setPersistent(false);
        });
        writePlate();
        writeBrief();
    }

    public Office.Desk role() { return desk; }

    /** Refresh from the live feeds. {@code agent} is null when the desk's agent isn't in them. */
    public void update(Office.Desk fresh, Agent agent, Status status) {
        boolean started = status == Status.WORKING && this.status != Status.WORKING;
        this.desk = fresh;
        this.agent = agent;
        this.status = status;
        writePlate();
        writeBrief();
        setLamp();
        if (started) startedFx();
    }

    /** Idle animation — a working desk shows it, a few times a second at most. */
    public void tick(int tick) {
        if (status != Status.WORKING || villager.isDead()) return;
        World w = villager.getWorld();
        if (w == null) return;
        if ((tick % 16) == 0) {
            w.spawnParticle(Particle.ENCHANT, villager.getLocation().add(0, 2.0, 0), 4, .3, .3, .3, .4);
            villager.swingMainHand();
        }
    }

    public boolean matches(org.bukkit.entity.Entity e) {
        return e != null && villager.getUniqueId().equals(e.getUniqueId());
    }

    public boolean isDead() { return villager.isDead() || plate.isDead(); }

    public void remove() {
        villager.remove();
        plate.remove();
    }

    /** The role card printed when a player right-clicks the villager. */
    public List<String> cardLines() {
        List<String> out = new ArrayList<>();
        out.add("§6🏢 §f" + office.name() + " §8· §7" + (desk.stage() + 1) + "단계");
        out.add("  §b" + desk.title() + " §8(" + desk.role() + ")");
        if (desk.priceUsd() > 0) out.add("  §7이 단계 보수 §2$" + fmt(desk.priceUsd()));
        if (!desk.after().isEmpty()) out.add("  §7받는 입력: §f" + String.join(", ", desk.after()));
        if (!desk.reviews().isEmpty()) out.add("  §c검수 대상: §f" + desk.reviews() + " §8(REVISE면 되돌려보냄)");
        out.add(desk.wired()
                ? "  §d⚡ MCP: §f" + desk.wiring()
                : "  §8MCP 연결 없음 — 플랫폼 에이전트로 실행");
        if (desk.agent().isEmpty()) {
            out.add("  §8담당 에이전트 미지정 §7(config의 agent: 항목)");
        } else if (agent == null) {
            out.add("  §7담당 §f" + desk.agent() + " §8— 지금 에이전트 피드에 없음");
        } else {
            String col = AgentNpc.tierColor(agent.creditRating(), agent.creditScore());
            out.add("  §7담당 §f" + agent.name() + " §8· " + col + (long) agent.creditScore()
                    + " " + agent.creditRating());
            out.add("  §7처리한 일 §f" + agent.jobsDone() + " §8· 총수익 §2$" + fmt(agent.earnedUsd()));
        }
        out.add("  §8지금: §7" + statusText());
        if (!office.source().isEmpty()) out.add("  §8공용 자료: §7" + office.source());
        return out;
    }

    // --- rendering ----------------------------------------------------------

    private void writePlate() {
        StringBuilder sb = new StringBuilder();
        sb.append(statusColor()).append(marker()).append(" §f").append(desk.title());
        if (desk.priceUsd() > 0) sb.append(" §8· §2$").append(fmt(desk.priceUsd()));
        sb.append('\n');
        if (desk.agent().isEmpty()) {
            sb.append("§8담당 미지정");
        } else if (agent == null) {
            sb.append("§7").append(desk.agent()).append(" §8· 피드에 없음");
        } else {
            String col = AgentNpc.tierColor(agent.creditRating(), agent.creditScore());
            sb.append("§f").append(agent.name()).append(" §8· ")
              .append(col).append((long) agent.creditScore()).append(' ').append(agent.creditRating());
        }
        if (desk.wired()) sb.append("\n§d⚡ ").append(desk.mcpTool().isEmpty() ? desk.mcpServer() : desk.mcpTool());
        sb.append('\n').append(statusColor()).append(statusText());
        plate.setText(sb.toString());
    }

    /** The lamp beside the desk is the status you can read from across the room. */
    private void setLamp() {
        Location lamp = OfficeBuilder.statusLamp(spot);
        if (!canvas.owns(lamp)) return;   // someone cleared the floor — don't start placing again
        Material m = switch (status) {
            case WORKING -> Material.SEA_LANTERN;
            case WAITING -> Material.AMETHYST_BLOCK;
            case IDLE -> Material.SMOOTH_QUARTZ;
            case OFFLINE -> Material.POLISHED_BLACKSTONE;
        };
        if (lamp.getBlock().getType() != m) canvas.placeForce(lamp, m);
    }

    /**
     * The role brief on the desk's lectern. Rewritten only when something on it
     * changed — a lectern update is a block state write, and this runs every poll.
     */
    private void writeBrief() {
        String signature = desk.title() + '|' + desk.agent() + '|' + status + '|'
                + (agent == null ? "-" : (long) agent.creditScore() + "/" + agent.jobsDone());
        Block block = OfficeBuilder.deskLectern(spot).getBlock();
        if (block.getType() != Material.LECTERN) return;   // cleared or never built
        if (signature.equals(briefSignature)) return;
        if (block.getState() instanceof Lectern lectern) {
            lectern.getInventory().setItem(0, briefBook());
            lectern.update(true, false);
            briefSignature = signature;
        }
    }

    private ItemStack briefBook() {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle(trim(desk.title(), 30));
        meta.setAuthor("Handsel Office");

        StringBuilder p1 = new StringBuilder();
        p1.append("§0§l").append(trim(desk.title(), 40)).append("\n§8")
          .append(office.name()).append(" · ").append(desk.stage() + 1).append("단계\n\n");
        if (desk.priceUsd() > 0) p1.append("§2$").append(fmt(desk.priceUsd())).append("\n");
        p1.append("§0담당: ").append(desk.agent().isEmpty() ? "§8(미지정)" : desk.agent()).append("\n");
        p1.append("§0상태: ").append(statusText()).append("\n\n");
        if (agent != null) {
            p1.append("§8신용 ").append((long) agent.creditScore()).append(' ')
              .append(agent.creditRating()).append("\n§8처리 ").append(agent.jobsDone())
              .append("건 · $").append(fmt(agent.earnedUsd()));
        } else if (!desk.agent().isEmpty()) {
            p1.append("§8지금 에이전트 피드에\n올라와 있지 않습니다.");
        }

        StringBuilder p2 = new StringBuilder("§0§l배선\n\n");
        p2.append("§0입력: ").append(desk.after().isEmpty() ? "§8없음 (바로 시작)"
                : String.join(", ", desk.after())).append("\n\n");
        if (!desk.reviews().isEmpty()) {
            p2.append("§4검수: ").append(desk.reviews())
              .append("\n§8REVISE면 그 자리로\n일이 되돌아갑니다.\n\n");
        }
        p2.append("§0MCP: ").append(desk.wired() ? "\n§8" + trim(desk.wiring(), 90) : "§8연결 없음");

        List<String> pages = new ArrayList<>(List.of(p1.toString(), p2.toString()));
        if (!office.scope().isEmpty()) {
            pages.add("§0§l이 오피스의 일감\n\n§0" + trim(office.scope(), 480));
        }
        if (!office.source().isEmpty()) {
            pages.add("§0§l공용 자료\n\n§8" + trim(office.source(), 200));
        }
        meta.setPages(pages);
        book.setItemMeta(meta);
        return book;
    }

    private void startedFx() {
        World w = villager.getWorld();
        if (w == null) return;
        Location at = villager.getLocation().add(0, 1.4, 0);
        w.spawnParticle(Particle.HAPPY_VILLAGER, at, 14, .3, .4, .3, 0);
        w.playSound(at, Sound.BLOCK_NOTE_BLOCK_BIT, 0.6f, 1.5f);
    }

    private String statusText() {
        return switch (status) {
            case WORKING -> "작업 중";
            case WAITING -> "일감 열어두고 대기";
            case IDLE -> "대기 중";
            case OFFLINE -> desk.agent().isEmpty() ? "담당 미지정" : "피드에 없음";
        };
    }

    private String statusColor() {
        return switch (status) {
            case WORKING -> "§a";
            case WAITING -> "§e";
            case IDLE -> "§7";
            case OFFLINE -> "§8";
        };
    }

    /** Stage marker, so the pipeline reads left-to-right even from a distance. */
    private String marker() {
        int s = desk.stage() + 1;
        return s <= 9 ? "①②③④⑤⑥⑦⑧⑨".substring(s - 1, s) : "•";
    }

    /**
     * A look for the role. Cosmetic only — a villager profession is the one
     * cheap way to tell two desks apart at a glance, so it follows the words in
     * the role rather than anything the API says.
     */
    private static Villager.Profession professionFor(Office.Desk desk) {
        String s = (desk.role() + " " + desk.title()).toLowerCase(Locale.ROOT);
        if (s.contains("research") || s.contains("리서치") || s.contains("scout")) return Villager.Profession.CARTOGRAPHER;
        if (s.contains("check") || s.contains("red") || s.contains("audit") || s.contains("legal")) return Villager.Profession.CLERIC;
        if (s.contains("edit") || s.contains("copy") || s.contains("write") || s.contains("memo")) return Villager.Profession.LIBRARIAN;
        if (s.contains("quant") || s.contains("model") || s.contains("financial") || s.contains("chart")) return Villager.Profession.MASON;
        if (s.contains("architect") || s.contains("plan") || s.contains("partner") || s.contains("head")) return Villager.Profession.TOOLSMITH;
        if (s.contains("distribut") || s.contains("sales") || s.contains("commercial")) return Villager.Profession.FARMER;
        return Villager.Profession.LIBRARIAN;
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.2f", v);
    }

    private static String trim(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
