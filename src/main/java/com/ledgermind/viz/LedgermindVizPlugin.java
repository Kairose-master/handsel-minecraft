package com.ledgermind.viz;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Locale;

public final class LedgermindVizPlugin extends JavaPlugin implements TabExecutor {

    private static final List<String> SUBS =
            List.of("board", "village", "rig", "mine", "take", "submit", "wallet",
                    "top", "jobs", "on", "off", "status", "reload", "clear");

    private LedgermindClient client;
    private JobBoard board;
    private AgentVillage village;
    private Miner miner;
    private MinerRig rig;
    private final BlockCanvas canvas = new BlockCanvas();
    private QuestBoard questBoard;
    private MineShaft mineShaft;
    private final PlayerLane playerLane = new PlayerLane(this);
    private BukkitTask poller;
    private BukkitTask minerTask;
    private int pollSeconds;
    private int maxJobs;
    private int maxAgents;
    private boolean showVault;
    private boolean broadcastFills;
    private volatile String lastWalletLine;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        restoreBoardFromConfig();
        restoreVillageFromConfig();
        restoreRigFromConfig();
        startPolling();
        if (miner != null && getConfig().getBoolean("mining.autostart", false)) startMining();
        getLogger().info("LedgermindViz enabled - polling " + client.baseUrl()
                + " every " + pollSeconds + "s");
    }

    @Override
    public void onDisable() {
        stopPolling();
        stopMining();
        if (board != null) board.clear();
        if (village != null) village.clear();
        if (rig != null) rig.clear();
        if (mineShaft != null) mineShaft.clear();
        canvas.restoreAll(); // never leave built blocks behind on shutdown
    }

    private void loadSettings() {
        String base = getConfig().getString("base-url",
                "https://ai-agent-credit-dashboard.vercel.app");
        pollSeconds = Math.max(5, getConfig().getInt("poll-seconds", 15));
        maxJobs = Math.max(1, getConfig().getInt("max-jobs", 8));
        maxAgents = Math.max(1, Math.min(64, getConfig().getInt("max-agents", 12)));
        showVault = getConfig().getBoolean("show-vault", true);
        broadcastFills = getConfig().getBoolean("broadcast-fills", true);
        client = new LedgermindClient(base);
        loadMiner();
    }

    /** Build the miner from the connect token, if one is configured. */
    private void loadMiner() {
        miner = null;
        String token = getConfig().getString("mining.token", "");
        if (token == null || token.isBlank()) return;
        try {
            miner = Miner.fromToken(token,
                    getConfig().getString("mining.model-base", "http://localhost:11434/v1"),
                    getConfig().getString("mining.model", "qwen2.5:7b"),
                    getConfig().getInt("mining.model-timeout-minutes", 15));
            getLogger().info("miner configured for agent " + miner.shortAgentId()
                    + " (model " + miner.model() + ") - start it with /lm mine start");
        } catch (IllegalArgumentException e) {
            getLogger().warning("mining.token unusable: " + e.getMessage());
        }
    }

    // --- polling -----------------------------------------------------------

    private void startPolling() {
        stopPolling();
        long ticks = pollSeconds * 20L;
        poller = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (board == null && village == null) return; // nothing to render into yet

            List<Job> jobs = List.of();
            if (board != null) {
                try {
                    jobs = client.fetchOpenJobs(maxJobs);
                } catch (Exception e) {
                    getLogger().warning("job poll failed: " + e.getMessage());
                    return;
                }
            }
            List<Agent> agents = List.of();
            if (village != null) {
                try {
                    agents = client.fetchAgents(maxAgents);
                } catch (Exception e) {
                    getLogger().warning("agent poll failed: " + e.getMessage());
                }
            }
            String vault = showVault ? client.fetchVaultLine() : null;

            // hop back to the main thread for every world/entity mutation
            final List<Job> polledJobs = jobs;
            final List<Agent> polledAgents = agents;
            getServer().getScheduler().runTask(this, () -> {
                if (village != null && !polledAgents.isEmpty()) village.render(polledAgents, vault);
                if (board == null) return;
                List<Job> filled = board.render(polledJobs, vault);
                if (questBoard != null) questBoard.render(polledJobs, vault);
                for (Job j : filled) {
                    if (broadcastFills) broadcastFill(j);
                    if (questBoard != null && getConfig().getBoolean("build.celebrate", true)) {
                        questBoard.celebrate(j);
                    }
                    if (village != null) {
                        // Prefer the agent display names (they key the NPCs); the
                        // wallet labels never match one, so they're only a last
                        // resort before the board-burst fallback (BUILD_PLAN §17).
                        village.animatePayment(
                                firstNonBlank(j.requesterName(), j.requesterLabel()),
                                firstNonBlank(j.workerName(), j.workerLabel()),
                                board.anchor());
                    }
                }
            });
        }, 40L, ticks);
    }

    private void stopPolling() {
        if (poller != null) {
            poller.cancel();
            poller = null;
        }
    }

    // --- mining ------------------------------------------------------------

    /**
     * The mining loop. Everything inside the async task blocks (HTTP + a model
     * call that can run for minutes); only the in-world reaction hops back to
     * the main thread. Miner#tick self-skips while a task is in flight, so a
     * slow model can't pile up overlapping work.
     */
    private void startMining() {
        stopMining();
        if (miner == null) return;
        miner.markStarted();
        long ticks = Math.max(1, getConfig().getInt("mining.poll-seconds", 5)) * 20L;
        minerTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (getConfig().getBoolean("mining.human-mode", false)) { humanTick(); return; }
            Miner.State before = miner.state();
            Miner.Result result = miner.tick();
            Miner.State after = miner.state();
            // Only re-read the wallet after a completed task (it's an extra HTTP
            // call), but keep showing the last known figure in between.
            if (result != null) {
                String fresh = miner.walletLine();
                if (fresh != null) lastWalletLine = fresh;
            }
            final String wallet = lastWalletLine;

            getServer().getScheduler().runTask(this, () -> {
                boolean justStarted = before != Miner.State.WORKING && after == Miner.State.WORKING;
                if (rig != null) {
                    if (justStarted) rig.startFx();
                    if (result != null) rig.doneFx(result.success());
                    rig.render(miner, wallet);
                }
                if (mineShaft != null) {
                    if (justStarted) mineShaft.onStart();
                    else if (after == Miner.State.WORKING) mineShaft.tickProgress();
                    if (result != null) mineShaft.onFinish(result.success(), 1);
                }
                if (result != null && getConfig().getBoolean("mining.broadcast", true)) {
                    getServer().broadcast(result.success()
                            ? Component.text("⛏ Ledgermind: mined a task in " + result.seconds()
                                    + "s - " + result.taskLine(), NamedTextColor.GREEN)
                            : Component.text("⛏ Ledgermind: task failed - " + result.error(),
                                    NamedTextColor.RED));
                }
            });
        }, 20L, ticks);
    }

    /**
     * Human-lane poll: claim a task, put it on the table for players, and only
     * fall back to the model when nobody takes it in time. Runs async like the
     * model loop; every in-world/chat effect hops to the main thread.
     */
    private void humanTick() {
        int timeout = Math.max(30, getConfig().getInt("mining.human-timeout-seconds", 300));

        // An offer already on the table: leave it for players until it ages out,
        // then let the model take it so a dispatched task never rots in the queue.
        if (playerLane.hasOffer()) {
            if (!playerLane.offerExpired(timeout)) return;
            Miner.Task task = playerLane.offered();
            if (!getConfig().getBoolean("mining.human-fallback-to-model", true)) return;
            long startedAt = System.currentTimeMillis();
            String output;
            try {
                output = miner.runModel(task.prompt());
            } catch (Exception e) {
                output = "";
                getLogger().warning("fallback model failed: " + e.getMessage());
            }
            Miner.Result r = miner.deliver(task, output,
                    (int) ((System.currentTimeMillis() - startedAt) / 1000));
            getServer().getScheduler().runTask(this, () -> {
                playerLane.clearOffer();
                announceResult(r, "§7(아무도 안 받아서 모델이 처리했습니다)");
            });
            return;
        }

        // Nothing offered — pull a task off the queue (if auto-mine dispatched
        // one) and offer it. The direct /lm take <id> path doesn't need this.
        Miner.Task task = miner.claimTask();
        if (task == null) return;
        getServer().getScheduler().runTask(this, () -> {
            playerLane.offer(task, timeout);
            if (mineShaft != null) mineShaft.onStart();
            if (rig != null) { rig.startFx(); rig.render(miner, lastWalletLine); }
        });
    }

    /** Shared post-delivery effects for both lanes. */
    private void announceResult(Miner.Result r, String suffix) {
        if (mineShaft != null) mineShaft.onFinish(r.success(), 1);
        if (rig != null) { rig.doneFx(r.success()); rig.render(miner, lastWalletLine); }
        if (getConfig().getBoolean("mining.broadcast", true)) {
            getServer().broadcast(r.success()
                    ? Component.text("⛏ Ledgermind: 제출 완료 (" + r.seconds() + "s) - "
                            + r.taskLine() + " " + suffix, NamedTextColor.GREEN)
                    : Component.text("⛏ Ledgermind: 제출 실패 - " + r.error(), NamedTextColor.RED));
        }
    }

    private void stopMining() {
        if (minerTask != null) {
            minerTask.cancel();
            minerTask = null;
        }
        if (miner != null) miner.markStopped();
    }

    private void broadcastFill(Job job) {
        getServer().broadcast(Component.text("⚙ Ledgermind: job #" + job.id()
                        + " (" + usd(job.rewardUsd()) + ") was just filled - "
                        + shorten(job.title()),
                NamedTextColor.GREEN));
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private static int clamp(String raw, int fallback) {
        try {
            return Math.max(1, Math.min(20, Integer.parseInt(raw)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String usd(double v) {
        return v == Math.floor(v) ? "$" + (long) v : String.format("$%.2f", v);
    }

    private static String shorten(String s) {
        if (s == null) return "";
        return s.length() > 48 ? s.substring(0, 48) + "..." : s;
    }

    // --- board persistence -------------------------------------------------

    private void restoreBoardFromConfig() {
        String worldName = getConfig().getString("board.world");
        if (worldName == null) return;
        var w = getServer().getWorld(worldName);
        if (w == null) {
            getLogger().warning("saved board world '" + worldName + "' not found; place it again with /lm board");
            return;
        }
        Location boardLoc = new Location(w,
                getConfig().getDouble("board.x"),
                getConfig().getDouble("board.y"),
                getConfig().getDouble("board.z"));
        board = new JobBoard(boardLoc);
        if (getConfig().getBoolean("build.quest-lectern", true)) {
            questBoard = new QuestBoard(this, canvas, boardLoc);
        }
    }

    private void saveBoardLocation(Location loc) {
        saveLocation("board", loc);
    }

    private void restoreVillageFromConfig() {
        String worldName = getConfig().getString("village.world");
        if (worldName == null) return;
        var w = getServer().getWorld(worldName);
        if (w == null) {
            getLogger().warning("saved village world '" + worldName + "' not found; place it again with /lm village");
            return;
        }
        village = new AgentVillage(this, new Location(w,
                getConfig().getDouble("village.x"),
                getConfig().getDouble("village.y"),
                getConfig().getDouble("village.z")));
        if (getConfig().getBoolean("build.towers", true)) village.withTowers(new CreditTower(canvas));
    }

    private void restoreRigFromConfig() {
        String worldName = getConfig().getString("rig.world");
        if (worldName == null) return;
        var w = getServer().getWorld(worldName);
        if (w == null) return;
        Location rigLoc = new Location(w,
                getConfig().getDouble("rig.x"),
                getConfig().getDouble("rig.y"),
                getConfig().getDouble("rig.z"));
        rig = new MinerRig(rigLoc);
        if (getConfig().getBoolean("build.mine", true)) {
            mineShaft = new MineShaft(this, canvas, rigLoc,
                    getConfig().getInt("mining.expected-seconds", 60));
        }
    }

    private void saveLocation(String key, Location loc) {
        getConfig().set(key + ".world", loc.getWorld().getName());
        getConfig().set(key + ".x", loc.getX());
        getConfig().set(key + ".y", loc.getY());
        getConfig().set(key + ".z", loc.getZ());
        saveConfig();
    }

    // --- commands ----------------------------------------------------------

    /**
     * Subcommands that change the world or the miner. Everything else —
     * looking at the economy and working a job — is open to every player,
     * because a server full of friends is the point (BUILD_PLAN §12) and a
     * spectator-only guest can't take part in the human lane.
     */
    private static final List<String> ADMIN_SUBS =
            List.of("board", "village", "rig", "mine", "on", "off", "reload", "clear");

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length > 0 && ADMIN_SUBS.contains(a[0].toLowerCase(Locale.ROOT))
                && !s.hasPermission("ledgermind.admin")) {
            s.sendMessage("§c그 명령은 관리자(OP)만 쓸 수 있어요.");
            s.sendMessage("§7쓸 수 있는 것: §f/lm take, /lm submit, /lm top, /lm jobs, /lm wallet, /lm status");
            return true;
        }
        if (a.length == 0) {
            s.sendMessage("/lm <board|village|rig|mine|take|submit|wallet|top|jobs|on|off|status|reload|clear>");
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "board" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage("players only - run this in-game where you want the board");
                    return true;
                }
                Location loc = p.getEyeLocation()
                        .add(p.getEyeLocation().getDirection().multiply(3));
                if (board != null) board.clear();
                board = new JobBoard(loc);
                if (getConfig().getBoolean("build.quest-lectern", true)) {
                    questBoard = new QuestBoard(this, canvas, loc);
                }
                saveBoardLocation(loc);
                startPolling();
                s.sendMessage("§aLedgermind board placed. It updates every " + pollSeconds + "s.");
            }
            case "village" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage("players only - stand where the plaza should go");
                    return true;
                }
                Location loc = p.getLocation().clone();
                if (village != null) village.clear();
                village = new AgentVillage(this, loc);
                if (getConfig().getBoolean("build.towers", true)) village.withTowers(new CreditTower(canvas));
                saveLocation("village", loc);
                startPolling();
                s.sendMessage("§aAgent village anchored here. Up to " + maxAgents
                        + " agents appear within " + pollSeconds + "s.");
            }
            case "rig" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage("players only - stand where the rig should go");
                    return true;
                }
                if (rig != null) rig.clear();
                rig = new MinerRig(p.getLocation().clone());
                if (getConfig().getBoolean("build.mine", true)) {
                    mineShaft = new MineShaft(this, canvas, p.getLocation().clone(),
                            getConfig().getInt("mining.expected-seconds", 60));
                    mineShaft.build();
                }
                saveLocation("rig", p.getLocation());
                if (miner != null) rig.render(miner, null);
                s.sendMessage(miner == null
                        ? "§eRig placed, but no mining.token is configured - see /lm mine"
                        : "§aRig placed. §7/lm mine start §ato begin.");
            }
            case "mine" -> {
                if (miner == null) {
                    s.sendMessage("§eNo miner configured. Put a 'Connect a local worker' token");
                    s.sendMessage("§ein plugins/LedgermindViz/config.yml as §fmining.token§e, then §f/lm reload§e.");
                    s.sendMessage("§8Get one at " + client.baseUrl() + " -> your agent -> Connect a local worker");
                    return true;
                }
                String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "status";
                switch (sub) {
                    case "start" -> {
                        startMining();
                        s.sendMessage("§amining started §7(agent " + miner.shortAgentId()
                                + ", model " + miner.model() + ")");
                    }
                    case "stop" -> {
                        stopMining();
                        if (rig != null) rig.render(miner, null);
                        s.sendMessage("§emining stopped");
                    }
                    default -> s.sendMessage("§7miner=" + miner.state()
                            + " done=" + miner.tasksDone()
                            + " failed=" + miner.tasksFailed()
                            + " model=" + miner.model()
                            + (miner.lastError() != null ? " §clastError=" + miner.lastError() : ""));
                }
            }
            case "take" -> {
                if (!(s instanceof Player p)) { s.sendMessage("players only"); return true; }
                if (miner == null) {
                    s.sendMessage("§7채굴 토큰이 설정돼 있지 않습니다 — §f/lm mine§7 참고");
                    return true;
                }
                if (playerLane.isWorking(p)) {
                    s.sendMessage("§e이미 일감을 하나 받았습니다. §f/lm submit§e 으로 제출하세요.");
                    return true;
                }

                // /lm take <jobId> — the manual-worker path: claim a specific
                // Open job straight off the market, no auto-mine needed.
                if (a.length > 1) {
                    int jobId;
                    try { jobId = Integer.parseInt(a[1]); }
                    catch (NumberFormatException e) { s.sendMessage("§c일감 번호를 숫자로: §f/lm take 180"); return true; }
                    s.sendMessage("§7#" + jobId + " 수주 중…");
                    getServer().getScheduler().runTaskAsynchronously(this, () -> {
                        Miner.Task task;
                        try {
                            task = miner.claimJob(jobId);
                        } catch (Exception e) {
                            getServer().getScheduler().runTask(this,
                                    () -> s.sendMessage("§c수주 실패: §7" + e.getMessage()));
                            return;
                        }
                        getServer().getScheduler().runTask(this, () -> {
                            playerLane.give(p, task);
                            if (mineShaft != null) mineShaft.onStart();
                            getServer().broadcast(Component.text(
                                    "⛏ " + p.getName() + " 님이 일감 #" + jobId
                                            + " 을(를) 수주했습니다 — AI와 같은 채점을 받습니다",
                                    NamedTextColor.GOLD));
                        });
                    });
                    return true;
                }

                // /lm take (no id) — grab the broadcast offer, if human-mode fed one.
                if (playerLane.hasOffer()) {
                    Miner.Task task = playerLane.offered();
                    playerLane.clearOffer();
                    playerLane.give(p, task);
                    getServer().broadcast(Component.text(
                            "⛏ " + p.getName() + " 님이 일감을 받았습니다 — AI와 같은 채점을 받습니다",
                            NamedTextColor.GOLD));
                    return true;
                }

                s.sendMessage("§7받을 일감을 고르세요: §f/lm jobs §7로 목록을 보고 §f/lm take <번호>");
            }
            case "submit" -> {
                if (!(s instanceof Player p)) { s.sendMessage("players only"); return true; }
                Miner.Task task = playerLane.heldTask(p);
                if (task == null) {
                    s.sendMessage("§7받은 일감이 없습니다. §f/lm jobs §7→ §f/lm take <번호>§7 로 먼저 받으세요.");
                    return true;
                }
                String answer = playerLane.readAnswer(p);
                if (answer == null) {
                    s.sendMessage("§c손에 §f서명된 책§c을 들고 있어야 합니다.");
                    s.sendMessage("§7'책과 깃펜'에 답을 쓰고 → §f서명하기§7 → 다시 §f/lm submit");
                    return true;
                }
                int seconds = playerLane.secondsHeld(p);
                s.sendMessage("§a제출 중… §7채점 결과는 대시보드에 반영됩니다.");
                getServer().getScheduler().runTaskAsynchronously(this, () -> {
                    Miner.Result r = miner.deliver(task, answer, seconds);
                    String fresh = miner.walletLine();
                    if (fresh != null) lastWalletLine = fresh;
                    getServer().getScheduler().runTask(this, () -> {
                        playerLane.finish(p);
                        announceResult(r, "§7(by " + p.getName() + ")");
                    });
                });
            }
            case "wallet" -> {
                if (miner == null) {
                    s.sendMessage("§eno miner configured - /lm mine");
                    return true;
                }
                getServer().getScheduler().runTaskAsynchronously(this, () -> {
                    String line = miner.walletLine();
                    getServer().getScheduler().runTask(this, () -> s.sendMessage(
                            line != null ? "§2⛁ " + line : "§cwallet read failed"));
                });
            }
            case "top" -> {
                int n = a.length > 1 ? clamp(a[1], 5) : 5;
                getServer().getScheduler().runTaskAsynchronously(this, () -> {
                    List<Agent> agents;
                    try {
                        agents = client.fetchAgents(n);
                    } catch (Exception e) {
                        getServer().getScheduler().runTask(this,
                                () -> s.sendMessage("§cleaderboard failed: " + e.getMessage()));
                        return;
                    }
                    getServer().getScheduler().runTask(this, () -> {
                        s.sendMessage("§6⛏ §fLedgermind — top agents");
                        int i = 0;
                        for (Agent ag : agents) {
                            s.sendMessage("§7" + (++i) + ". §f" + ag.name()
                                    + " §8· " + AgentNpc.tierColor(ag.creditRating(), ag.creditScore())
                                    + (long) ag.creditScore() + " " + ag.creditRating()
                                    + (ag.jobsDone() > 0 ? " §8· §2$" + (long) ag.earnedUsd()
                                        + " §8(" + ag.jobsDone() + " jobs)" : ""));
                        }
                    });
                });
            }
            case "jobs" -> {
                int n = a.length > 1 ? clamp(a[1], 5) : 5;
                getServer().getScheduler().runTaskAsynchronously(this, () -> {
                    List<Job> jobs;
                    try {
                        jobs = client.fetchOpenJobs(n);
                    } catch (Exception e) {
                        getServer().getScheduler().runTask(this,
                                () -> s.sendMessage("§cjob feed failed: " + e.getMessage()));
                        return;
                    }
                    getServer().getScheduler().runTask(this, () -> {
                        if (jobs.isEmpty()) {
                            s.sendMessage("§7no open jobs right now");
                            return;
                        }
                        s.sendMessage("§6⛏ §fLedgermind — open jobs §7(받으려면 /lm take <번호>)");
                        for (Job j : jobs) {
                            s.sendMessage("§e#" + j.id() + " §a$" + (long) j.rewardUsd()
                                    + " §f" + j.title()
                                    + " §8· " + j.verification()
                                    + (j.requesterName() != null && !j.requesterName().isEmpty()
                                        ? " §8by §7" + j.requesterName() : ""));
                        }
                    });
                });
            }
            case "on" -> {
                startPolling();
                s.sendMessage("§apolling on");
            }
            case "off" -> {
                stopPolling();
                s.sendMessage("§epolling off");
            }
            case "status" -> s.sendMessage("§7board=" + (board != null)
                    + " village=" + (village != null ? village.size() + " npcs" : "false")
                    + " polling=" + (poller != null)
                    + " every=" + pollSeconds + "s"
                    + " maxJobs=" + maxJobs + " maxAgents=" + maxAgents
                    + " miner=" + (miner == null ? "none" : miner.state().toString())
                    + " url=" + client.baseUrl());
            case "reload" -> {
                // loadSettings() builds a NEW Miner (the token/model may have
                // changed), and a fresh Miner starts OFF — so a reload while
                // mining would leave the loop ticking against a stopped miner
                // and quietly stop working. Carry the running state across.
                boolean wasMining = minerTask != null;
                reloadConfig();
                loadSettings();
                startPolling();
                if (wasMining || getConfig().getBoolean("mining.autostart", false)) startMining();
                s.sendMessage("§aconfig reloaded"
                        + (miner != null && minerTask != null ? " §7(채굴 계속 실행 중)" : ""));
            }
            case "clear" -> {
                if (board != null) board.clear();
                if (village != null) village.clear();
                if (rig != null) rig.clear();
                if (questBoard != null) questBoard.clear();
                if (mineShaft != null) mineShaft.clear();
                int restored = canvas.size();
                canvas.restoreAll();
                board = null;
                village = null;
                rig = null;
                questBoard = null;
                mineShaft = null;
                s.sendMessage("§7블록 " + restored + "개를 원래대로 복구했습니다.");
                getConfig().set("board", null);
                getConfig().set("village", null);
                getConfig().set("rig", null);
                saveConfig();
                s.sendMessage("§eboard, village and rig removed §7(mining itself: /lm mine stop)");
            }
            default -> s.sendMessage("/lm <board|village|rig|mine|take|submit|wallet|top|jobs|on|off|status|reload|clear>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (a.length != 1) return List.of();
        String prefix = a[0].toLowerCase(Locale.ROOT);
        boolean admin = s.hasPermission("ledgermind.admin");
        return SUBS.stream()
                .filter(x -> admin || !ADMIN_SUBS.contains(x))
                .filter(x -> x.startsWith(prefix))
                .toList();
    }
}
