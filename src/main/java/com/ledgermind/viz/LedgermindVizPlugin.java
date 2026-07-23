package com.ledgermind.viz;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Locale;

public final class LedgermindVizPlugin extends JavaPlugin implements TabExecutor, Listener {

    private static final List<String> SUBS =
            List.of("help", "board", "village", "account", "rig", "mine", "take", "answer", "submit",
                    "duel", "wallet", "top", "jobs", "on", "off", "status", "reload", "clear");

    private LedgermindClient client;
    private JobBoard board;
    /** One town per Ledgermind account, road-linked into a city. */
    private final List<Town> towns = new java.util.ArrayList<>();
    private Miner miner;
    private MinerRig rig;
    private final BlockCanvas canvas = new BlockCanvas();
    private QuestBoard questBoard;
    private MineShaft mineShaft;
    private final PlayerLane playerLane = new PlayerLane(this);
    private Scoreboard scoreboard;
    private Ticker ticker;
    private DuelGame duel;
    private BukkitTask poller;
    private BukkitTask minerTask;
    private BukkitTask tickerTask;
    private BukkitTask lifeTask;
    private int lifeTick;
    private volatile List<Agent> lastAgents = List.of();
    private volatile int lastOpenJobs;
    private volatile String lastVaultLine;
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
        restoreTownsFromConfig();
        restoreRigFromConfig();
        setupHud();
        getServer().getPluginManager().registerEvents(this, this);
        startPolling();
        startLife();
        if (miner != null && getConfig().getBoolean("mining.autostart", false)) startMining();
        getLogger().info("LedgermindViz enabled - polling " + client.baseUrl()
                + " every " + pollSeconds + "s");
    }

    @Override
    public void onDisable() {
        stopPolling();
        stopMining();
        if (lifeTask != null) lifeTask.cancel();
        if (tickerTask != null) tickerTask.cancel();
        for (Town t : towns) { if (t.spectacle != null) t.spectacle.allOff(); t.village.clear(); }
        if (ticker != null) ticker.clear();
        if (scoreboard != null) scoreboard.clear();
        if (board != null) board.clear();
        if (rig != null) rig.clear();
        if (mineShaft != null) mineShaft.clear();
        canvas.restoreAll(); // never leave built blocks behind on shutdown
    }

    // --- HUD: sidebar scoreboard + actionbar ticker ------------------------

    private void setupHud() {
        if (getConfig().getBoolean("hud.scoreboard", true)) {
            scoreboard = new Scoreboard();
            for (Player p : getServer().getOnlinePlayers()) scoreboard.show(p);
        }
        if (getConfig().getBoolean("hud.ticker", true)) {
            ticker = new Ticker();
            long every = Math.max(20L, getConfig().getLong("hud.ticker-seconds", 3) * 20L);
            tickerTask = getServer().getScheduler().runTaskTimer(this,
                    () -> ticker.tick(System.currentTimeMillis()), every, every);
        }
        duel = new DuelGame(this, getConfig().getInt("duel-seconds", 20));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (scoreboard != null) scoreboard.show(p);
        // Greet with the key commands a couple seconds after the join screen clears.
        getServer().getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            p.sendMessage("");
            p.sendMessage("§6§l⛏ LEDGERMIND §r§7— 마인크래프트 속 살아있는 AI 에이전트 경제");
            p.sendMessage("§8§m                                        ");
            p.sendMessage("§f§l처음 오셨나요? 이 순서로 해보세요:");
            p.sendMessage("§e①§f 마을을 걸어보세요 §7— 주민 = AI 에이전트. 지금 일하러 돌아다녀요");
            p.sendMessage("§e②§f 주민을 §a우클릭§f §7— 그 에이전트의 실시간 신용점수·수익을 봅니다");
            p.sendMessage("§e③§f §a/lm jobs §7— 열린 일감을 보고 §a/lm take <번호>§7로 수주");
            p.sendMessage("§e④§f §a/lm answer <답>§7 쓰고 §a/lm submit §7— AI와 똑같이 채점받아 보상!");
            p.sendMessage("§e⑤§f §a/lm duel §7— AI와 퀴즈 대결 · 광장 §a레버 우클릭§7 = 공장 풀가동🎆");
            p.sendMessage("§8§m                                        ");
            p.sendMessage("§7전체 명령: §a/lm help §8· 오른쪽 순위판과 아래 알림도 실시간입니다");
            if (towns.isEmpty()) {
                p.sendMessage("§8(관리자: §7/lm village§8 로 마을을, §7/lm board§8 로 게시판을 설치하세요)");
            }
            p.sendMessage("");
        }, 40L);
    }

    /** Right-click an agent villager → its live Ledgermind profile in chat. */
    @EventHandler
    public void onClickAgent(PlayerInteractEntityEvent e) {
        for (Town t : towns) {
            AgentNpc npc = t.village.npcForEntity(e.getRightClicked());
            if (npc != null) {
                e.setCancelled(true); // no villager trade UI
                if (t.label != null) e.getPlayer().sendMessage("§8[" + t.label + " 마을]");
                npc.profileLines().forEach(e.getPlayer()::sendMessage);
                return;
            }
        }
    }

    /** Right-click a board kiosk → jobs; a FULL-POWER lever → that town's show. */
    @EventHandler
    public void onClickBoard(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Location blk = e.getClickedBlock().getLocation();
        for (Town t : towns) {
            if (t.spectacle != null && t.spectacle.isLever(blk)) {
                t.spectacle.fullPower(getConfig().getInt("spectacle-seconds", 30));
                getServer().broadcast(Component.text(
                        "⚡ " + e.getPlayer().getName() + " 님이 " + t.label
                                + " 공장을 §l풀가동§r§a 시켰습니다!", NamedTextColor.GREEN));
                return;
            }
            if (t.village.isBoardKiosk(blk)) {
                e.getPlayer().sendMessage("§6📋 §f" + t.label + " 게시판 — 열린 일감");
                showJobs(e.getPlayer(), 6);
                return;
            }
        }
    }

    /** Shared open-jobs readout used by /lm jobs and the board kiosk. */
    private void showJobs(CommandSender to, int n) {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            List<Job> jobs;
            try {
                jobs = client.fetchOpenJobs(n);
            } catch (Exception ex) {
                getServer().getScheduler().runTask(this, () -> to.sendMessage("§cjob feed failed: " + ex.getMessage()));
                return;
            }
            getServer().getScheduler().runTask(this, () -> {
                if (jobs.isEmpty()) { to.sendMessage("§7no open jobs right now"); return; }
                to.sendMessage("§7받으려면 §f/lm take <번호>");
                for (Job j : jobs) {
                    to.sendMessage("§e#" + j.id() + " §a$" + (long) j.rewardUsd()
                            + " §f" + j.title() + " §8· " + j.verification()
                            + (j.requesterName() != null && !j.requesterName().isEmpty()
                                ? " §8by §7" + j.requesterName() : ""));
                }
            });
        });
    }

    /** The living-village mover: every 2 ticks, walk each NPC one step. */
    private void startLife() {
        if (lifeTask != null) lifeTask.cancel();
        if (!getConfig().getBoolean("village-life", true)) return;
        lifeTask = getServer().getScheduler().runTaskTimer(this, () -> {
            int t = lifeTick++;
            for (Town town : towns) {
                town.village.tickLife(t);
                if (town.spectacle != null) town.spectacle.tick(t);
            }
        }, 2L, 2L);
    }

    /** Re-render the HUD from the latest poll (main thread). */
    private void refreshHud() {
        if (scoreboard != null && !lastAgents.isEmpty()) {
            scoreboard.render(lastAgents, lastOpenJobs, lastVaultLine, null);
        }
        if (ticker != null) {
            String top = lastAgents.isEmpty() ? null
                    : "1위 " + lastAgents.get(0).name() + " " + (long) lastAgents.get(0).creditScore();
            ticker.setAmbient(top, lastVaultLine);
        }
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
            boolean haveTowns = !towns.isEmpty();
            boolean needJobs = board != null || scoreboard != null || ticker != null;
            boolean needAgents = haveTowns || scoreboard != null || ticker != null;
            if (!needJobs && !needAgents) return; // nothing wants data yet

            List<Job> jobs = List.of();
            if (needJobs) {
                try {
                    jobs = client.fetchOpenJobs(Math.max(maxJobs, 8));
                } catch (Exception e) {
                    getLogger().warning("job poll failed: " + e.getMessage());
                    if (board != null) return; // board can't render without jobs
                }
            }
            // Global leaderboard powers the HUD and any token-less town.
            List<Agent> globalAgents = List.of();
            if (needAgents) {
                try {
                    globalAgents = client.fetchAgents(Math.max(maxAgents, 5));
                } catch (Exception e) {
                    getLogger().warning("agent poll failed: " + e.getMessage());
                }
            }
            // Per-account agents for each town scoped to a token ("1 village = 1 account").
            java.util.Map<Town, List<Agent>> townAgents = new java.util.HashMap<>();
            for (Town t : towns) {
                if (t.token == null) { townAgents.put(t, globalAgents); continue; }
                try {
                    townAgents.put(t, client.fetchMyAgents(t.token));
                } catch (Exception e) {
                    getLogger().warning("town '" + t.label + "' agent poll failed: " + e.getMessage());
                    townAgents.put(t, List.of());
                }
            }
            // All-status jobs drive every town's live foot traffic (who's working / requesting).
            List<Job> roleJobs = List.of();
            if (haveTowns) {
                try {
                    roleJobs = client.fetchJobs("all", 20);
                } catch (Exception e) {
                    getLogger().warning("role poll failed: " + e.getMessage());
                }
            }
            String vault = showVault ? client.fetchVaultLine() : null;

            // hop back to the main thread for every world/entity mutation
            final List<Job> polledJobs = jobs;
            final List<Agent> polledGlobal = globalAgents;
            final List<Job> polledRoleJobs = roleJobs;
            getServer().getScheduler().runTask(this, () -> {
                // Feed the always-on HUD from the global leaderboard.
                if (!polledGlobal.isEmpty()) lastAgents = polledGlobal;
                lastOpenJobs = polledJobs.size();
                lastVaultLine = vault;
                refreshHud();

                for (Town t : towns) {
                    List<Agent> ags = townAgents.getOrDefault(t, List.of());
                    // On an API hiccup (empty/failed fetch) keep the last good roster
                    // so NPCs don't vanish — and respawn if a chunk unload culled them.
                    if (!ags.isEmpty()) lastGoodAgents.put(t, ags);
                    else ags = lastGoodAgents.getOrDefault(t, List.of());
                    if (!ags.isEmpty()) t.village.render(ags, vault);
                    if (!polledRoleJobs.isEmpty()) t.village.assignRoles(polledRoleJobs);
                }
                if (board == null) return;
                List<Job> filled = board.render(polledJobs, vault);
                if (questBoard != null) questBoard.render(polledJobs, vault);
                for (Job j : filled) {
                    if (broadcastFills) broadcastFill(j);
                    for (Town t : towns) {
                        if (t.spectacle != null) t.spectacle.pulse();
                        // requester/worker NPCs may live in any town — animate wherever they resolve
                        t.village.animatePayment(
                                firstNonBlank(j.requesterName(), j.requesterLabel()),
                                firstNonBlank(j.workerName(), j.workerLabel()),
                                board.anchor());
                    }
                    if (ticker != null) ticker.push("§a💰 일감 #" + j.id() + " ($" + (long) j.rewardUsd()
                            + ") 완료" + (j.workerName() != null && !j.workerName().isEmpty()
                                ? " — " + j.workerName() : ""), System.currentTimeMillis());
                    if (questBoard != null && getConfig().getBoolean("build.celebrate", true)) {
                        questBoard.celebrate(j);
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

    /** Rebuild every saved town (config list `villages:`), plus a legacy single `village:`. */
    private void restoreTownsFromConfig() {
        // Legacy single-village config → migrate into the list on next save.
        if (getConfig().isSet("village.world")) {
            var w = getServer().getWorld(getConfig().getString("village.world"));
            if (w != null) {
                createTown(null, new Location(w, getConfig().getDouble("village.x"),
                        getConfig().getDouble("village.y"), getConfig().getDouble("village.z")),
                        getConfig().getString("village.token", ""));
            }
        }
        var list = getConfig().getMapList("villages");
        for (var m : list) {
            Object wn = m.get("world");
            var w = wn == null ? null : getServer().getWorld(String.valueOf(wn));
            if (w == null) continue;
            Location loc = new Location(w, asD(m.get("x")), asD(m.get("y")), asD(m.get("z")));
            createTown(m.get("label") == null ? null : String.valueOf(m.get("label")), loc,
                    m.get("token") == null ? "" : String.valueOf(m.get("token")));
        }
        if (!towns.isEmpty()) {
            getLogger().info(towns.size() + " town(s) restored (" + canvas.size() + " blocks)");
        }
    }

    private static double asD(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }

    /**
     * Build one town at {@code loc}: village + credit towers + town structures +
     * spectacle, road-linked to the nearest existing town. A non-blank token
     * scopes it to that account ("1 village = 1 account").
     */
    private Town createTown(String label, Location loc, String tokenStr) {
        AgentVillage village = new AgentVillage(this, loc);
        if (getConfig().getBoolean("build.towers", true)) village.withTowers(new CreditTower(canvas));
        Spectacle spectacle = null;
        if (getConfig().getBoolean("build.town", true)) {
            TownBuilder tb = new TownBuilder(canvas);
            // Link this new town to the nearest existing one with a road.
            Town nearest = nearestTown(loc);
            if (nearest != null) tb.connectRoad(loc, nearest.center());
            tb.build(loc);
            if (getConfig().getBoolean("build.spectacle", true)) spectacle = new Spectacle(this, loc);
        }
        LedgermindClient.Token token = LedgermindClient.decodeToken(tokenStr);
        String name = label != null ? label : (token != null ? shorten(token.agentId()) : "글로벌");
        Town town = new Town(name, village, spectacle, token);
        towns.add(town);
        if (tokenStr != null && !tokenStr.isBlank()) rawTokens.put(town, tokenStr);
        return town;
    }

    private Town nearestTown(Location loc) {
        Town best = null;
        double bestD = Double.MAX_VALUE;
        for (Town t : towns) {
            if (t.center().getWorld() == null || !t.center().getWorld().equals(loc.getWorld())) continue;
            double d = t.center().distanceSquared(loc);
            if (d < bestD) { bestD = d; best = t; }
        }
        return best;
    }

    /** Persist all towns to the `villages:` config list. */
    private void saveTowns() {
        var list = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (Town t : towns) {
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("label", t.label);
            m.put("world", t.center().getWorld().getName());
            m.put("x", t.center().getX());
            m.put("y", t.center().getY());
            m.put("z", t.center().getZ());
            if (t.token != null) m.put("token", tokenStringFor(t));
            list.add(m);
        }
        getConfig().set("villages", list);
        getConfig().set("village", null); // drop the legacy single entry
        saveConfig();
    }

    /** We only kept the decoded token; re-encode isn't needed — store nothing sensitive we can't rebuild.
     *  The raw token was given at placement time; keep it in a side map to persist. */
    private final java.util.Map<Town, String> rawTokens = new java.util.HashMap<>();
    /** Last non-empty roster per town, so an API outage doesn't wipe the NPCs. */
    private final java.util.Map<Town, List<Agent>> lastGoodAgents = new java.util.HashMap<>();
    private String tokenStringFor(Town t) { return rawTokens.getOrDefault(t, ""); }

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
            List.of("board", "village", "account", "rig", "mine", "on", "off", "reload", "clear");

    /** The full custom-command reference, filtered by permission. */
    private void sendHelp(CommandSender s) {
        boolean admin = s.hasPermission("ledgermind.admin");
        s.sendMessage("§6§l⛏ LedgermindViz §r§7— 마인크래프트 속 AI 에이전트 경제 §8v" + getPluginMeta().getVersion());
        s.sendMessage("§f처음이라면: §a/lm jobs §7→ §a/lm take <번호> §7→ §a/lm answer <답> §7→ §a/lm submit");
        s.sendMessage("§8누구나 쓸 수 있는 명령");
        s.sendMessage("§e/lm jobs §8[n] §7— 열린 일감 목록 (받으려면 아래 take)");
        s.sendMessage("§e/lm take §f<번호> §7— 그 일감을 직접 수주 (책 받음)");
        s.sendMessage("§e/lm answer §f<답> §7— 답 작성 §8(붙여넣기 가능, 여러 번 이어붙음)");
        s.sendMessage("§e/lm submit §7— 작성한 답 제출 §8(AI와 같은 채점)");
        s.sendMessage("§e/lm top §8[n] §7— 신용점수 상위 에이전트");
        s.sendMessage("§e/lm wallet §7— 내 채굴 에이전트 지갑 잔고");
        s.sendMessage("§e/lm duel §7— 인간 vs AI 대결 §8(/lm duel <답>, /lm duel stats)");
        s.sendMessage("§e/lm status §7— 마을·폴링·채굴 상태");
        s.sendMessage("§8우클릭: §7에이전트 = 프로필 · 게시판 = 일감 · 레버 = 풀가동🎆");
        if (admin) {
            s.sendMessage("§c관리자(OP) 전용");
            s.sendMessage("§c/lm village §8[이름] §7— 글로벌(전체) 마을 세우기");
            s.sendMessage("§c/lm account add <이름> <토큰> §7— 계정별 마을 등록 §8(1마을=1계정, 도로연결)");
            s.sendMessage("§c/lm account list|remove §7— 등록된 계정 마을 목록/제거");
            s.sendMessage("§c/lm board §7— 일감 게시판 설치 · §c/lm rig §7— 채굴 리그 설치");
            s.sendMessage("§c/lm mine §fstart|stop|status §7— 채굴 제어");
            s.sendMessage("§c/lm on|off §7— API 폴링 · §c/lm reload §7— config 다시 읽기");
            s.sendMessage("§c/lm clear §7— 마을·구조물 전부 제거(블록 원상복구)");
        }
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length > 0 && ADMIN_SUBS.contains(a[0].toLowerCase(Locale.ROOT))
                && !s.hasPermission("ledgermind.admin")) {
            s.sendMessage("§c그 명령은 관리자(OP)만 쓸 수 있어요.");
            s.sendMessage("§7쓸 수 있는 것: §f/lm take, /lm submit, /lm top, /lm jobs, /lm wallet, /lm status");
            return true;
        }
        if (a.length == 0 || a[0].equalsIgnoreCase("help") || a[0].equals("?")) {
            sendHelp(s);
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
                // Global village (whole-platform leaderboard). For one account, use /lm account add.
                String vlabel = a.length > 1 ? a[1] : "글로벌";
                boolean first = towns.isEmpty();
                Town town = createTown(vlabel, p.getLocation().clone(), "");
                saveTowns();
                startPolling();
                s.sendMessage("§a'" + town.label + "' 마을을 세웠습니다 §7(글로벌 리더보드)"
                        + (first ? "." : " — 기존 마을과 도로로 연결됨."));
                s.sendMessage("§8특정 계정만 보려면: §7/lm account add <이름> <토큰>");
            }
            case "account" -> {
                String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "list";
                switch (sub) {
                    case "add" -> {
                        if (!(s instanceof Player p)) { s.sendMessage("§7서 있는 자리에 마을이 생기니 게임에서 실행하세요."); return true; }
                        if (a.length < 4) {
                            s.sendMessage("§7사용법: §f/lm account add <이름> <토큰>");
                            s.sendMessage("§8토큰 = 대시보드 → 에이전트 → \"Connect a local worker\" 에서 발급");
                            return true;
                        }
                        String acctLabel = a[2];
                        String token = a[3];
                        if (LedgermindClient.decodeToken(token) == null) {
                            s.sendMessage("§c토큰이 올바르지 않습니다. 대시보드에서 다시 복사하세요.");
                            return true;
                        }
                        for (Town t : towns) {
                            if (acctLabel.equalsIgnoreCase(t.label)) {
                                s.sendMessage("§c'" + acctLabel + "' 이름의 마을이 이미 있습니다. 다른 이름을 쓰세요.");
                                return true;
                            }
                        }
                        boolean first = towns.isEmpty();
                        Town town = createTown(acctLabel, p.getLocation().clone(), token);
                        saveTowns();
                        startPolling();
                        s.sendMessage("§a계정 마을 '" + acctLabel + "' 등록 완료 §7(에이전트 " + town.token.agentId().substring(0, 6) + "… 소속)");
                        s.sendMessage(first ? "§7이제 이 계정의 에이전트만 이 마을에 나타납니다."
                                : "§7기존 마을과 도로로 자동 연결됐습니다.");
                        s.sendMessage("§8⚠ 토큰이 채팅에 남았습니다 — 신경 쓰이면 대시보드에서 재발급하세요.");
                    }
                    case "list" -> {
                        if (towns.isEmpty()) { s.sendMessage("§7등록된 마을이 없습니다. §f/lm village §7또는 §f/lm account add"); return true; }
                        s.sendMessage("§6등록된 마을/계정 (" + towns.size() + ")");
                        for (Town t : towns) {
                            s.sendMessage("§8· §f" + t.label + " §7"
                                    + (t.token != null ? "계정 " + t.token.agentId().substring(0, 6) + "…" : "글로벌")
                                    + " §8(" + t.village.size() + " 주민)");
                        }
                    }
                    case "remove" -> {
                        if (a.length < 3) { s.sendMessage("§7사용법: §f/lm account remove <이름>"); return true; }
                        Town found = null;
                        for (Town t : towns) if (a[2].equalsIgnoreCase(t.label)) { found = t; break; }
                        if (found == null) { s.sendMessage("§c'" + a[2] + "' 마을이 없습니다. §f/lm account list"); return true; }
                        if (found.spectacle != null) found.spectacle.allOff();
                        found.village.clear();
                        towns.remove(found);
                        rawTokens.remove(found);
                        lastGoodAgents.remove(found);
                        saveTowns();
                        s.sendMessage("§e'" + found.label + "' 마을을 제거했습니다 §7(주민 제거됨; 건물 블록은 /lm clear 로 전체 복구)");
                    }
                    default -> {
                        s.sendMessage("§7/lm account <add|list|remove>");
                        s.sendMessage("§f/lm account add <이름> <토큰> §7— 계정 마을 등록");
                        s.sendMessage("§f/lm account list §7— 등록된 마을 목록");
                        s.sendMessage("§f/lm account remove <이름> §7— 마을 제거");
                    }
                }
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
            case "answer" -> {
                if (!(s instanceof Player p)) { s.sendMessage("players only"); return true; }
                if (playerLane.heldTask(p) == null) {
                    s.sendMessage("§7받은 일감이 없습니다. §f/lm jobs §7→ §f/lm take <번호>§7 로 먼저 받으세요.");
                    return true;
                }
                if (a.length < 2) {
                    String draft = playerLane.draft(p);
                    if (draft.isEmpty()) {
                        s.sendMessage("§7사용법: §f/lm answer <답 내용>§7  — 채팅에 붙여넣기(Ctrl+V)도 됩니다.");
                        s.sendMessage("§7여러 줄이면 §f/lm answer§7 를 여러 번 치면 이어붙습니다.");
                        s.sendMessage("§7다 쓰면 §f/lm submit§7, 지우고 다시 쓰려면 §f/lm answer clear");
                    } else {
                        s.sendMessage("§7현재 작성한 답 (" + draft.length() + "자):");
                        s.sendMessage("§f" + (draft.length() > 200 ? draft.substring(0, 200) + "…" : draft));
                        s.sendMessage("§7제출하려면 §f/lm submit");
                    }
                    return true;
                }
                String line = String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length));
                if (line.equalsIgnoreCase("clear")) {
                    playerLane.clearDraft(p);
                    s.sendMessage("§e답을 비웠습니다.");
                    return true;
                }
                int len = playerLane.appendDraft(p, line);
                s.sendMessage("§a추가됨 §7(총 " + len + "자). 더 쓰려면 §f/lm answer§7, 제출은 §f/lm submit");
            }
            case "submit" -> {
                if (!(s instanceof Player p)) { s.sendMessage("players only"); return true; }
                Miner.Task task = playerLane.heldTask(p);
                if (task == null) {
                    s.sendMessage("§7받은 일감이 없습니다. §f/lm jobs §7→ §f/lm take <번호>§7 로 먼저 받으세요.");
                    return true;
                }
                // Prefer the chat-typed draft (paste-friendly, no length limit);
                // fall back to a signed book for players who wrote one.
                String answer = playerLane.draft(p);
                if (answer.isEmpty()) answer = playerLane.readAnswer(p);
                if (answer == null || answer.isEmpty()) {
                    s.sendMessage("§c아직 제출할 답이 없습니다.");
                    s.sendMessage("§7쉬운 방법: §f/lm answer <답>§7 (붙여넣기 가능) → §f/lm submit");
                    s.sendMessage("§7또는: '책과 깃펜'에 쓰고 §f서명§7 후 손에 들고 §f/lm submit");
                    return true;
                }
                int seconds = playerLane.secondsHeld(p);
                final String finalAnswer = answer;
                s.sendMessage("§a제출 중… §7채점 결과는 대시보드에 반영됩니다.");
                getServer().getScheduler().runTaskAsynchronously(this, () -> {
                    Miner.Result r = miner.deliver(task, finalAnswer, seconds);
                    String fresh = miner.walletLine();
                    if (fresh != null) lastWalletLine = fresh;
                    getServer().getScheduler().runTask(this, () -> {
                        playerLane.finish(p);
                        announceResult(r, "§7(by " + p.getName() + ")");
                    });
                });
            }
            case "duel" -> {
                if (!(s instanceof Player p)) { s.sendMessage("players only"); return true; }
                if (duel == null) { s.sendMessage("§7대결 기능이 꺼져 있습니다."); return true; }
                if (a.length > 1 && a[1].equalsIgnoreCase("stats")) {
                    s.sendMessage("§6⚔ 인간 vs AI 전적 — §f" + duel.statsLine());
                    return true;
                }
                if (duel.active()) {
                    // A second arg during a live round is an answer; bare /lm duel just informs.
                    if (a.length > 1) {
                        duel.submit(p, String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)));
                    } else {
                        s.sendMessage("§7대결 진행 중 — 답: §f/lm duel <답>");
                    }
                    return true;
                }
                if (a.length > 1) { // answering when nothing's running
                    s.sendMessage("§7지금 진행 중인 대결이 없습니다. §f/lm duel §7로 시작하세요.");
                    return true;
                }
                if (!duel.start(lastAgents, miner)) {
                    s.sendMessage("§7아직 대결을 시작할 데이터가 부족합니다 (에이전트 정보 로딩 중).");
                }
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
                s.sendMessage("§6⛏ §fLedgermind — open jobs");
                showJobs(s, n);
            }
            case "on" -> {
                startPolling();
                s.sendMessage("§apolling on");
            }
            case "off" -> {
                stopPolling();
                s.sendMessage("§epolling off");
            }
            case "status" -> {
                int npcs = 0;
                for (Town t : towns) npcs += t.village.size();
                s.sendMessage("§7board=" + (board != null)
                        + " towns=" + towns.size() + " (" + npcs + " npcs)"
                        + " polling=" + (poller != null)
                        + " every=" + pollSeconds + "s"
                        + " miner=" + (miner == null ? "none" : miner.state().toString())
                        + " url=" + client.baseUrl());
                for (Town t : towns) s.sendMessage("§8  · " + t.label
                        + (t.token != null ? " (계정 전용)" : " (글로벌)"));
            }
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
                for (Town t : towns) { if (t.spectacle != null) t.spectacle.allOff(); t.village.clear(); }
                if (rig != null) rig.clear();
                if (questBoard != null) questBoard.clear();
                if (mineShaft != null) mineShaft.clear();
                int restored = canvas.size();
                canvas.restoreAll();
                board = null;
                towns.clear();
                rawTokens.clear();
                rig = null;
                questBoard = null;
                mineShaft = null;
                s.sendMessage("§7블록 " + restored + "개를 원래대로 복구했습니다.");
                getConfig().set("board", null);
                getConfig().set("village", null);
                getConfig().set("villages", null);
                getConfig().set("rig", null);
                saveConfig();
                s.sendMessage("§eboard, 모든 마을, rig 제거됨 §7(채굴 자체: /lm mine stop)");
            }
            default -> { s.sendMessage("§7알 수 없는 명령입니다."); sendHelp(s); }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        boolean admin = s.hasPermission("ledgermind.admin");
        if (a.length == 1) {
            String prefix = a[0].toLowerCase(Locale.ROOT);
            return SUBS.stream()
                    .filter(x -> admin || !ADMIN_SUBS.contains(x))
                    .filter(x -> x.startsWith(prefix))
                    .toList();
        }
        if (a.length == 2) {
            String sub = a[0].toLowerCase(Locale.ROOT);
            String pre = a[1].toLowerCase(Locale.ROOT);
            if (sub.equals("account") && admin)
                return List.of("add", "list", "remove").stream().filter(x -> x.startsWith(pre)).toList();
            if (sub.equals("mine") && admin)
                return List.of("start", "stop", "status").stream().filter(x -> x.startsWith(pre)).toList();
        }
        if (a.length == 3 && a[0].equalsIgnoreCase("account")
                && a[1].equalsIgnoreCase("remove") && admin) {
            return towns.stream().map(t -> t.label).filter(java.util.Objects::nonNull).toList();
        }
        return List.of();
    }
}
