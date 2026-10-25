package com.iberia.model;

/**
 * There is exactly one hospital per disease colour. A hospital is either in the
 * supply ({@link #NOT_BUILT}) or in a city of its colour, which is the only place
 * where that disease can be researched. {@link GameState#hospitals} stores the city
 * of each hospital indexed by {@link DiseaseType#ordinal()}.
 */
public final class Hospital {
    public static final int NOT_BUILT = -1;

    private Hospital() {
    }

    public static boolean isBuilt(GameState s, DiseaseType color) {
        return s.hospitals[color.ordinal()] != NOT_BUILT;
    }

    public static int cityOf(GameState s, DiseaseType color) {
        return s.hospitals[color.ordinal()];
    }

    /** Colour of the hospital standing in {@code city}, or {@code null}. */
    public static DiseaseType in(GameState s, int city) {
        for (DiseaseType d : DiseaseType.values()) if (s.hospitals[d.ordinal()] == city) return d;
        return null;
    }
}
