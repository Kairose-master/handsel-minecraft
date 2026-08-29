package com.handsel.viz;

import java.util.List;

/**
 * One Handsel OFFICE — a desk of specialist agents with a pipeline already
 * wired between them (the platform's {@code hire_office} / {@code office_roster}),
 * rendered as a floor you can walk through.
 *
 * <p><b>Where the shape comes from.</b> The public API this plugin reads has no
 * office endpoint (only {@code /api/tasks}, {@code /api/world/agents} and the
 * vault gauge), so an office's STRUCTURE — who sits at which desk, what feeds
 * what, which MCP server a role is wired to — is what the server owner pastes
 * into {@code config.yml} from their own roster. The LIVE numbers on each desk
 * are still the API's: the desk's agent is matched by name against the agent
 * feed, and its lamp is lit by that agent's real job status. Nothing on a desk
 * is invented — a role whose agent is not in the feed says so.
 *
 * @param desks in pipeline order (stage, then the order they were written)
 */
public record Office(String id, String name, String tagline, double budgetUsd,
                     String scope, String source, List<Desk> desks) {

    /**
     * One role at one desk.
     *
     * @param role     the role id used by {@code after}/{@code reviews} wiring
     * @param title    what the role actually delivers
     * @param agent    platform agent NAME, matched against the agent feed; may be blank
     * @param priceUsd this step's slice of the office budget; 0 when unpriced
     * @param after    upstream roles feeding this desk (never dangling — the parser drops those)
     * @param reviews  the role this desk reviews (a REVISE goes back to it), or blank
     * @param stage    pipeline depth, 0 for a desk that waits on nobody — drives the layout
     */
    public record Desk(String role, String title, String agent, double priceUsd,
                       List<String> after, String reviews,
                       String mcpTool, String mcpServer, int stage) {

        /** True when this role calls a real MCP server rather than running as a plain agent. */
        public boolean wired() { return !mcpTool.isEmpty() || !mcpServer.isEmpty(); }

        /** "web_search_exa on https://mcp.exa.ai/mcp" — blank when the role isn't wired. */
        public String wiring() {
            if (!wired()) return "";
            if (mcpTool.isEmpty()) return mcpServer;
            return mcpServer.isEmpty() ? mcpTool : mcpTool + " on " + mcpServer;
        }
    }

    /** How many pipeline stages deep this office is (at least 1). */
    public int stages() {
        int max = 0;
        for (Desk d : desks) max = Math.max(max, d.stage());
        return max + 1;
    }

    /** The desks sitting at one stage, left to right. */
    public List<Desk> atStage(int stage) {
        return desks.stream().filter(d -> d.stage() == stage).toList();
    }

    /** The widest stage — how many desks stand side by side at the busiest row. */
    public int width() {
        int max = 1;
        for (int s = 0; s < stages(); s++) max = Math.max(max, atStage(s).size());
        return max;
    }

    public Desk desk(String role) {
        for (Desk d : desks) if (d.role().equals(role)) return d;
        return null;
    }
}
