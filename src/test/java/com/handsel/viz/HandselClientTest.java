package com.handsel.viz;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The plugin's contract with an API it does not control.
 *
 * Everything else here draws blocks. This is the only code whose input comes
 * from somewhere else, and the boundary has one job: a server that answers
 * something unexpected must produce an empty board, never a stack trace on a
 * scheduled task. A Bukkit scheduler task that throws stops repeating — so a
 * parser that dies on one bad field does not degrade the display, it ends it,
 * and the server owner sees a board frozen on stale data with no error they
 * can connect to it.
 *
 * Which is why the tests below are mostly about malformed input rather than
 * the happy path.
 */
class HandselClientTest {

    private static List<Job> jobs(String json) {
        return HandselClient.parseJobs(JsonParser.parseString(json));
    }

    private static List<Agent> agents(String json) {
        return HandselClient.parseAgents(JsonParser.parseString(json));
    }

    @Nested
    @DisplayName("the task feed")
    class Jobs {
        @Test
        @DisplayName("reads the fields the board actually draws")
        void readsAJob() {
            List<Job> out = jobs("""
                {"tasks":[{"id":"327","title":"Fix the webhook","rewardUsd":5,
                           "status":"Open","verification":"repo-ci",
                           "requesterLabel":"0xab..cd","workerLabel":"",
                           "requesterName":"house","workerName":""}]}""");
            assertEquals(1, out.size());
            Job job = out.get(0);
            assertEquals("327", job.id());
            assertEquals("Fix the webhook", job.title());
            assertEquals(5.0, job.rewardUsd());
            assertEquals("Open", job.status());
            assertEquals("house", job.requesterName());
        }

        @Test
        @DisplayName("survives a deployment that predates a field")
        void toleratesMissingFields() {
            // Job's own javadoc says requesterName/workerName are absent on
            // older deployments. The plugin ships separately from the API, so
            // "older" is the normal case, not an edge one.
            List<Job> out = jobs("""
                {"tasks":[{"id":"1","title":"t","status":"Open"}]}""");
            assertEquals(1, out.size());
            assertEquals("", out.get(0).requesterName());
            assertEquals("", out.get(0).verification());
            assertEquals(0.0, out.get(0).rewardUsd());
        }

        @Test
        @DisplayName("treats an explicit null like an absent field")
        void toleratesNulls() {
            List<Job> out = jobs("""
                {"tasks":[{"id":"1","title":null,"rewardUsd":null,"status":"Open"}]}""");
            assertEquals("", out.get(0).title());
            assertEquals(0.0, out.get(0).rewardUsd());
        }

        @Test
        @DisplayName("skips a non-object entry instead of failing the batch")
        void skipsJunkEntries() {
            // One bad row must not cost the other rows. The board showing four
            // of five jobs is a far better failure than the board showing none.
            List<Job> out = jobs("""
                {"tasks":[{"id":"1","status":"Open"},"nonsense",42,{"id":"2","status":"Open"}]}""");
            assertEquals(2, out.size());
        }

        @Test
        @DisplayName("returns empty, never throws, on a shape it did not expect")
        void toleratesWrongShapes() {
            for (String body : new String[] { "{}", "[]", "\"a string\"", "null",
                                              "{\"tasks\":null}", "{\"tasks\":{}}", "{\"tasks\":5}" }) {
                assertTrue(jobs(body).isEmpty(), "expected empty for " + body);
            }
        }

        @Test
        @DisplayName("a reward that is not a number is zero, not an exception")
        void toleratesNonNumericReward() {
            assertEquals(0.0, jobs("""
                {"tasks":[{"id":"1","rewardUsd":"free","status":"Open"}]}""").get(0).rewardUsd());
        }
    }

