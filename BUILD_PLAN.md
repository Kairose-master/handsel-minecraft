# Ledgermind × Minecraft — Build Plan (handoff for another session)

> **Purpose.** A self-contained plan a future session (or developer) can execute
> to build a Minecraft **Paper plugin** that visualizes the Ledgermind AI-agent
> economy in-world, as a viral/expansion tool. Everything needed is here:
> verified API shapes, full v1 code, gotchas, toolchain notes, and QA.
>
> **Status:** not started. This doc is the seed. Start at "Step-by-step".

---

## 1. Concept & why it goes viral

Ledgermind is an AI-agent labor market (agents hire, grade, pay, and lend to
each other on testnet). This plugin makes that **abstract economy visible** in
Minecraft: a live **job board** where the real open bounties float as holograms,
and when a job gets claimed/paid the world reacts (particles, sound, broadcast).

Viral precedent: Altera's *Project Sid* (1000 AI agents in Minecraft) went huge
because it **visualized emergent agent behavior**. Our built-in drama engine is
the **credit system** — an agent going from score 0 → creditworthy, borrowing
against reputation, getting paid on pass. Watching that unfold in a village is
the hook. Content-first: a 30–60s clip of the living economy beats any ad.

**Honest constraint:** a sandbox session can COMPILE the plugin (Java 21 + Maven
are available) but **cannot run a Minecraft server or record gameplay**. The
deliverable of the build session is a **working, compiled `.jar` + run
instructions**; the human drops it into a Paper server's `plugins/` and records.

---

## 2. Scope tiers (build v1; sketch v2/v3)

- **v1 — Live job board (THIS PLAN).** Plugin polls the real keyless API and
  renders open jobs as floating `TextDisplay` holograms above a player-set
  anchor. Diffs each poll: new job → blue "ding" + particles; a job that leaves
  the Open feed (claimed/paid) → green "cha-ching" + particles + chat broadcast.
  Optional MiniVault gauge line (price + health factor). **No Ledgermind
  server-side changes needed.**
- **v2 — Agent village.** One villager/NPC per agent with a floating credit-score
  + balance hologram; a "requester" and "worker" NPC animate a payment (item
  toss + particle stream) when a job completes. Needs an agents/world data
  endpoint (see §4 — `/api/world` or reuse `/world` data source).
- **v3 — Mineflayer puppets (separate Node project).** Bots that physically walk
  to the job board / bank driven by the real economy events (Option "puppet" —
  decisions come from Ledgermind, not per-bot LLMs). Highest production value,
  weeks of work. Out of scope here; note only.

---

## 3. Architecture

```
Minecraft (Paper server)
  └─ LedgermindViz plugin (Java 21)
        ├─ async task every N s → HTTP GET (keyless public API)
        ├─ parse JSON (Gson, bundled with Paper)
        └─ hop to main thread → render Display entities + effects
                    │
                    ▼  (read-only, keyless)
        https://ai-agent-credit-dashboard.vercel.app
          /api/tasks           (open jobs feed)
          /api/vault/onchain   (MiniVault gauge, optional)
```

- **Paper**, not Spigot/Bukkit (better API + `Display` entities + Adventure).
- **Java 21** (Paper 1.20.6+ requires it). Target **Paper API 1.21.1**.
- **No external deps to shade:** HTTP via built-in `java.net.http.HttpClient`;
  JSON via **Gson** which Paper bundles (declare `provided` scope). Output a
  plain jar — no shade plugin.
- All entity/world mutations happen on the **main server thread**; HTTP is async.

---

## 4. Verified API contract (keyless, testnet)

Base: `https://ai-agent-credit-dashboard.vercel.app`

### `GET /api/tasks?status=Open&limit=8`  → open jobs feed
```json
{
  "type": "LedgermindTaskFeed",
  "count": 1,
  "tasks": [
    {
      "id": "148",
      "kind": "paid_job",
      "title": "next_run(expr, after) …",
      "description": "…",
      "acceptanceCriteria": "…",
      "rewardUsd": 6,
      "minScore": 0,
      "status": "Open",
      "requesterLabel": "0xea32…cB8A",
      "workerAgentId": null,
      "workerLabel": null,
      "verification": "manual_review",
      "createdAt": null
    }
  ]
}
```
Fields the board uses: `id`, `title`, `rewardUsd`, `status`, `verification`,
`requesterLabel`. (No `deliverableKind` here — infer from title if needed, or
show `verification`.)

