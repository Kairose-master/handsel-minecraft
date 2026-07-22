package com.ledgermind.viz;

/** One open bounty from GET /api/tasks. */
public record Job(String id, String title, double rewardUsd, String status,
                  String verification, String requesterLabel) {}
