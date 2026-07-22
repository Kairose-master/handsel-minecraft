package com.ledgermind.viz;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The human lane: a player takes a real Ledgermind job, writes the answer in a
 * book, and submits it — graded by the same independent grader that grades the
 * AI agents, with no marker saying a person did it.
 *
 * <p>This is not a simulation. The task text comes from the platform's own
 * worker queue and the answer goes back through {@code /api/runtime/callback},
 * exactly as the model path does — the protocol never had an opinion on how
 * the output was produced (docs/agent-integration.md §2), so a person is a
 * legitimate worker implementation.
 *
 * <p>Honesty note: work submitted here is credited to the CONFIGURED AGENT,
 * because that is whose queue the task came from and whose secret signs the
 * callback. A player is doing the work on that agent's behalf, the same way a
 * local model would — that's what the chat message says, rather than implying
 * the player has an account of their own.
 */
public final class PlayerLane {

    private final Plugin plugin;

    private Miner.Task offered;
    private long offeredAt;
    private UUID claimedBy;
    private String claimedName;

    public PlayerLane(Plugin plugin) { this.plugin = plugin; }

    public boolean hasOffer() { return offered != null; }
    public Miner.Task offered() { return offered; }
    public UUID claimedBy() { return claimedBy; }
    public String claimedName() { return claimedName; }

    public int secondsWaiting() {
        return offered == null ? 0 : (int) ((System.currentTimeMillis() - offeredAt) / 1000);
    }

    /** A task arrived from the platform — put it on the table for players. */
    public void offer(Miner.Task task, int timeoutSeconds) {
        this.offered = task;
        this.offeredAt = System.currentTimeMillis();
        this.claimedBy = null;
        this.claimedName = null;

        plugin.getServer().broadcast(Component.text(
                "⛏ 새 일감이 도착했습니다 — /lm take 로 받으세요  (" + timeoutSeconds + "초 안에)",
                NamedTextColor.GOLD));
        plugin.getServer().broadcast(Component.text("   " + task.firstLine(), NamedTextColor.GRAY));
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.2f);
        }
    }

    /** A player claims it: they get the task as a readable book plus a blank one to answer in. */
    public boolean claim(Player player) {
        if (offered == null || claimedBy != null) return false;
        claimedBy = player.getUniqueId();
        claimedName = player.getName();

        player.getInventory().addItem(taskBook(offered));
        player.getInventory().addItem(new ItemStack(Material.WRITABLE_BOOK));
        player.sendMessage("§a일감을 받았습니다. §7책과 깃펜에 답을 쓰고 §f서명§7한 뒤 §f/lm submit§7 하세요.");
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
        return true;
    }

    /** Pull the answer out of the signed book the player is holding. */
    public String readAnswer(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() != Material.WRITTEN_BOOK) return null;
        if (!(held.getItemMeta() instanceof BookMeta meta)) return null;
        StringBuilder sb = new StringBuilder();
        for (String page : meta.getPages()) {
            sb.append(page).append('\n');
        }
        String text = sb.toString().trim();
        return text.isEmpty() ? null : text;
    }

    public void clear() {
        offered = null;
        claimedBy = null;
        claimedName = null;
    }

    /** The task, as something you can actually read in-game. */
    private ItemStack taskBook(Miner.Task task) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle("Job " + shortId(task.id()));
        meta.setAuthor("Ledgermind");

        List<String> pages = new ArrayList<>();
        pages.add("§0§l일감\n\n§0" + wrap(task.prompt(), 240)
                + "\n\n§8답을 '책과 깃펜'에\n쓰고 서명한 뒤\n/lm submit");
        String rest = task.prompt().length() > 240 ? task.prompt().substring(240) : "";
        while (!rest.isEmpty()) {
            String chunk = rest.length() > 250 ? rest.substring(0, 250) : rest;
            pages.add("§0" + chunk);
            rest = rest.length() > 250 ? rest.substring(250) : "";
        }
        meta.setPages(pages);
        book.setItemMeta(meta);
        return book;
    }

    private static String wrap(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String shortId(String id) {
        return id == null ? "?" : (id.length() > 12 ? id.substring(0, 12) : id);
    }
}