### `GET /api/vault/onchain`  → MiniVault gauge (optional v1 line)
```json
{
  "deployed": true, "chain": "sepolia",
  "state": { "address": "0x3470…", "priceUsd": 3000, "totalSupplyGusd": 1 },
  "position": { "healthFactor": 2.25, "liquidatable": false },
  "crossCheck": { "engineAgrees": true }
}
```

### For v2 later (NOT needed for v1)
Agents/world data: check `app/(dashboard)/world` / `lib/platform-index.ts` for a
keyless read, or add a small `GET /api/world/agents` returning `[{name, creditScore, rating, balanceUsd}]`. `/api/market/index` is **x402-paywalled (402)** — do NOT use it keyless.

---

## 5. Repo decision

Recommended: **separate repo `ledgermind-minecraft`** (heavy unrelated Java/Maven
deps, content/experimental, only consumes the public HTTP API → zero coupling).
The `desktop/` precedent means in-repo `minecraft/` is also acceptable if kept
isolated (its own Maven build; the Next.js `tsc`/`eslint` gates never touch Java,
so it won't break them). Either is fine — pick one and keep the plugin's build
self-contained. This plan's paths assume a project root (repo root or
`minecraft/`).

---

## 6. Toolchain (verified available in-session)

- `java -version` → OpenJDK 21 ✅
- `mvn -version` → Maven present ✅ (downloads Paper API from `repo.papermc.io`)
- Build: `mvn -q -DskipTests package` → `target/LedgermindViz-0.1.0.jar`
- **Cannot** run a Paper server or record in the sandbox → hand the jar to the
  human. Compile success is the in-session acceptance bar.

---

## 7. File tree (v1)

```
<root>/
  pom.xml
  README.md
  src/main/resources/plugin.yml
  src/main/resources/config.yml
  src/main/java/com/ledgermind/viz/
    LedgermindVizPlugin.java   # main: config, scheduler, /lm command
    LedgermindClient.java      # HTTP + Gson parsing (async-safe)
    Job.java                   # record
    JobBoard.java              # Display entities, diff, effects (main-thread)
```

---

## 8. Full v1 code (assemble, then `mvn package` and fix any API drift)

### pom.xml
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.ledgermind</groupId>
  <artifactId>ledgermind-viz</artifactId>
  <version>0.1.0</version>
  <packaging>jar</packaging>
  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
  <repositories>
    <repository><id>papermc</id><url>https://repo.papermc.io/repository/maven-public/</url></repository>
  </repositories>
  <dependencies>
    <dependency>
      <groupId>io.papermc.paper</groupId><artifactId>paper-api</artifactId>
      <version>1.21.1-R0.1-SNAPSHOT</version><scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>com.google.code.gson</groupId><artifactId>gson</artifactId>
      <version>2.11.0</version><scope>provided</scope>
    </dependency>
  </dependencies>
  <build>
    <finalName>LedgermindViz-${project.version}</finalName>
    <plugins>
      <plugin><groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>
    </plugins>
  </build>
</project>
```

### src/main/resources/plugin.yml
```yaml
name: LedgermindViz
version: 0.1.0
main: com.ledgermind.viz.LedgermindVizPlugin
api-version: '1.21'
author: Ledgermind
description: A live in-world job board driven by the real Ledgermind agent-economy API (testnet).
commands:
  lm:
    description: Ledgermind visualization controls
    usage: "/lm <board|on|off|status>"
    permission: ledgermind.admin
permissions:
  ledgermind.admin:
    description: Manage the Ledgermind visualization
    default: op
```

### src/main/resources/config.yml
```yaml
base-url: "https://ai-agent-credit-dashboard.vercel.app"
poll-seconds: 15
max-jobs: 8
# board anchor is saved here by /lm board (world, x, y, z)
```

### src/main/java/com/ledgermind/viz/Job.java
```java
package com.ledgermind.viz;

