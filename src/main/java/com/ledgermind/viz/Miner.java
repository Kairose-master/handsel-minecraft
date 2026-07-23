package com.ledgermind.viz;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-game mining: the Ledgermind local-worker protocol
 * (docs/agent-integration.md §2) implemented in Java, so a Paper server IS
 * the worker rig. Same three calls public/ledgermind-worker.mjs makes —
 * poll for a queued task, run it through a local OpenAI-compatible model,
 * post the result back. This is a client of an existing protocol, not a new
 * one; the platform grades the output exactly as it grades any other worker.
 *
 * <p>Every method here runs OFF the main server thread (blocking HTTP + a
 * model call that can take minutes). Callers hop back for anything in-world.
 *
 * <p><b>Money:</b> deliberately read-only. `/api/worker/wallet` shows the
 * balance, but withdrawing re-authenticates with the ACCOUNT PASSWORD by
 * design — that never belongs in a game server's config file, so this class
 * has no withdraw path at all.
 */
public final class Miner {

    public enum State { OFF, IDLE, WORKING, ERROR }

    private static final String SYSTEM_PROMPT =
            "You are an autonomous worker agent on the Ledgermind labor market. "
            + "Complete the task exactly as specified. If the task requires code in a "
            + "fenced code block, provide the complete, runnable code. Be factual and concise.";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private final String platformUrl;
    private final String agentId;
    private final String secret;
    private final String modelBase;   // OpenAI-compatible base, e.g. http://localhost:11434/v1
    private final String model;
    private final int modelTimeoutMinutes;

    private volatile State state = State.OFF;
    private volatile String currentTaskId;
    private volatile String lastError;
    private volatile int tasksDone;
    private volatile int tasksFailed;

    private Miner(String platformUrl, String agentId, String secret,
                  String modelBase, String model, int modelTimeoutMinutes) {
        this.platformUrl = platformUrl.replaceAll("/+$", "");
        this.agentId = agentId;
        this.secret = secret;
        this.modelBase = modelBase.replaceAll("/+$", "");
        this.model = model;
        this.modelTimeoutMinutes = Math.max(1, modelTimeoutMinutes);
    }

    /**
     * Decode a "Connect a local worker" token: base64url of
     * {@code {a: agentId, s: secret, u: platformUrl}} — the same token the
     * reference worker script takes.
     *
     * @throws IllegalArgumentException if the token isn't a usable token
     */
    public static Miner fromToken(String token, String modelBase, String model, int timeoutMinutes) {
        String json;
        try {
            json = new String(Base64.getUrlDecoder().decode(token.trim()), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("token is not valid base64url - copy it again from the dashboard");
        }
        JsonObject cfg;
        try {
            cfg = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("token did not decode to JSON - copy it again from the dashboard");
        }
        if (!cfg.has("a") || !cfg.has("s") || !cfg.has("u")) {
            throw new IllegalArgumentException("token is incomplete (needs agent id, secret, platform url)");
        }
        return new Miner(cfg.get("u").getAsString(), cfg.get("a").getAsString(),
                cfg.get("s").getAsString(), modelBase, model, timeoutMinutes);
    }

    // --- state (read from the main thread for the rig hologram) ------------

    public State state() { return state; }
    public String currentTaskId() { return currentTaskId; }
    public String lastError() { return lastError; }
    public int tasksDone() { return tasksDone; }
    public int tasksFailed() { return tasksFailed; }
    public String model() { return model; }
    public String agentId() { return agentId; }
    public String platformUrl() { return platformUrl; }

    public void markStarted() { if (state == State.OFF) state = State.IDLE; }
    public void markStopped() { state = State.OFF; }

    /** Short agent id for display — the full id isn't secret but is noise. */
    public String shortAgentId() {
        return agentId.length() > 10 ? agentId.substring(0, 8) + "…" : agentId;
    }

    /**
     * One poll cycle. Returns a result when a task was actually run, else null.
     * Skips itself while a task is already in flight — a model call can outlast
     * many poll intervals and the platform hands out one task per poll anyway.
     */
    public Result tick() {
        if (state == State.OFF || !busy.compareAndSet(false, true)) return null;
        try {
            JsonObject task = poll();
            if (task == null) {
                if (state != State.ERROR) state = State.IDLE;
                return null;
            }
            String taskId = task.get("task_id").getAsString();
            String prompt = task.has("task") && !task.get("task").isJsonNull()
                    ? task.get("task").getAsString() : "";
            currentTaskId = taskId;
            state = State.WORKING;

            long startedAt = System.currentTimeMillis();
            String output = null;
            String error = null;
            try {
                output = askModel(prompt);
                if (output.isBlank()) error = "local model returned empty output";
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            boolean success = error == null;
            int seconds = (int) ((System.currentTimeMillis() - startedAt) / 1000);

            try {
                submit(taskId, success, success ? output : "Local worker error: " + error, seconds);
            } catch (Exception e) {
                success = false;
                error = "submit failed: " + e.getMessage();
            }

            currentTaskId = null;
            if (success) {
                tasksDone++;
                state = State.IDLE;
                lastError = null;
            } else {
                tasksFailed++;
                state = State.ERROR;
                lastError = error;
            }
            return new Result(taskId, success, seconds, error, firstLine(prompt));
        } catch (Exception e) {
            state = State.ERROR;
            lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            return null;
        } finally {
            busy.set(false);
        }
    }

    /** POST /api/worker/poll — returns the task object, or null when idle. */
    private JsonObject poll() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("agent_id", agentId);
        JsonObject res = platformPost("/api/worker/poll", body.toString());
        if (res == null || !res.has("task") || res.get("task").isJsonNull()) return null;
        JsonObject task = res.getAsJsonObject("task");
        return task.has("task_id") ? task : null;
    }

    /** POST /api/runtime/callback — the same submission any worker makes. */
    private void submit(String taskId, boolean success, String output, int seconds) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("task_id", taskId);
        payload.addProperty("agent_id", agentId);
        payload.addProperty("success", success);
        payload.addProperty("output", output);
        payload.addProperty("plan", "");
        payload.add("quality_score", com.google.gson.JsonNull.INSTANCE); // self-scoring carries no weight by design
        payload.addProperty("execution_time", seconds);
        payload.addProperty("token_cost", 0);

        com.google.gson.JsonArray events = new com.google.gson.JsonArray();
        events.add(event(taskId, "TASK_STARTED", true, 0));
        events.add(event(taskId, success ? "TASK_COMPLETED" : "TASK_FAILED", success, seconds));
        payload.add("events", events);

        platformPost("/api/runtime/callback", payload.toString());
    }

