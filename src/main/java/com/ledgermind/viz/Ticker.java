package com.ledgermind.viz;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The bottom-of-screen live event ticker (actionbar). Economy events pushed in
 * by the poller — a job filling, a new bounty, an agent's score rising — cycle
 * across everyone's actionbar; when nothing's happening it rotates through a
 * couple of ambient stat lines so the bar is never dead. MAIN THREAD ONLY.
 */
public final class Ticker {

    /** A headline and the time it should stop being shown. */
    private record Headline(String text, long expiresAt) {}

    private static final int MAX = 8;
    private final Deque<Headline> events = new ArrayDeque<>();
    private final String[] ambient = { "", "" };
    private int cursor;

    /** Push a fresh economy event to the front of the ticker. */
    public void push(String text, long now) {
        events.addFirst(new Headline(text, now + 12_000)); // visible ~12s
        while (events.size() > MAX) events.removeLast();
    }

    /** Ambient fallbacks refreshed each poll (top agent, open-job count, vault). */
    public void setAmbient(String topLine, String vaultLine) {
        ambient[0] = topLine == null ? "" : topLine;
        ambient[1] = vaultLine == null ? "" : vaultLine;
    }

    /** Called on a short timer: show the next headline to everyone. */
    public void tick(long now) {
        events.removeIf(h -> h.expiresAt() < now);
        if (Bukkit.getOnlinePlayers().isEmpty()) return;

        String text;
        if (!events.isEmpty()) {
            // Rotate through the live events.
            Headline[] arr = events.toArray(new Headline[0]);
            text = arr[cursor % arr.length].text();
        } else {
            String a = ambient[cursor % ambient.length];
            if (a.isEmpty()) return;
            text = a;
        }
        cursor++;

        Component msg = legacy("§6⛏ §r" + text);
        for (Player p : Bukkit.getOnlinePlayers()) p.sendActionBar(msg);
    }

    public void clear() {
        events.clear();
        Component blank = Component.text("");
        for (Player p : Bukkit.getOnlinePlayers()) p.sendActionBar(blank);
    }

    private static Component legacy(String s) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacySection().deserialize(s);
    }
}
