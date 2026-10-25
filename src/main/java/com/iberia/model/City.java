package com.iberia.model;

/** Immutable board city. Coordinates are in board space (see {@link Board#WIDTH}). */
public final class City {
    public final int id;
    public final String name;
    public final DiseaseType color;
    public final boolean port;
    public final double x;
    public final double y;
    /** Approximate founding year (negative = BC), used to pick the first player. */
    public final int founded;

    public City(int id, String name, DiseaseType color, boolean port, double x, double y, int founded) {
        this.id = id;
        this.name = name;
        this.color = color;
        this.port = port;
        this.x = x;
        this.y = y;
        this.founded = founded;
    }

    public String foundedLabel() {
        return founded < 0 ? (-founded) + " a.C." : founded + " d.C.";
    }

    @Override
    public String toString() {
        return name;
    }
}