public record Job(String id, String title, double rewardUsd, String status,
                  String verification, String requesterLabel) {}
```

### src/main/java/com/ledgermind/viz/LedgermindClient.java
```java
package com.ledgermind.viz;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** All HTTP is keyless GETs to the public API. Safe to call off the main thread. */
public final class LedgermindClient {
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public LedgermindClient(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    /** GET /api/tasks?status=Open&limit=N — throws on failure (caller logs). */
    public List<Job> fetchOpenJobs(int limit) throws Exception {
        String url = baseUrl + "/api/tasks?status=Open&limit=" + limit;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json").GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) throw new RuntimeException("HTTP " + res.statusCode());
        JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
        JsonArray tasks = root.getAsJsonArray("tasks");
        List<Job> out = new ArrayList<>();
        if (tasks == null) return out;
        for (JsonElement e : tasks) {
            JsonObject t = e.getAsJsonObject();
            out.add(new Job(
                    str(t, "id"), str(t, "title"),
                    t.has("rewardUsd") && !t.get("rewardUsd").isJsonNull() ? t.get("rewardUsd").getAsDouble() : 0,
                    str(t, "status"), str(t, "verification"), str(t, "requesterLabel")));
        }
        return out;
    }

    /** GET /api/vault/onchain — returns "priceUsd | HF x.xx" or null on failure. */
    public String fetchVaultLine() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/vault/onchain"))
                    .timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) return null;
            JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
            if (!root.has("state")) return null;
            double price = root.getAsJsonObject("state").get("priceUsd").getAsDouble();
            JsonObject pos = root.has("position") && !root.get("position").isJsonNull()
                    ? root.getAsJsonObject("position") : null;
            String hf = pos != null && pos.has("healthFactor") && !pos.get("healthFactor").isJsonNull()
                    ? String.format("%.2f", pos.get("healthFactor").getAsDouble()) : "—";
            return "MiniVault  $" + (long) price + "  ·  HF " + hf;
        } catch (Exception ex) { return null; }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }
}
```

### src/main/java/com/ledgermind/viz/JobBoard.java
```java
package com.ledgermind.viz;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Renders the live job board with Display entities. MAIN THREAD ONLY. */
public final class JobBoard {
    private static final String Y = "§e", G = "§a", B = "§b",
            GRY = "§8", W = "§f", AMB = "§6";

    private final Location anchor;      // top of the board
    private TextDisplay header;
    private TextDisplay vaultLine;
    private final Map<String, TextDisplay> rows = new HashMap<>();
    private Set<String> lastIds = new HashSet<>();

    public JobBoard(Location anchor) { this.anchor = anchor.clone(); }

    /** Diff + render. Returns filled-job count (for the caller to broadcast). */
    public List<Job> render(List<Job> jobs, String vaultText) {
        World world = anchor.getWorld();
        if (world == null) return List.of();

        if (header == null) header = spawn(anchor.clone().add(0, 0.4, 0),
                AMB + "⛏ " + W + "LEDGERMIND — " + GRY + "live jobs (testnet)");

        Set<String> nowIds = new HashSet<>();
        for (Job j : jobs) nowIds.add(j.id());

        // Newly appeared jobs → blue ding
        for (Job j : jobs) if (!lastIds.contains(j.id())) fx(world, false);

        // Jobs that left the Open feed → claimed/paid → green cha-ching + collect
        List<Job> filled = new ArrayList<>();
        for (String id : lastIds) if (!nowIds.contains(id)) { fx(world, true); }

        // Reposition + (re)write rows
        int i = 0;
        for (Job j : jobs) {
            Location loc = anchor.clone().add(0, -0.35 * (++i), 0);
            String col = "manual_review".equals(j.verification()) ? AMB : G;
            String text = Y + "#" + j.id() + "  " + col + "$" + fmt(j.rewardUsd())
                    + GRY + "  " + W + trim(j.title(), 34);
            TextDisplay td = rows.get(j.id());
            if (td == null || td.isDead()) { td = spawn(loc, text); rows.put(j.id(), td); }
            else { td.teleport(loc); td.setText(strip(text)); }
        }
        // Remove rows for jobs no longer present
        rows.entrySet().removeIf(en -> {
            if (!nowIds.contains(en.getKey())) { en.getValue().remove(); return true; }
            return false;
        });

        // Vault line under the list
        Location vloc = anchor.clone().add(0, -0.35 * (jobs.size() + 1) - 0.15, 0);
        if (vaultText != null) {
            if (vaultLine == null || vaultLine.isDead()) vaultLine = spawn(vloc, B + vaultText);
            else { vaultLine.teleport(vloc); vaultLine.setText(B + vaultText); }
        }

        lastIds = nowIds;
        return filled; // ids not tracked individually in v1; broadcast a generic message
    }

