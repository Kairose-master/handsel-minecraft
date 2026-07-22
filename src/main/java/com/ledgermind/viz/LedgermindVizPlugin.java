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
            List.of("board", "village", "rig", "mine", "wallet", "top", "jobs",
                    "on", "off", "status", "reload", "clear");

    private LedgermindClient client;
    private JobBoard board;
    private AgentVillage village;
    private Miner miner;
    private MinerRig rig;
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
                for (Job j : filled) {
                    if (broadcastFills) broadcastFill(j);
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
                if (rig != null) {
                    if (before != Miner.State.WORKING && after == Miner.State.WORKING) rig.startFx();
                    if (result != null) rig.doneFx(result.success());
                    rig.render(miner, wallet);
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
        board = new JobBoard(new Location(w,
                getConfig().getDouble("board.x"),
                getConfig().getDouble("board.y"),
                getConfig().getDouble("board.z")));
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
    }

    private void restoreRigFromConfig() {
        String worldName = getConfig().getString("rig.world");
        if (worldName == null) return;
        var w = getServer().getWorld(worldName);
        if (w == null) return;
        rig = new MinerRig(new Location(w,
                getConfig().getDouble("rig.x"),
                getConfig().getDouble("rig.y"),
                getConfig().getDouble("rig.z")));
    }

    private void saveLocation(String key, Location loc) {
        getConfig().set(key + ".world", loc.getWorld().getName());
        getConfig().set(key + ".x", loc.getX());
        getConfig().set(key + ".y", loc.getY());
        getConfig().set(key + ".z", loc.getZ());
        saveConfig();
    }

    // --- commands ----------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) {
            s.sendMessage("/lm <board|village|rig|mine|wallet|top|jobs|on|off|status|reload|clear>");
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
                        s.sendMessage("§6⛏ §fLedgermind — open jobs");
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
                reloadConfig();
                loadSettings();
                startPolling();
                s.sendMessage("§aconfig reloaded");
            }
            case "clear" -> {
                if (board != null) board.clear();
                if (village != null) village.clear();
                if (rig != null) rig.clear();
                board = null;
                village = null;
                rig = null;
                getConfig().set("board", null);
                getConfig().set("village", null);
                getConfig().set("rig", null);
                saveConfig();
                s.sendMessage("§eboard, village and rig removed §7(mining itself: /lm mine stop)");
            }
            default -> s.sendMessage("/lm <board|village|rig|mine|wallet|top|jobs|on|off|status|reload|clear>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (a.length != 1) return List.of();
        String prefix = a[0].toLowerCase(Locale.ROOT);
        return SUBS.stream().filter(x -> x.startsWith(prefix)).toList();
    }
}
