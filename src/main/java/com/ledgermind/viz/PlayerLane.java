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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The human lane — a player is a real manual worker on the Ledgermind labor
 * market, not a stand-in for an AI. Two ways in, both ending in the SAME
 * submission through {@code /api/runtime/callback} that grades every worker:
 *
 * <ol>
 *   <li><b>Direct (primary):</b> {@code /lm jobs} to browse the open market,
 *       {@code /lm take <id>} to claim a specific job for yourself. The claim
 *       does the real on-chain accept ({@code /api/worker/claim}); the job is
 *       now this agent's, worked by you.</li>
 *   <li><b>Offered (optional):</b> when {@code mining.human-mode} is on, a task
 *       already dispatched to the agent's queue is offered to whoever grabs it
 *       first with {@code /lm take}.</li>
 * </ol>
 *
 * <p>Each player holds their own claimed job, so several friends can each work
 * a different bounty at once. Work is credited to the CONFIGURED AGENT (its
 * secret signs the callback and its wallet is paid) — the player does the work
 * on that agent's behalf, which is what the chat says.
 *
 * <p>MAIN THREAD ONLY for the inventory/chat calls; the HTTP claim/submit runs
 * off-thread in the plugin and hands results back here.
 */
public final class PlayerLane {

    /** One job a specific player is currently working. */
    private record Held(Miner.Task task, long claimedAt) {}

    private final Plugin plugin;
    private final Map<UUID, Held> held = new HashMap<>();

    // The optional broadcast offer (human-mode intercepting the queue).
    private Miner.Task offered;
    private long offeredAt;

    public PlayerLane(Plugin plugin) { this.plugin = plugin; }

    // --- broadcast offer lane (human-mode) ---------------------------------

    public boolean hasOffer() { return offered != null; }
    public Miner.Task offered() { return offered; }

    public int secondsWaiting() {
        return offered == null ? 0 : (int) ((System.currentTimeMillis() - offeredAt) / 1000);
    }

    /** A task arrived on the queue — announce it for whoever wants it. */
    public void offer(Miner.Task task, int timeoutSeconds) {
        this.offered = task;
        this.offeredAt = System.currentTimeMillis();
        plugin.getServer().broadcast(Component.text(
                "⛏ 새 일감이 도착했습니다 — /lm take 로 받으세요  (" + timeoutSeconds + "초 안에)",
                NamedTextColor.GOLD));
        plugin.getServer().broadcast(Component.text("   " + task.firstLine(), NamedTextColor.GRAY));
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.7f, 1.2f);
        }
    }

    /** True once nobody's holding the offer and it's aged past the timeout. */
    public boolean offerExpired(int timeoutSeconds) {
        return offered != null && secondsWaiting() > timeoutSeconds;
    }

    public void clearOffer() { offered = null; }

    // --- per-player held jobs (both lanes) ---------------------------------

    public boolean isWorking(Player p) { return held.containsKey(p.getUniqueId()); }
    public Miner.Task heldTask(Player p) {
        Held h = held.get(p.getUniqueId());
        return h == null ? null : h.task();
    }
    public int secondsHeld(Player p) {
        Held h = held.get(p.getUniqueId());
        return h == null ? 0 : (int) ((System.currentTimeMillis() - h.claimedAt()) / 1000);
    }

    /** Hand a claimed job to a player: the readable task + a book to answer in. */
    public void give(Player player, Miner.Task task) {
        held.put(player.getUniqueId(), new Held(task, System.currentTimeMillis()));
        player.getInventory().addItem(taskBook(task));
        player.getInventory().addItem(new ItemStack(Material.WRITABLE_BOOK));
        player.sendMessage("§a일감을 받았습니다. §7'책과 깃펜'에 답을 쓰고 §f서명§7한 뒤 §f/lm submit§7 하세요.");
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
    }

    public void finish(Player player) { held.remove(player.getUniqueId()); }

    /** Pull the answer out of the signed book the player is holding. */
    public String readAnswer(Player player) {
        ItemStack heldItem = player.getInventory().getItemInMainHand();
        if (heldItem.getType() != Material.WRITTEN_BOOK) return null;
        if (!(heldItem.getItemMeta() instanceof BookMeta meta)) return null;
        StringBuilder sb = new StringBuilder();
        for (String page : meta.getPages()) sb.append(page).append('\n');
        String text = sb.toString().trim();
        return text.isEmpty() ? null : text;
    }

    public void clear() {
        offered = null;
        held.clear();
    }

    /** The task, as something you can actually read in-game. */
    private ItemStack taskBook(Miner.Task task) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle("Job " + shortId(task.id()));
        meta.setAuthor("Ledgermind");

        List<String> pages = new ArrayList<>();
        pages.add("§0§l일감\n\n§0" + wrap(task.prompt(), 220)
                + "\n\n§8답을 '책과 깃펜'에\n쓰고 서명한 뒤\n/lm submit");
        String rest = task.prompt().length() > 220 ? task.prompt().substring(220) : "";
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