    public void clear() {
        if (header != null) header.remove();
        if (vaultLine != null) vaultLine.remove();
        rows.values().forEach(TextDisplay::remove);
        rows.clear();
    }

    private TextDisplay spawn(Location loc, String legacyText) {
        World world = loc.getWorld();
        return world.spawn(loc, TextDisplay.class, td -> {
            td.setText(strip(legacyText));
            td.setBillboard(Display.Billboard.CENTER);
            td.setSeeThrough(true);
            td.setViewRange(1.0f);
            td.setBackgroundColor(Color.fromARGB(140, 8, 12, 22));
            td.setPersistent(false); // don't survive restart; plugin re-renders
        });
    }

    private static void fx(World world, boolean filled) {
        // effects at 0,0,0 relative to nothing here; caller passes anchor world.
    }

    private static String fmt(double v) { return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v); }
    private static String trim(String s, int n) { return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s); }
    // TextDisplay#setText takes a plain String; legacy § codes render on Paper.
    private static String strip(String s) { return s; }
}
```
> NOTE: the `fx()` particle/sound effects are stubbed above — wire them in the
> plugin class where you have the `anchor` world + location (see next file), OR
> pass `world`+`loc` into `fx`. Effects to use (Paper 1.21 names):
> `world.spawnParticle(Particle.HAPPY_VILLAGER, loc, 20, .4,.4,.4, 0)` and
> `world.playSound(loc, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.4f)` for a
> fill; `Particle.ENCHANT` + `Sound.BLOCK_NOTE_BLOCK_PLING` for a new job.

### src/main/java/com/ledgermind/viz/LedgermindVizPlugin.java
```java
package com.ledgermind.viz;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

public final class LedgermindVizPlugin extends JavaPlugin {
    private LedgermindClient client;
    private JobBoard board;
    private BukkitTask poller;
    private int pollSeconds, maxJobs;

    @Override public void onEnable() {
        saveDefaultConfig();
        String base = getConfig().getString("base-url", "https://ai-agent-credit-dashboard.vercel.app");
        pollSeconds = getConfig().getInt("poll-seconds", 15);
        maxJobs = getConfig().getInt("max-jobs", 8);
        client = new LedgermindClient(base);
        restoreBoardFromConfig();
        startPolling();
        getLogger().info("LedgermindViz enabled — polling " + base + " every " + pollSeconds + "s");
    }

    @Override public void onDisable() { stopPolling(); if (board != null) board.clear(); }

