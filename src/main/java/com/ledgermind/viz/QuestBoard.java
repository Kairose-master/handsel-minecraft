package com.ledgermind.viz;

import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.entity.Firework;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * The open-jobs feed as things you can touch: a LECTERN holding a written
 * book — right-click it and Minecraft's own book UI opens with every open
 * bounty, its reward, and who posted it. No custom GUI, no hologram to squint
 * at; the game already has a way to read a list of quests.
 *
 * <p>Also owns the celebration: a filled job launches a firework, rings the
 * village bell note, and pulses a REDSTONE BLOCK for two seconds so players
 * can wire their own contraptions (cannons, lamps, doors) to the real economy.
 * MAIN THREAD ONLY.
 */
public final class QuestBoard {

    private static final int JOBS_PER_PAGE = 4;

    private final Plugin plugin;
    private final BlockCanvas canvas;
    private final Location anchor;
    private String lastRendered = "";

    public QuestBoard(Plugin plugin, BlockCanvas canvas, Location anchor) {
        this.plugin = plugin;
        this.canvas = canvas;
        this.anchor = anchor.clone();
    }

    /** Refresh the book on the lectern when the feed actually changed. */
    public void render(List<Job> jobs, String vaultLine) {
        World world = anchor.getWorld();
        if (world == null) return;

        String signature = jobs.stream().map(Job::id).reduce("", (a, b) -> a + "," + b);
        Block block = lecternLoc().getBlock();
        boolean needsBlock = block.getType() != Material.LECTERN;
        if (!needsBlock && signature.equals(lastRendered)) return;

        if (needsBlock) {
            canvas.place(lecternLoc().clone().add(0, -1, 0), Material.POLISHED_ANDESITE); // pedestal
            canvas.place(lecternLoc(), Material.LECTERN);
            block = lecternLoc().getBlock();
        }
        if (block.getState() instanceof Lectern lectern) {
            lectern.getInventory().setItem(0, writeBook(jobs, vaultLine));
            lectern.update(true, false);
        }
        lastRendered = signature;
    }

    private ItemStack writeBook(List<Job> jobs, String vaultLine) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle("Ledgermind Jobs");
        meta.setAuthor("Ledgermind");

        List<String> pages = new ArrayList<>();
        StringBuilder cover = new StringBuilder("§0§lLEDGERMIND\n§8열린 일감 " + jobs.size() + "건\n\n");
        if (vaultLine != null) cover.append("§8").append(vaultLine).append("\n\n");
        cover.append("§8오른쪽 페이지에서\n각 일감의 상세를\n확인하세요.\n\n§8(테스트넷)");
        pages.add(cover.toString());

        StringBuilder page = new StringBuilder();
        int onPage = 0;
        for (Job j : jobs) {
            page.append("§1#").append(j.id()).append("  §2$").append(fmt(j.rewardUsd())).append("\n")
                .append("§0").append(trim(j.title(), 60)).append("\n");
            if (j.requesterName() != null && !j.requesterName().isBlank()) {
                page.append("§8by ").append(trim(j.requesterName(), 18)).append("\n");
            }
            page.append("§8").append(j.verification()).append("\n\n");
            if (++onPage == JOBS_PER_PAGE) {
                pages.add(page.toString());
                page = new StringBuilder();
                onPage = 0;
            }
        }
        if (onPage > 0) pages.add(page.toString());
        if (pages.size() == 1) pages.add("§8지금은 열린 일감이\n없습니다.");

        meta.setPages(pages);
        book.setItemMeta(meta);
        return book;
    }

    /**
     * A job just got filled and paid. Firework + bell + a two-second redstone
     * pulse other players' builds can react to.
     */
    public void celebrate(Job job) {
        World world = anchor.getWorld();
        if (world == null) return;

        Location at = anchor.clone().add(0.5, 1.2, 0.5);
        world.playSound(at, Sound.BLOCK_BELL_USE, 1f, 1f);

        world.spawn(at, Firework.class, fw -> {
            FireworkMeta meta = fw.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .with(FireworkEffect.Type.BALL_LARGE)
                    .withColor(Color.fromRGB(255, 200, 40), Color.fromRGB(80, 220, 120))
                    .withFade(Color.WHITE)
                    .trail(true)
                    .build());
            meta.setPower(1);
            fw.setFireworkMeta(meta);
        });

        Location signal = redstoneLoc();
        if (canvas.place(signal, Material.REDSTONE_BLOCK)) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> canvas.restore(signal), 40L);
        }
    }

    public void clear() {
        lastRendered = "";
        // blocks are restored through the shared canvas
    }

    private Location lecternLoc() { return anchor.clone().add(0, 0, 1); }

    /** Two blocks aside, so a player's redstone can tap it without touching the board. */
    private Location redstoneLoc() { return anchor.clone().add(2, 0, 1); }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.2f", v);
    }

    private static String trim(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