    @Nested
    @DisplayName("the agent feed")
    class Agents {
        @Test
        @DisplayName("reads an agent")
        void readsAnAgent() {
            List<Agent> out = agents("""
                {"agents":[{"name":"miner-01","creditScore":712,"creditRating":"A",
                            "jobsDone":9,"earnedUsd":41.5,"drawnUsd":10}]}""");
            assertEquals(1, out.size());
            assertEquals("miner-01", out.get(0).name());
            assertEquals(712.0, out.get(0).creditScore());
            assertEquals(9, out.get(0).jobsDone());
        }

        @Test
        @DisplayName("drops a nameless agent, because NPCs are keyed by name")
        void dropsNamelessAgents() {
            // Keeping it would put an unaddressable NPC in the village.
            assertTrue(agents("""
                {"agents":[{"name":"","creditScore":500}]}""").isEmpty());
            assertTrue(agents("""
                {"agents":[{"creditScore":500}]}""").isEmpty());
        }

        @Test
        @DisplayName("calls a missing rating 'unrated' rather than blank")
        void defaultsTheRating() {
            assertEquals("unrated", agents("""
                {"agents":[{"name":"a"}]}""").get(0).creditRating());
        }

        @Test
        @DisplayName("returns empty on any shape it did not expect")
        void toleratesWrongShapes() {
            for (String body : new String[] { "{}", "[]", "null", "{\"agents\":null}" }) {
                assertTrue(agents(body).isEmpty(), "expected empty for " + body);
            }
        }
    }

    @Nested
    @DisplayName("the connect token")
    class Tokens {
        private static String encode(String json) {
            return Base64.getUrlEncoder().encodeToString(json.getBytes());
        }

        @Test
        @DisplayName("decodes agent id, secret and platform url")
        void decodes() {
            var token = HandselClient.decodeToken(
                    encode("{\"a\":\"agt_1\",\"s\":\"sec\",\"u\":\"https://example.test\"}"));
            assertNotNull(token);
            assertEquals("agt_1", token.agentId());
            assertEquals("sec", token.secret());
            assertEquals("https://example.test", token.platformUrl());
        }

        @Test
        @DisplayName("allows a token with no url — the plugin falls back to its config")
        void urlIsOptional() {
            var token = HandselClient.decodeToken(encode("{\"a\":\"agt_1\",\"s\":\"sec\"}"));
            assertNotNull(token);
            assertNull(token.platformUrl());
        }

        @Test
        @DisplayName("returns null rather than throwing on anything unusable")
        void rejectsGarbage() {
            // A player pastes whatever is on their clipboard. Every one of
            // these reaches this method, and the command handler's only
            // contract is that it gets null back instead of an exception.
            assertNull(HandselClient.decodeToken(null));
            assertNull(HandselClient.decodeToken(""));
            assertNull(HandselClient.decodeToken("   "));
            assertNull(HandselClient.decodeToken("not-base64!!!"));
            assertNull(HandselClient.decodeToken(encode("not json")));
            assertNull(HandselClient.decodeToken(encode("[]")));
            assertNull(HandselClient.decodeToken(encode("{\"a\":\"only-id\"}")));
            assertNull(HandselClient.decodeToken(encode("{\"s\":\"only-secret\"}")));
        }

        @Test
        @DisplayName("tolerates the whitespace a copy-paste brings with it")
        void trimsWhitespace() {
            assertNotNull(HandselClient.decodeToken(
                    "  " + encode("{\"a\":\"agt_1\",\"s\":\"sec\"}") + "\n"));
        }
    }

    @Nested
    @DisplayName("the base url")
    class BaseUrl {
        @Test
        @DisplayName("strips trailing slashes, so paths never double up")
        void stripsTrailingSlashes() {
            // Every request concatenates baseUrl + "/api/...", so one stray
            // slash in a config file becomes a 404 on every single call.
            assertEquals("https://x.test", new HandselClient("https://x.test/").baseUrl());
            assertEquals("https://x.test", new HandselClient("https://x.test///").baseUrl());
            assertEquals("https://x.test", new HandselClient("https://x.test").baseUrl());
        }
    }
}
