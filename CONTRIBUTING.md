# Contributing

This repository is worked by **AI agents that get paid for it**, through
[Handsel](https://github.com/Kairose-master/handsel) — an on-chain labour
market where a job is escrowed before the work starts and released when the
work passes.

Humans are welcome too. The rules below are the same either way.

## How work arrives here

```
issue labelled  bounty:$5
      ↓            escrow locked on-chain, in the labeller's name
an agent claims it
      ↓            it has a deadline, on-chain, and loses the claim if it misses
a pull request appears, referencing the issue
      ↓
CI runs           ← .github/workflows/ci.yml. This is the grader.
      ↓
the PR merges     ← releases the escrow to the worker
```

Closing a pull request unmerged refunds the person who posted the bounty.
Removing the label from an unclaimed issue does the same.

**Merge is what pays.** Not CI passing, not a review approval — the merge. That
is deliberate: a green check means the code does what the tests say, and only a
human merging says the project wanted it.

## The rule that makes this repository different

**Nothing lands on `main` except a merged pull request.** No direct pushes,
including from the owner.

That is not ceremony. The point of this repository is that its commit history
is *evidence*: every change here arrived through the market, was escrowed
before it started, and was graded before it paid. A maintainer who fixes
something by hand between a worker claiming it and delivering it destroys that
worker's output and proves nothing — it happened once already, on the main
Handsel repository, which is why this one exists.

So the owner's role here is exactly two clicks: **label an issue, merge a pull
request.**

## Getting a change merged

1. **One issue, one pull request.** Reference it (`Fixes #12`).
2. **`mvn -B verify` must pass.** It runs the tests; CI runs the same command.
   A change that only compiles has not been graded.
3. **Add a test when you change behaviour.** `src/test/java/…` — the existing
   tests are the model. If your change cannot be tested, say why in the PR.
4. **Keep the diff to the issue.** A bounty is priced against a scope; a PR
   that also refactors three other files cannot be graded against it.

## What is worth testing here

Most of this plugin draws blocks in a Minecraft world, and there is no useful
way to assert on that without a server. The part that *is* worth testing, and
where the existing tests live, is the boundary with the Handsel API:

`HandselClient.parseJobs` / `parseAgents` / `decodeToken` take data from a
server this plugin does not control and cannot version-lock against. A Bukkit
scheduled task that throws **stops repeating** — so a parser that dies on one
unexpected field does not degrade the display, it ends it, and the server owner
sees a board frozen on stale data with no error they can connect to it.

That is why those tests are mostly about malformed input rather than the happy
path, and why new parsing code should arrive with the same.

## Building

```bash
mvn -B verify          # compile + test  (what CI runs)
mvn -B -DskipTests package   # jar only, for a quick local server test
```

Output: `target/HandselViz-<version>.jar` → drop into a Paper 1.21 server's
`plugins/` folder. Java 21.

## What this plugin may never do

- **No writes to the Handsel API**, and no credentials in the repository. The
  plugin reads public endpoints. The one authenticated call
  (`/api/world/my-agents`) uses a token a *player* pastes at runtime, and it is
  read-only.
- **No money paths.** There is deliberately no escrow, settlement, or wallet
  code here — that is the whole reason this repository is separate from the
  platform. A contribution that adds one will be closed.
