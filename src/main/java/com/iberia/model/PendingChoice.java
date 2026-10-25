package com.iberia.model;

import java.util.ArrayList;
import java.util.List;

/** A decision the engine is waiting for before the game can continue. */
public final class PendingChoice {
    public enum Type {
        /** Setup: pick a start city (options = allowed cities). */
        START_CITY,
        /** Nurse must place her prevention token (options = regions). */
        NURSE_TOKEN,
        /** Player is over the 7-card hand limit (options = cards in hand). */
        DISCARD,
        /** Current player chooses the region losing a purification token (options = regions). */
        PURIFICATION_REMOVAL,
        /** Royal Academy Scientist reorders the top Player cards (options = cards, top first). */
        SCIENTIST_ORDER,
        /** Government Mobilization: one free move or skip. */
        FREE_MOVE
    }

    public Type type;
    public int player;
    public List<Integer> options = new ArrayList<>();
    public String prompt;

    public PendingChoice() {
    }

    public PendingChoice(Type type, int player, List<Integer> options, String prompt) {
        this.type = type;
        this.player = player;
        this.options = new ArrayList<>(options);
        this.prompt = prompt;
    }
}
