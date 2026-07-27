# CLAUDE.md — repo guide

Orientation for an AI (or human) working in this repo. Read this first.

## What this is

**HandselViz** — a Paper 1.21 plugin that renders the
[Handsel](https://github.com/Kairose-master/handsel) agent labour market inside
Minecraft. Open bounties become holograms on a board, agents become villagers
with credit-score towers behind them, and the server itself can work jobs as a
local worker.

**And it is the repository Handsel dogfoods its own market on.** You are
probably here because a bounty brought you. Read `CONTRIBUTING.md` — the rule
that matters is that nothing reaches `main` except a merged pull request, and
the merge is what releases the escrow.

## Stack & layout

- **Java 21**, **Paper 1.21** API, Maven. No external runtime dependencies: HTTP
  is the JDK's `HttpClient`, JSON is the Gson that Paper already bundles (both
  `provided`, so neither ships inside the jar).
- Everything lives in `src/main/java/com/handsel/viz/`.

| I want to… | look in |
|---|---|
| **The only code with an untrusted input** | `HandselClient.java` — parses the public API. Tests live next to it. |
| Plugin entry point, commands, config | `HandselVizPlugin.java` (the big one) |
| The job board + its holograms | `JobBoard.java`, `QuestBoard.java` |
| Agent villagers and their towers | `AgentVillage.java`, `AgentNpc.java`, `CreditTower.java` |
| Working jobs from inside the game | `Miner.java`, `MinerRig.java`, `PlayerLane.java` |
| Placing blocks without destroying builds | `BlockCanvas.java` |
| The polling loop | `Ticker.java`, `Spectacle.java` |
| Design notes and the numbered decision log | `BUILD_PLAN.md` |

## Conventions (important)

- **Bukkit threading is not advisory.** HTTP goes on an async thread; every
  entity spawn, move, block change and text update goes back to the main thread
  via `runTask`. Touching the world off-thread is how a server crashes, not how
  it lags.
- **A scheduled task that throws stops repeating.** That is why the parsers
  return empty instead of raising, and it is the single most important
  behaviour in this codebase: a board frozen on stale data with no visible
  error is worse than a board that shows nothing.
- **Never overwrite a player's build.** `BlockCanvas` remembers the original
  block and restores it on `/lm clear` and on shutdown, and only places into
  air, grass and flowers. Any new block-placing code goes through it.
- **No fake data.** If the API returns nothing, the board says
  `no open jobs right now`. It never invents a number to look alive.
- **Read-only, and no money paths.** The plugin reads public endpoints; the one
  authenticated call is a token a player pastes at runtime. There is
  deliberately no escrow, settlement or withdrawal code here — that is why this
  repo is separate from the platform, and a change that adds one will be
  closed.

## Build / test / verify

```bash
mvn -B verify                 # compile + test — exactly what CI runs
mvn -B -DskipTests package    # jar only, for a quick local server test
```

Output: `target/HandselViz-<version>.jar` → a Paper 1.21 server's `plugins/`.

**`verify`, not `package`.** `package` builds the jar and skips the tests,
which is the difference between a grader and a compiler. CI never skips them,
because CI is what decides whether a bounty gets paid.

### What is worth testing

Most of this plugin draws blocks, and asserting on that needs a running server.
The tests that exist cover the boundary with the Handsel API — `parseJobs`,
`parseAgents`, `decodeToken` — because that is the only input this code does
not control and cannot version-lock against. They are mostly about malformed
input for the reason above: the parser dying is the parser taking the display
with it.

Add tests in that spirit. A test that asserts a hologram is 3 blocks to the
left is testing the decision, not the code.

## Gotchas

- **The plugin ships separately from the API it reads.** A field added to
  `/api/tasks` today is absent for every server running last month's jar, and
  vice versa. Optional-everything is the contract, not laziness.
- **`config.yml` holds a worker token in plaintext** when mining is enabled.
  Anyone who can read that file can do work as that agent — but cannot move
  money: withdrawal re-authenticates with an account password, and this plugin
  has no withdrawal path at all.
- **Cross-repo references.** `BUILD_PLAN.md` and the README point at paths
  (`docs/agent-integration.md`, `public/handsel-worker.mjs`,
  `app/api/world/agents/route.ts`) that live in the Handsel platform repo, not
  here.
- **The API base URL still defaults to the v1 deployment**
  (`ai-agent-credit-dashboard.vercel.app`). That is correct for now; it moves
  when Handsel's own deployment goes live.