    private void startPolling() {
        stopPolling();
        long ticks = Math.max(5, pollSeconds) * 20L;
        poller = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            List<Job> jobs;
            try { jobs = client.fetchOpenJobs(maxJobs); }
            catch (Exception e) { getLogger().warning("poll failed: " + e.getMessage()); return; }
            String vault = client.fetchVaultLine();
            getServer().getScheduler().runTask(this, () -> { if (board != null) board.render(jobs, vault); });
        }, 40L, ticks);
    }
    private void stopPolling() { if (poller != null) { poller.cancel(); poller = null; } }

    private void restoreBoardFromConfig() {
        if (!getConfig().isSet("board.world")) return;
        var w = getServer().getWorld(getConfig().getString("board.world"));
        if (w == null) return;
        board = new JobBoard(new Location(w,
                getConfig().getDouble("board.x"), getConfig().getDouble("board.y"), getConfig().getDouble("board.z")));
    }

    @Override public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) { s.sendMessage("/lm <board|on|off|status>"); return true; }
        switch (a[0].toLowerCase()) {
            case "board" -> {
                if (!(s instanceof Player p)) { s.sendMessage("players only"); return true; }
                Location loc = p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(3));
                if (board != null) board.clear();
                board = new JobBoard(loc);
                getConfig().set("board.world", loc.getWorld().getName());
                getConfig().set("board.x", loc.getX()); getConfig().set("board.y", loc.getY()); getConfig().set("board.z", loc.getZ());
                saveConfig();
                s.sendMessage("§aLedgermind board placed. It updates every " + pollSeconds + "s.");
            }
            case "on" -> { startPolling(); s.sendMessage("§apolling on"); }
            case "off" -> { stopPolling(); s.sendMessage("§epolling off"); }
            case "status" -> s.sendMessage("board=" + (board != null) + " poll=" + pollSeconds + "s max=" + maxJobs);
            default -> s.sendMessage("/lm <board|on|off|status>");
        }
        return true;
    }
}
```

---

## 9. Paper API gotchas (why compile-first matters)

- **Main-thread rule:** never spawn/mutate entities off-thread. HTTP is async;
  the render hop uses `runTask` (sync). Getting this wrong = `IllegalStateException`.
- **Particle/Sound renames (1.20.5+/1.21):** `VILLAGER_HAPPY`→`HAPPY_VILLAGER`,
  `ENCHANTMENT_TABLE`→`ENCHANT`. `Sound` became an interface with constants —
  `Sound.ENTITY_EXPERIENCE_ORB_PICKUP` still resolves on 1.21.1; if it doesn't,
  use `org.bukkit.Registry.SOUNDS` or the key. Compile will tell you.
- **Display entities** (`TextDisplay`) need MC ≥ 1.19.4. `setText(String)` renders
  legacy `§` color codes on Paper. `setBillboard(CENTER)` makes it face players.
- **`world.spawn(loc, Class, Consumer)`** runs the configurer pre-add — set text
  there so the entity never flashes blank.
- **api-version '1.21'** in plugin.yml — required or Paper warns/refuses.
- Effects are stubbed in `JobBoard.fx()` — wire them with the real `world`+`loc`.

---

## 10. Step-by-step (for the build session)

1. Create the file tree in §7 with the §8 code. (Decide repo per §5.)
2. `mvn -q -DskipTests package`. Fix compile errors (mostly Particle/Sound enum
   names against 1.21.1 — the compiler names each one).
3. Wire the `fx()` effects with the anchor's `world`/`loc` (green cha-ching on
   fill, blue ding on new job) and, if desired, broadcast via
   `getServer().broadcast(net.kyori.adventure.text.Component.text("§a💰 A job was just filled on Ledgermind!"))`.
4. Confirm `target/LedgermindViz-0.1.0.jar` builds. **That is the session's
   done bar** (can't run a server here).
5. Write `README.md`: how to run — download Paper 1.21.1, `java -jar paper.jar`,
   drop the jar in `plugins/`, restart, `/lm board`, watch the live jobs.
6. Hand the jar + README to the human to run locally and record.

## 11. v1 acceptance (human, on a real server)
- `/lm board` places a hologram board; within ~15s real open jobs appear as
  `#id  $reward  title`.
- Posting/claiming a job on Ledgermind changes the board within a poll; a filled
  job triggers the green effect + broadcast.

---

## 12. Viral packaging (after v1 runs)
- Record 30–60s: empty board → jobs stream in → one fills with a cha-ching.
- Vertical crop for Shorts/Reels/TikTok; landscape for X/YouTube.
- Caption hook: *"I visualized an AI-agent economy inside Minecraft — these
  bots are hiring and paying each other with real (testnet) money."*
- Later: v2 agent village (credit holograms) is the real Sid-style payload.

## 13. Nice-to-haves / backlog
- Per-job fill tracking (broadcast the exact `#id` + reward that filled).
- Sign/lectern at the board linking to `/world`.
- v2 endpoint `GET /api/world/agents` (keyless) → NPCs per agent.
- ClawHub/Modrinth listing for the plugin itself once it's polished.
```
