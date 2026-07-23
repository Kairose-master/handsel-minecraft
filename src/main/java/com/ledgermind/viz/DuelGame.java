package com.ledgermind.viz;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;

/**
 * "Human vs AI" duel — a quick round where a player and the local model race
 * to answer a factual question about the live Ledgermind economy.
 *
 * <p>The twist that makes it on-theme: the question is answerable from what a
 * human can SEE (the sidebar scoreboard shows the live ranking), while the AI
 * opponent is asked the same question with NO data — it has to guess from
 * memory. So the human's real-time access to the screen is the advantage, the
 * exact "human holds an edge over an AI agent" thesis the project is about.
 *
 * <p>No escrow, no credit — this is a self-contained game graded against the
 * live agent data, so it can't affect anyone's real score. MAIN THREAD for
 * chat/effects; the model call runs off-thread.
 */
public final class DuelGame {

    private final Plugin plugin;
    private final int answerSeconds;

    private boolean active;
    private String question;
    private String answer;          // canonical correct answer (lowercased)
    private String answerDisplay;   // pretty form for the reveal
    private long startedAt;
    private String humanName;
    private boolean humanCorrect;
    private String aiAnswerRaw;
    private boolean aiAnswered;

    public DuelGame(Plugin plugin, int answerSeconds) {
        this.plugin = plugin;
        this.answerSeconds = Math.max(10, answerSeconds);
    }

    public boolean active() { return active; }

    /**
     * Start a round from the current leaderboard. Returns false if there isn't
     * enough live data to make a fair question yet.
     */
    public boolean start(List<Agent> agents, Miner miner) {
        if (active) return false;
        if (agents == null || agents.size() < 3) return false;

        // Pick a question type deterministically-ish by list contents (no RNG —
        // Math.random() is unavailable in some contexts and we want it cheap).
        int pick = (int) ((System.currentTimeMillis() / 1000) % 3);
        Agent a0 = agents.get(0), a1 = agents.get(1), a2 = agents.get(2);
        switch (pick) {
            case 0 -> {
                question = a1.name() + " 와 " + a2.name() + " 중 신용점수가 더 높은 에이전트는?";
                boolean firstHigher = a1.creditScore() >= a2.creditScore();
                answerDisplay = (firstHigher ? a1 : a2).name();
            }
            case 1 -> {
                question = "지금 리더보드 1위 에이전트의 이름은?";
                answerDisplay = a0.name();
            }
            default -> {
                question = a0.name() + " 의 신용 등급(rating)은? (예: A, BBB, D)";
                answerDisplay = a0.creditRating();
            }
        }
        answer = answerDisplay.toLowerCase(Locale.ROOT).trim();

        active = true;
        startedAt = System.currentTimeMillis();
        humanName = null;
        humanCorrect = false;
        aiAnswered = false;
        aiAnswerRaw = null;

        Bukkit.broadcast(Component.text("⚔ 인간 vs AI — " + answerSeconds + "초!", NamedTextColor.GOLD));
        Bukkit.broadcast(Component.text("   Q: " + question, NamedTextColor.YELLOW));
        Bukkit.broadcast(Component.text("   답: /lm duel <답>   §8(힌트: 오른쪽 순위판을 보세요)", NamedTextColor.GRAY));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
        }

        // Ask the AI opponent in the background — no data given, it must guess.
        if (miner != null) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                String a;
                try {
                    a = miner.runModel("You are competing in a quick trivia game about an AI-agent "
                            + "marketplace called Ledgermind. Answer in a few words only, no explanation.\n\n"
                            + "Question: " + question);
                } catch (Exception e) {
                    a = "";
                }
                final String aiRaw = a;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    aiAnswered = true;
                    aiAnswerRaw = aiRaw;
                });
            });
        } else {
            aiAnswered = true; // no model -> AI forfeits
        }

        // Schedule the reveal.
        plugin.getServer().getScheduler().runTaskLater(plugin, this::reveal, answerSeconds * 20L);
        return true;
    }

    /** A player submits an answer during the round. */
    public void submit(Player p, String guess) {
        if (!active) {
            p.sendMessage("§7지금 진행 중인 대결이 없습니다. §f/lm duel §7로 시작하세요.");
            return;
        }
        if (humanCorrect) return; // already won by a human
        boolean ok = normalize(guess).contains(answer) || answer.contains(normalize(guess));
        if (ok) {
            humanCorrect = true;
            humanName = p.getName();
            Bukkit.broadcast(Component.text("✔ " + p.getName() + " 정답!", NamedTextColor.GREEN));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.6f);
        } else {
            p.sendMessage("§c오답! 다시: §f/lm duel <답>");
        }
    }

    private void reveal() {
        if (!active) return;
        active = false;

        boolean aiCorrect = aiAnswered && aiAnswerRaw != null
                && (normalize(aiAnswerRaw).contains(answer) || answer.contains(normalize(aiAnswerRaw)));

        Bukkit.broadcast(Component.text("⚔ 정답: " + answerDisplay, NamedTextColor.AQUA));
        Bukkit.broadcast(Component.text("   AI 답: "
                + (aiAnswerRaw == null || aiAnswerRaw.isBlank() ? "(기권)" : trim(aiAnswerRaw, 40))
                + (aiCorrect ? " §a✔" : " §c✘"), NamedTextColor.GRAY));

        String winner;
        if (humanCorrect && !aiCorrect) winner = "§a인간(" + humanName + ") 승리!";
        else if (!humanCorrect && aiCorrect) winner = "§cAI 승리!";
        else if (humanCorrect) winner = "§e무승부 (둘 다 정답)";
        else winner = "§7무승부 (둘 다 오답)";
        Bukkit.broadcast(Component.text("   " + winner, NamedTextColor.WHITE));

        // Tally to config so /lm duel stats can show a running record.
        if (humanCorrect && !aiCorrect) bump("human-wins");
        else if (!humanCorrect && aiCorrect) bump("ai-wins");
        else bump("draws");
    }

    public String statsLine() {
        int h = plugin.getConfig().getInt("duel-stats.human-wins", 0);
        int a = plugin.getConfig().getInt("duel-stats.ai-wins", 0);
        int d = plugin.getConfig().getInt("duel-stats.draws", 0);
        return "인간 " + h + "승 · AI " + a + "승 · 무 " + d;
    }

    private void bump(String key) {
        int v = plugin.getConfig().getInt("duel-stats." + key, 0) + 1;
        plugin.getConfig().set("duel-stats." + key, v);
        plugin.saveConfig();
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9가-힣]", "");
    }

    private static String trim(String s, int n) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
