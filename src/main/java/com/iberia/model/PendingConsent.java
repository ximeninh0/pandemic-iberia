package com.iberia.model;

import java.util.ArrayList;
import java.util.List;

/**
 * An action that affects other players (Share Knowledge, passengers, moving
 * someone's pawn, swapping cards) waits here until every affected seat agrees.
 */
public final class PendingConsent {
    public int actor;
    public GameAction action;
    public List<Integer> awaiting = new ArrayList<>();
    public String description;

    public PendingConsent() {
    }

    public PendingConsent(int actor, GameAction action, List<Integer> awaiting, String description) {
        this.actor = actor;
        this.action = action;
        this.awaiting = new ArrayList<>(awaiting);
        this.description = description;
    }
}
