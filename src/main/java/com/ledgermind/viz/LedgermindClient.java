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

    public String baseUrl() { return baseUrl; }

    /** GET /api/tasks?status=Open&limit=N — throws on failure (caller logs). */
    public List<Job> fetchOpenJobs(int limit) throws Exception {
        return fetchJobs("Open", limit);
    }

    /**
     * GET /api/tasks?status=S — jobs across statuses, for the town's live foot
     * traffic (who's requesting, who's working). Pass "all" for every status.
     */
    public List<Job> fetchJobs(String status, int limit) throws Exception {
        String url = baseUrl + "/api/tasks?status=" + status + "&limit=" + limit;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("User-Agent", "LedgermindViz/0.1.0 (Paper plugin)")
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) throw new RuntimeException("HTTP " + res.statusCode());

        JsonElement parsed = JsonParser.parseString(res.body());
        if (!parsed.isJsonObject()) throw new RuntimeException("unexpected response shape");
        JsonObject root = parsed.getAsJsonObject();

        List<Job> out = new ArrayList<>();
        if (!root.has("tasks") || root.get("tasks").isJsonNull()) return out;
        JsonArray tasks = root.getAsJsonArray("tasks");
        for (JsonElement e : tasks) {
            if (!e.isJsonObject()) continue;
            JsonObject t = e.getAsJsonObject();
            out.add(new Job(
                    str(t, "id"),
                    str(t, "title"),
                    num(t, "rewardUsd"),
                    str(t, "status"),
                    str(t, "verification"),
                    str(t, "requesterLabel"),
                    str(t, "workerLabel"),
                    str(t, "requesterName"),
                    str(t, "workerName")));
        }
        return out;
    }

    /** GET /api/world/agents?limit=N — the public credit leaderboard (v2 village). */
    public List<Agent> fetchAgents(int limit) throws Exception {
        String url = baseUrl + "/api/world/agents?limit=" + limit;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("User-Agent", "LedgermindViz/0.2.0 (Paper plugin)")
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) throw new RuntimeException("HTTP " + res.statusCode());

        JsonElement parsed = JsonParser.parseString(res.body());
        if (!parsed.isJsonObject()) throw new RuntimeException("unexpected response shape");
        JsonObject root = parsed.getAsJsonObject();

        List<Agent> out = new ArrayList<>();
        if (!root.has("agents") || root.get("agents").isJsonNull()) return out;
        for (JsonElement e : root.getAsJsonArray("agents")) {
            if (!e.isJsonObject()) continue;
            JsonObject a = e.getAsJsonObject();
            String name = str(a, "name");
            if (name.isEmpty()) continue; // NPCs are keyed by name — skip the unusable
            String rating = str(a, "creditRating");
            out.add(new Agent(name, num(a, "creditScore"),
                    rating.isEmpty() ? "unrated" : rating,
                    (int) num(a, "jobsDone"), num(a, "earnedUsd"), num(a, "drawnUsd")));
        }
        return out;
    }

    /** GET /api/vault/onchain — returns "MiniVault $price · HF x.xx" or null on failure. */
    public String fetchVaultLine() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/vault/onchain"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("User-Agent", "LedgermindViz/0.1.0 (Paper plugin)")
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) return null;

            JsonElement parsed = JsonParser.parseString(res.body());
            if (!parsed.isJsonObject()) return null;
            JsonObject root = parsed.getAsJsonObject();
            if (!root.has("state") || root.get("state").isJsonNull()) return null;

            double price = num(root.getAsJsonObject("state"), "priceUsd");
            JsonObject pos = root.has("position") && root.get("position").isJsonObject()
                    ? root.getAsJsonObject("position") : null;
            String hf = (pos != null && pos.has("healthFactor") && !pos.get("healthFactor").isJsonNull())
                    ? String.format("%.2f", pos.get("healthFactor").getAsDouble())
                    : "-";
            return "MiniVault  $" + (long) price + "  ·  HF " + hf;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static double num(JsonObject o, String k) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : 0d;
        } catch (RuntimeException ex) {
            return 0d;
        }
    }
}
