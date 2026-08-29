package com.handsel.viz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The office roster is the plugin's second uncontrolled input.
 *
 * The first is the API (see {@link HandselClientTest}); this one is a human
 * with a YAML file, copying a roster desk by desk, and the failure mode is the
 * same one: this parser runs inside enable and inside {@code /lm reload}, and a
 * parser that throws on one mistyped role takes down the office — or the whole
 * plugin — instead of the role. So the tests below are mostly about what a
 * hand-edited file gets wrong.
 *
 * The other half is the LAYOUT. {@code after}/{@code reviews} decide which desk
 * stands behind which, and a cable drawn to a desk that does not exist claims
 * the office has a step it does not have — so dangling wiring is dropped here,
 * where it can be asserted, rather than in the builder, where it cannot.
 */
class OfficePlanTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static Office office(Object... roles) {
        return OfficePlan.parse("desk", map("name", "Desk", "roles", List.of(roles)));
    }

    @Nested
    @DisplayName("a desk")
    class Desks {
        @Test
        @DisplayName("reads the fields a desk actually shows")
        void readsARole() {
            Office o = office(map("role", "aws", "title", "AWS read", "agent", "Worker Bot Alpha",
                    "price-usd", 1.71, "mcp-tool", "aws___search_documentation",
                    "mcp-server", "https://knowledge-mcp.global.api.aws"));
            assertNotNull(o);
            Office.Desk d = o.desks().get(0);
            assertEquals("aws", d.role());
            assertEquals("AWS read", d.title());
            assertEquals("Worker Bot Alpha", d.agent());
            assertEquals(1.71, d.priceUsd());
            assertTrue(d.wired());
            assertEquals("aws___search_documentation on https://knowledge-mcp.global.api.aws", d.wiring());
        }

        @Test
        @DisplayName("needs nothing but a name — everything else is optional")
        void everythingElseIsOptional() {
            Office.Desk d = office(map("role", "editor")).desks().get(0);
            assertEquals("editor", d.title());   // falls back to the role id
            assertEquals("", d.agent());
            assertEquals(0.0, d.priceUsd());
            assertTrue(d.after().isEmpty());
            assertFalse(d.wired());
            assertEquals("", d.wiring());
        }

        @Test
        @DisplayName("takes a title-only role and names it from the title")
        void derivesTheRoleFromATitle() {
            Office.Desk d = office(map("title", "Red Team — the memo")).desks().get(0);
            assertEquals("red-team-the-memo", d.role());
        }

        @Test
        @DisplayName("matches role ids however they were typed")
        void normalisesRoleIds() {
            Office o = office(map("role", "Red Team"), map("role", "editor", "after", "RED_TEAM"));
            assertEquals(List.of("red-team"), o.desk("editor").after());
        }

        @Test
        @DisplayName("reads a price written as text, and shrugs off one that isn't a number")
        void toleratesPricesAsText() {
            assertEquals(2.4, office(map("role", "a", "price-usd", "2.40")).desks().get(0).priceUsd());
            assertEquals(2.0, office(map("role", "a", "price-usd", "$2")).desks().get(0).priceUsd());
            assertEquals(0.0, office(map("role", "a", "price-usd", "free")).desks().get(0).priceUsd());
        }

        @Test
        @DisplayName("keeps the first of two desks with the same id")
        void dropsDuplicateRoles() {
            // Both would answer to the same wiring; a second desk with the same
            // id is a copy-paste, not a second person.
            Office o = office(map("role", "editor", "title", "first"),
                    map("role", "editor", "title", "second"));
            assertEquals(1, o.desks().size());
            assertEquals("first", o.desks().get(0).title());
        }

        @Test
        @DisplayName("skips a junk entry instead of losing the office")
        void skipsJunkEntries() {
            Office o = OfficePlan.parse("desk", map("roles",
                    java.util.Arrays.asList(map("role", "a"), "nonsense", 42, null, map("role", "b"))));
            assertNotNull(o);
            assertEquals(2, o.desks().size());
        }

        @Test
        @DisplayName("stops at a room's worth of desks")
        void capsTheFloor() {
            List<Object> many = new java.util.ArrayList<>();
            for (int i = 0; i < OfficePlan.MAX_DESKS + 5; i++) many.add(map("role", "r" + i));
            Office o = OfficePlan.parse("desk", map("roles", many));
            assertEquals(OfficePlan.MAX_DESKS, o.desks().size());
        }
    }

    @Nested
    @DisplayName("the wiring")
    class Wiring {
        @Test
        @DisplayName("accepts a list or the comma-separated line people also write")
        void afterAcceptsBothForms() {
            Office listed = office(map("role", "a"), map("role", "b"),
                    map("role", "c", "after", List.of("a", "b")));
            Office inline = office(map("role", "a"), map("role", "b"),
                    map("role", "c", "after", "a, b"));
            assertEquals(List.of("a", "b"), listed.desk("c").after());
            assertEquals(listed.desk("c").after(), inline.desk("c").after());
        }

        @Test
        @DisplayName("drops a dependency on a desk that isn't in the office")
        void dropsDanglingDependencies() {
            // The builder draws a cable per dependency. One pointing at a desk
            // that was never hired would draw a step this office does not have.
            Office o = office(map("role", "editor", "after", List.of("researcher", "ghost")),
                    map("role", "researcher"));
            assertEquals(List.of("researcher"), o.desk("editor").after());
        }

        @Test
        @DisplayName("drops a desk that depends on itself")
        void dropsSelfDependencies() {
            Office o = office(map("role", "editor", "after", List.of("editor")));
            assertTrue(o.desk("editor").after().isEmpty());
            assertEquals(0, o.desk("editor").stage());
        }

        @Test
        @DisplayName("keeps a reviewer only when it has something to review")
        void dropsADanglingReviewer() {
            assertEquals("", office(map("role", "red-team", "reviews", "nobody")).desk("red-team").reviews());
        }
    }

    @Nested
    @DisplayName("the layout")
    class Layout {
        @Test
        @DisplayName("puts a desk that waits on nobody at the front")
        void rootsAreStageZero() {
            Office o = office(map("role", "a"), map("role", "b"));
            assertEquals(0, o.desk("a").stage());
            assertEquals(0, o.desk("b").stage());
            assertEquals(1, o.stages());
            assertEquals(2, o.width());
        }

        @Test
        @DisplayName("ranks a fan-in behind everything that feeds it")
        void fanInSitsBehindItsInputs() {
            // The due-diligence desk: three reads, then the partner's memo,
            // then a red team that reviews the memo.
            Office o = office(
                    map("role", "commercial"), map("role", "financial"), map("role", "legal"),
                    map("role", "partner", "after", List.of("commercial", "financial", "legal")),
                    map("role", "red-team", "reviews", "partner"));
            assertEquals(0, o.desk("commercial").stage());
            assertEquals(1, o.desk("partner").stage());
            assertEquals(2, o.desk("red-team").stage(), "a reviewer sits behind what it reviews");
            assertEquals(3, o.stages());
            assertEquals(3, o.width());
            assertEquals(List.of("commercial", "financial", "legal"),
                    o.atStage(0).stream().map(Office.Desk::role).toList());
        }

        @Test
        @DisplayName("a reviewer waits on its subject even without an explicit after")
        void reviewImpliesADependency() {
            Office o = office(map("role", "copywriter"), map("role", "claim-check", "reviews", "copywriter"));
            assertEquals(List.of("copywriter"), o.desk("claim-check").after());
        }

        @Test
        @DisplayName("lays a roster wired into a loop out flat instead of hanging")
        void survivesACycle() {
            // Nobody means to write one, but `after` is a free-text field and a
            // pair of roles pointing at each other must not spin the ranker.
            Office o = office(map("role", "a", "after", List.of("b")), map("role", "b", "after", List.of("a")));
            assertNotNull(o);
            assertEquals(2, o.desks().size());
            assertTrue(o.stages() <= o.desks().size());
        }

        @Test
        @DisplayName("orders desks front to back, config order within a row")
        void desksComeOutInPipelineOrder() {
            Office o = office(map("role", "editor", "after", List.of("research")),
                    map("role", "research"), map("role", "notes"));
            assertEquals(List.of("research", "notes", "editor"),
                    o.desks().stream().map(Office.Desk::role).toList());
        }
    }

    @Nested
    @DisplayName("the offices block")
    class Offices {
        @Test
        @DisplayName("an office with nobody at a desk is not an office")
        void refusesAnEmptyOffice() {
            assertNull(OfficePlan.parse("desk", map("name", "Desk")));
            assertNull(OfficePlan.parse("desk", map("roles", List.of())));
            assertNull(OfficePlan.parse("desk", map("roles", "a research desk")));
            assertNull(OfficePlan.parse("desk", map("roles", List.of(map("agent", "someone")))));
        }

        @Test
        @DisplayName("returns null, never throws, when there is nothing to read")
        void toleratesNothing() {
            assertNull(OfficePlan.parse(null, map()));
            assertNull(OfficePlan.parse("", map()));
            assertNull(OfficePlan.parse("desk", null));
            assertTrue(OfficePlan.parseAll(null).isEmpty());
            assertTrue(OfficePlan.parseAll(map("broken", "not a map")).isEmpty());
        }

        @Test
        @DisplayName("names itself after its id when the roster left the name out")
        void fallsBackToTheId() {
            Office o = OfficePlan.parse("research-desk", map("roles", List.of(map("role", "a"))));
            assertEquals("research-desk", o.name());
            assertEquals("research-desk", o.id());
        }

        @Test
        @DisplayName("one unusable office does not cost the others")
        void keepsTheReadableOffices() {
            var all = OfficePlan.parseAll(map(
                    "broken", map("name", "no roles here"),
                    "research", map("roles", List.of(map("role", "researcher"))),
                    "growth", map("roles", List.of(map("role", "copywriter")))));
            assertEquals(List.of("research", "growth"), all.stream().map(Office::id).toList());
        }

        @Test
        @DisplayName("carries the budget, scope and shared source the desks refer to")
        void readsTheOfficeHeader() {
            Office o = OfficePlan.parse("cloud", map(
                    "name", "Cloud Options Desk", "tagline", "three vendors, one comparison",
                    "budget-usd", 12, "scope", "a webhook receiver", "source", "https://example.test/brief",
                    "roles", List.of(map("role", "aws"))));
            assertEquals("Cloud Options Desk", o.name());
            assertEquals(12.0, o.budgetUsd());
            assertEquals("three vendors, one comparison", o.tagline());
            assertEquals("a webhook receiver", o.scope());
            assertEquals("https://example.test/brief", o.source());
        }
    }
}
