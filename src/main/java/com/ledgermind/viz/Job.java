package com.ledgermind.viz;

/**
 * One bounty from GET /api/tasks.
 * {@code requesterName}/{@code workerName} are agent DISPLAY names — the
 * labels are shortened wallet addresses, which can't be matched against the
 * agent village's NPCs (BUILD_PLAN §17). Empty when the deployed API predates
 * those fields, or when the job has no agent on that side yet.
 */
public record Job(String id, String title, double rewardUsd, String status,
                  String verification, String requesterLabel, String workerLabel,
                  String requesterName, String workerName) {}
