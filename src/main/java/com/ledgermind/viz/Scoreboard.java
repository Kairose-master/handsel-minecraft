package com.ledgermind.viz;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.List;

/**
 * The always-on right-side panel ("자막") — a live read of the Ledgermind
 * economy that every player sees without running a command: the top agents,
 * open-job count, vault price, and the viewer's own rank/earnings when they're
 * mining. Rebuilt each poll on the main thread from data the poller already
 * fetched, so it costs no extra HTTP.
 *
 * <p>One shared {@link org.bukkit.scoreboard.Scoreboard} is assigned to every
 * player. Lines are Teams whose prefix carries the text, keyed by a unique
 * hidden entry per row, so we can rewrite values without flicker.
 */
public final class Scoreboard {

    private static final int TOP_N = 5;

    private final org.bukkit.scoreboard.Scoreboard board;
    private final Objective objective;

    public Scoreboard() {
        board = Bukkit.getScoreboardManager().getNewScoreboard();
        objective = board.registerNewObjective("lm", Criteria.DUMMY,
                Component.text("⛏ LEDGERMIND", NamedTextColor.GOLD));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
    }

    /** Show this board to a player (called on join and on first enable). */
    public void show(Player p) { p.setScoreboard(board); }

    /**
     * Rebuild the sidebar. {@code selfName} is the mining agent's name (or null)
     * so the viewer can see their own rank highlighted.
     */
    public void render(List<Agent> agents, int openJobs, String vaultLine, String selfName) {
        // Sidebar renders highest score at top; we assign descending scores.
        List<String> lines = new ArrayList<>();

        int rank = 0;
        for (Agent a : agents) {
            if (rank >= TOP_N) break;
            String medal = switch (rank) { case 0 -> "§6★"; case 1 -> "§7★"; case 2 -> "§c★"; default -> "§8•"; };
            String col = AgentNpc.tierColor(a.creditRating(), a.creditScore());
            lines.add(medal + " §f" + trim(a.name(), 14) + " " + col + (long) a.creditScore());
            rank++;
        }
        lines.add("§0§m          ");                                  // divider
        lines.add("§7열린 일감 §f" + openJobs);
        if (vaultLine != null) lines.add("§b" + trim(vaultLine, 24));

        // The viewer's own line, if they're the mining agent.
        if (selfName != null) {
            int myRank = indexOf(agents, selfName);
            Agent me = myRank >= 0 ? agents.get(myRank) : null;
            lines.add("§0§m           ");
            if (me != null) {
                lines.add("§e나: §f#" + (myRank + 1) + " §7$" + (long) me.earnedUsd());
            } else {
                lines.add("§e나: §8순위권 밖");
            }
        }

        writeLines(lines);
    }

    /** Replace every row atomically-ish: highest score = top row. */
    private void writeLines(List<String> lines) {
        // Clear old entries whose teams we no longer need.
        for (String entry : new ArrayList<>(board.getEntries())) {
            board.resetScores(entry);
        }
        int score = lines.size();
        int i = 0;
        for (String text : lines) {
            String entry = uniqueEntry(i++);           // invisible per-row key
            Team team = board.getTeam("r" + i);
            if (team == null) team = board.registerNewTeam("r" + i);
            if (!team.hasEntry(entry)) {
                for (String e : new ArrayList<>(team.getEntries())) team.removeEntry(e);
                team.addEntry(entry);
            }
            team.prefix(legacy(text));
            objective.getScore(entry).setScore(score--);
        }
    }

    public void clear() {
        org.bukkit.scoreboard.Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Player p : Bukkit.getOnlinePlayers()) p.setScoreboard(main);
        objective.unregister();
    }

    /** Each row needs a distinct, invisible scoreboard entry — colour codes work. */
    private static String uniqueEntry(int i) {
        // §-code pairs are zero-width; 16 combos are plenty for our rows.
        char c = "0123456789abcdef".charAt(i % 16);
        return "§" + c + "§r";
    }

    private static Component legacy(String s) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection().deserialize(s);
    }

    private static int indexOf(List<Agent> agents, String name) {
        for (int i = 0; i < agents.size(); i++) {
            if (agents.get(i).name().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    private static String trim(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) : s;
    }
}
