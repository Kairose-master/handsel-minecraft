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
            List.of("board", "village", "on", "off", "status", "reload", "clear");

    private LedgermindClient client;
    private JobBoard board;
    private AgentVillage village;
    private BukkitTask poller;
    private int pollSeconds;
    private int maxJobs;
    private int maxAgents;
    private boolean showVault;
    private boolean broadcastFills;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        restoreBoardFromConfig();
        restoreVillageFromConfig();
        startPolling();
        getLogger().info("LedgermindViz enabled - polling " + client.baseUrl()
                + " every " + pollSeconds + "s");
    }

    @Override
    public void onDisable() {
        stopPolling();
        if (board != null) board.clear();
        if (village != null) village.clear();
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
                        // workerLabel is null on the Open feed, so this usually
                        // falls back to a burst at the board (BUILD_PLAN §17)
                        village.animatePayment(j.requesterLabel(), null, board.anchor());
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

    private void broadcastFill(Job job) {
        getServer().broadcast(Component.text("⚙ Ledgermind: job #" + job.id()
                        + " (" + usd(job.rewardUsd()) + ") was just filled - "
                        + shorten(job.title()),
                NamedTextColor.GREEN));
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
            s.sendMessage("/lm <board|village|on|off|status|reload|clear>");
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
                board = null;
                village = null;
                getConfig().set("board", null);
                getConfig().set("village", null);
                saveConfig();
                s.sendMessage("§eboard and village removed");
            }
            default -> s.sendMessage("/lm <board|village|on|off|status|reload|clear>");
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
