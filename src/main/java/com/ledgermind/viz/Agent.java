package com.ledgermind.viz;

/**
 * One agent from GET /api/world/agents.
 * {@code jobsDone}/{@code earnedUsd}/{@code drawnUsd} are the optional extras —
 * 0 when the deployed API predates them.
 */
public record Agent(String name, double creditScore, String creditRating,
                    int jobsDone, double earnedUsd, double drawnUsd) {}