    private JsonObject event(String taskId, String type, boolean success, int seconds) {
        JsonObject e = new JsonObject();
        e.addProperty("agent_id", agentId);
        e.addProperty("task_id", taskId);
        e.addProperty("event_type", type);
        e.addProperty("success", success);
        e.addProperty("execution_time", seconds);
        e.addProperty("token_cost", 0);
        e.add("quality_score", com.google.gson.JsonNull.INSTANCE);
        JsonObject detail = new JsonObject();
        detail.addProperty("runtime", "minecraft-plugin");
        detail.addProperty("model", model);
        e.add("detail", detail);
        return e;
    }

    /** POST /api/worker/wallet — read-only balance view. */
    public String walletLine() {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("agent_id", agentId);
            JsonObject res = platformPost("/api/worker/wallet", body.toString());
            if (res == null) return null;
            String usdc = res.has("usdc") && !res.get("usdc").isJsonNull()
                    ? res.get("usdc").getAsString() : "?";
            String addr = res.has("address") && !res.get("address").isJsonNull()
                    ? res.get("address").getAsString() : "";
            String shortAddr = addr.length() > 12
                    ? addr.substring(0, 6) + "…" + addr.substring(addr.length() - 4) : addr;
            return "USDC " + usdc + (shortAddr.isEmpty() ? "" : "  ·  " + shortAddr);
        } catch (Exception e) {
            return null;
        }
    }

    private JsonObject platformPost(String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(platformUrl + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("X-Runtime-Secret", secret)
                .header("User-Agent", "LedgermindViz/0.3.0 (Paper plugin)")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            // The API returns {"error":"human-readable reason"} on refusals —
            // surface just that, so a claim rejection reads cleanly in chat.
            String detail = res.body();
            try {
                JsonElement e = JsonParser.parseString(res.body());
                if (e.isJsonObject() && e.getAsJsonObject().has("error")) {
                    detail = e.getAsJsonObject().get("error").getAsString();
                }
            } catch (RuntimeException ignored) { /* keep raw body */ }
            throw new RuntimeException(detail.substring(0, Math.min(200, detail.length())));
        }
        JsonElement parsed = JsonParser.parseString(res.body());
        return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
    }

    /**
     * One completion against an OpenAI-compatible endpoint (Ollama's /v1 by
     * default). Non-streaming, with a long timeout — the reference script
     * streams to survive slow reasoning models; here the equivalent guard is
     * simply allowing the request to take minutes.
     */
    private String askModel(String task) throws Exception {
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", SYSTEM_PROMPT);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", task);
        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        messages.add(sys);
        messages.add(user);

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);
        body.add("messages", messages);

        HttpRequest req = HttpRequest.newBuilder(URI.create(modelBase + "/chat/completions"))
                .timeout(Duration.ofMinutes(modelTimeoutMinutes))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer not-needed") // local endpoints ignore it
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new RuntimeException("model responded " + res.statusCode() + ": "
                    + res.body().substring(0, Math.min(200, res.body().length())));
        }
        JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
        com.google.gson.JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) return "";
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        String content = message != null && message.has("content") && !message.get("content").isJsonNull()
                ? message.get("content").getAsString() : "";
        return stripThinking(content);
    }

    /** Reasoning models wrap scratchpad text in <think>…</think>; the answer is what's left. */
    static String stripThinking(String content) {
        if (content == null) return "";
        String cleaned = content.replaceAll("(?s)<think>.*?</think>", "").trim();
        return cleaned.isEmpty() ? content.trim() : cleaned;
    }

    private static String firstLine(String s) {
        if (s == null || s.isBlank()) return "";
        String line = s.split("\n", 2)[0];
        return line.length() > 60 ? line.substring(0, 60) + "…" : line;
    }

    /** What one completed cycle did — for the in-world effects and broadcast. */
    public record Result(String taskId, boolean success, int seconds, String error, String taskLine) {}

    /** A task handed out by the platform, before anyone (model or human) works it. */
    public record Task(String id, String prompt) {
        public String firstLine() { return Miner.firstLine(prompt); }
    }

    /**
     * Directly claim ONE Open Labor Market job by id — the manual-worker path.
     * Unlike {@link #claimTask()} (which only drains an already-dispatched
     * queue), this walks up to the open market and takes a specific job, doing
     * the on-chain accept server-side. The returned Task is then worked and
     * submitted through {@link #deliver} exactly like any other.
     *
     * @throws Exception with the platform's own refusal message (job taken,
     *         score too low, capability mismatch, self-deal, …)
     */
    public Task claimJob(int jobId) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("agent_id", agentId);
        body.addProperty("job_id", jobId);
        JsonObject res = platformPost("/api/worker/claim", body.toString());
        if (res == null || !res.has("task_id")) {
            throw new RuntimeException("claim returned no task");
        }
        return new Task(res.get("task_id").getAsString(),
                res.has("prompt") && !res.get("prompt").isJsonNull()
                        ? res.get("prompt").getAsString() : "");
    }

    // --- the pipeline, exposed piece by piece ------------------------------
    // tick() runs model-mode end to end; human mode (BUILD_PLAN "player lane")
    // needs the same three steps with a person in the middle, so each is public.

    /** Claim one queued task, or null when the platform has nothing for us. */
    public Task claimTask() {
        if (state == State.OFF || !busy.compareAndSet(false, true)) return null;
        try {
            JsonObject task = poll();
            if (task == null) {
                if (state != State.ERROR) state = State.IDLE;
                return null;
            }
            String taskId = task.get("task_id").getAsString();
            String prompt = task.has("task") && !task.get("task").isJsonNull()
                    ? task.get("task").getAsString() : "";
            currentTaskId = taskId;
            state = State.WORKING;
            return new Task(taskId, prompt);
        } catch (Exception e) {
            state = State.ERROR;
            lastError = e.getMessage() == null ? e.toString() : e.getMessage();
            return null;
        } finally {
            // A claimed task is worked outside this call, so the lock is released
            // here; releaseTask() below is what ends the WORKING state.
            busy.set(false);
        }
    }

    /** Run a prompt through the configured model (used for the human-mode fallback). */
    public String runModel(String prompt) throws Exception {
        return askModel(prompt);
    }

    /**
     * Submit work for a claimed task — the SAME callback the model path uses, so
     * a human-written answer is graded exactly like a model-written one. The
     * platform has no opinion on how the output was produced (agent-integration
     * §2), only on whether it is correct.
     */
    public Result deliver(Task task, String output, int seconds) {
        boolean success = output != null && !output.isBlank();
        String error = success ? null : "empty submission";
        try {
            submit(task.id(), success, success ? output : "Worker error: " + error, seconds);
        } catch (Exception e) {
            success = false;
            error = "submit failed: " + e.getMessage();
        }
        currentTaskId = null;
        if (success) { tasksDone++; state = State.IDLE; lastError = null; }
        else { tasksFailed++; state = State.ERROR; lastError = error; }
        return new Result(task.id(), success, seconds, error, task.firstLine());
    }
}
