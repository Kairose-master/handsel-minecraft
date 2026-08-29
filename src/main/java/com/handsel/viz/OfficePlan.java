package com.handsel.viz;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the {@code offices:} block of config.yml into {@link Office} records.
 *
 * <p>This is the second input the plugin does not control (the first is the
 * API — see {@link HandselClient}), and it gets the same treatment: it is
 * hand-written YAML, pasted role by role from a real roster, so every field is
 * optional and nothing here throws. A typo in one role costs that role, not the
 * office; an office that cannot be read at all is skipped with a log line
 * rather than taking the plugin's enable path down with it.
 *
 * <p>It also decides the LAYOUT: {@code after}/{@code reviews} form a small
 * dependency graph, and each desk's {@code stage} is its depth in it, so the
 * office builds itself front-to-back in the order work actually flows.
 */
public final class OfficePlan {

    /** A floor big enough to walk; past this the room stops being readable. */
    static final int MAX_DESKS = 16;

    private OfficePlan() {}

    /** Every readable office under the {@code offices:} map, in config order. */
    public static List<Office> parseAll(Map<?, ?> raw) {
        List<Office> out = new ArrayList<>();
        if (raw == null) return out;
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (e.getKey() == null) continue;
            Office office = parse(String.valueOf(e.getKey()), asMap(e.getValue()));
            if (office != null) out.add(office);
        }
        return out;
    }

    /** One office, or null when it has no usable desk (an empty office is not an office). */
    public static Office parse(String id, Map<?, ?> raw) {
        if (id == null || id.isBlank() || raw == null) return null;
        List<Office.Desk> desks = staged(desks(raw.get("roles")));
        if (desks.isEmpty()) return null;
        String name = str(raw, "name");
        return new Office(id.trim(),
                name.isEmpty() ? id.trim() : name,
                str(raw, "tagline"),
                num(raw, "budget-usd"),
                str(raw, "scope"),
                str(raw, "source"),
                desks);
    }

    /** The roles list — anything that isn't a role-shaped map is skipped, not fatal. */
    private static List<Office.Desk> desks(Object rolesRaw) {
        List<Office.Desk> out = new ArrayList<>();
        if (!(rolesRaw instanceof List<?> list)) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (Object e : list) {
            Map<?, ?> m = asMap(e);
            if (m == null) continue;
            String title = str(m, "title");
            String role = slug(str(m, "role"));
            if (role.isEmpty()) role = slug(title);   // a role written as a title only still gets a desk
            if (role.isEmpty() || !seen.add(role)) continue;  // nameless, or a duplicate of one above
            out.add(new Office.Desk(role,
                    title.isEmpty() ? role : title,
                    str(m, "agent"),
                    num(m, "price-usd"),
                    slugs(m.get("after")),
                    slug(str(m, "reviews")),
                    str(m, "mcp-tool"),
                    str(m, "mcp-server"),
                    0));
            if (out.size() >= MAX_DESKS) break;
        }
        return out;
    }

    /**
     * Drop wiring that points at nobody, then rank each desk by pipeline depth.
     *
     * <p>A dangling {@code after} would draw a cable to a desk that isn't there,
     * which is worse than no cable: it says the office has a step it does not
     * have. Depth is the longest path to a desk, bounded by the desk count so a
     * roster someone wired into a cycle lays out flat instead of looping here.
     */
    private static List<Office.Desk> staged(List<Office.Desk> desks) {
        if (desks.isEmpty()) return List.of();
        Set<String> ids = new LinkedHashSet<>();
        for (Office.Desk d : desks) ids.add(d.role());

        List<Office.Desk> clean = new ArrayList<>();
        for (Office.Desk d : desks) {
            List<String> after = new ArrayList<>();
            for (String up : d.after()) {
                if (ids.contains(up) && !up.equals(d.role()) && !after.contains(up)) after.add(up);
            }
            String reviews = ids.contains(d.reviews()) && !d.reviews().equals(d.role()) ? d.reviews() : "";
            if (!reviews.isEmpty() && !after.contains(reviews)) after.add(reviews); // a reviewer waits on its subject
            clean.add(new Office.Desk(d.role(), d.title(), d.agent(), d.priceUsd(),
                    List.copyOf(after), reviews, d.mcpTool(), d.mcpServer(), 0));
        }

        Map<String, Integer> stage = new HashMap<>();
        int cap = clean.size() - 1;
        for (int pass = 0; pass <= clean.size(); pass++) {
            boolean changed = false;
            for (Office.Desk d : clean) {
                int s = 0;
                for (String up : d.after()) s = Math.max(s, stage.getOrDefault(up, 0) + 1);
                s = Math.min(s, cap);
                if (s != stage.getOrDefault(d.role(), 0)) { stage.put(d.role(), s); changed = true; }
            }
            if (!changed) break;
        }

        List<Office.Desk> out = new ArrayList<>();
        for (Office.Desk d : clean) {
            out.add(new Office.Desk(d.role(), d.title(), d.agent(), d.priceUsd(),
                    d.after(), d.reviews(), d.mcpTool(), d.mcpServer(),
                    stage.getOrDefault(d.role(), 0)));
        }
        out.sort(java.util.Comparator.comparingInt(Office.Desk::stage));  // stable: config order within a stage
        return List.copyOf(out);
    }

    // --- field readers: every one of them has to survive a hand-edited file ---

    private static Map<?, ?> asMap(Object o) {
        return o instanceof Map<?, ?> m ? m : null;
    }

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    /** A price written as {@code 2.40}, as {@code "2.40"}, or as nonsense — never an exception. */
    private static double num(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.doubleValue();
        try {
            return v == null ? 0d : Double.parseDouble(String.valueOf(v).replace("$", "").trim());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    /** {@code after} accepts a YAML list or a comma-separated string — people write both. */
    private static List<String> slugs(Object v) {
        List<String> out = new ArrayList<>();
        if (v == null) return out;
        if (v instanceof List<?> list) {
            for (Object e : list) {
                String s = slug(e == null ? "" : String.valueOf(e));
                if (!s.isEmpty()) out.add(s);
            }
            return out;
        }
        for (String part : String.valueOf(v).split(",")) {
            String s = slug(part);
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** Role ids are matched against each other, so they are normalised on the way in. */
    static String slug(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '-').replace('_', '-');
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-') sb.append(c);
        }
        for (int i = sb.length() - 1; i > 0; i--) {
            if (sb.charAt(i) == '-' && sb.charAt(i - 1) == '-') sb.deleteCharAt(i);  // "a — b" -> "a-b"
        }
        while (sb.length() > 0 && sb.charAt(0) == '-') sb.deleteCharAt(0);
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') sb.setLength(sb.length() - 1);
        return sb.toString();
    }
}
