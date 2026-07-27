package com.handsel.viz;

import org.bukkit.Location;

/**
 * One town = one Handsel account's village, plus its power-plant spectacle
 * and the token that scopes its agent feed. Several towns coexist, road-linked
 * into one city; each renders only its own account's agents.
 */
public final class Town {
    final AgentVillage village;
    final Spectacle spectacle;                 // nullable when spectacle is off
    final HandselClient.Token token;        // nullable → global leaderboard
    final String label;

    Town(String label, AgentVillage village, Spectacle spectacle, HandselClient.Token token) {
        this.label = label;
        this.village = village;
        this.spectacle = spectacle;
        this.token = token;
    }

    Location center() { return village.center(); }
}
