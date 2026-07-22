package com.ledgermind.viz;

/**
 * One agent from GET /api/world/agents.
 * {@code jobsDone}/{@code earnedUsd} are the §14 optional extras — 0 when the
 * deployed API predates them, which the village renders as "no payouts yet".
 */
public record Agent(String name, double creditScore, String creditRating,
                    int jobsDone, double earnedUsd) {}
