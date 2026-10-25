package com.iberia.model;

/**
 * Infection cards are identified by the city they infect (48 cards, one per
 * city). The decks in {@link GameState} store these ids directly.
 */
public final class InfectionCard {
    private InfectionCard() {
    }

    public static City city(int card) {
        return Board.get().city(card);
    }

    public static DiseaseType color(int card) {
        return city(card).color;
    }

    public static String name(int card) {
        return city(card).name;
    }
}
