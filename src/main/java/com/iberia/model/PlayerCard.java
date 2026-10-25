package com.iberia.model;

/**
 * Player cards are referenced everywhere by a compact integer id so that game
 * state stays trivially serialisable:
 * <ul>
 *     <li>{@code 0..47}: City card (id == city id)</li>
 *     <li>{@code 100..114}: Event card (100 + {@link EventType#ordinal()})</li>
 *     <li>{@code 200..205}: Epidemic card</li>
 * </ul>
 */
public final class PlayerCard {
    public static final int EVENT_BASE = 100;
    public static final int EPIDEMIC_BASE = 200;

    public enum Kind {CITY, EVENT, EPIDEMIC}

    private PlayerCard() {
    }

    public static Kind kind(int card) {
        if (card >= EPIDEMIC_BASE) return Kind.EPIDEMIC;
        if (card >= EVENT_BASE) return Kind.EVENT;
        return Kind.CITY;
    }

    public static boolean isCity(int card) {
        return card >= 0 && card < EVENT_BASE;
    }

    public static boolean isEvent(int card) {
        return card >= EVENT_BASE && card < EPIDEMIC_BASE;
    }

    public static boolean isEpidemic(int card) {
        return card >= EPIDEMIC_BASE;
    }

    public static int ofEvent(EventType e) {
        return EVENT_BASE + e.ordinal();
    }

    public static int epidemic(int n) {
        return EPIDEMIC_BASE + n;
    }

    public static EventType event(int card) {
        return EventType.values()[card - EVENT_BASE];
    }

    public static City city(int card) {
        return Board.get().city(card);
    }

    /** Disease colour of a City card, or {@code null} for other cards. */
    public static DiseaseType color(int card) {
        return isCity(card) ? city(card).color : null;
    }

    public static String name(int card) {
        switch (kind(card)) {
            case CITY:
                return city(card).name;
            case EVENT:
                return event(card).title;
            default:
                return "Epidemia";
        }
    }
}
