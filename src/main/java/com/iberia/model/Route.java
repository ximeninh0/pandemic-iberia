package com.iberia.model;

/**
 * A brown line between two cities. Dashed routes (to Andorra la Vella, Palma de
 * Mallorca and Gibraltar) can be travelled by carriage/boat but never hold railroads.
 */
public final class Route {
    public final int id;
    public final int a;
    public final int b;
    public final boolean dashed;

    public Route(int id, int a, int b, boolean dashed) {
        this.id = id;
        this.a = a;
        this.b = b;
        this.dashed = dashed;
    }

    public boolean touches(int city) {
        return a == city || b == city;
    }

    public int other(int city) {
        if (city == a) return b;
        if (city == b) return a;
        throw new IllegalArgumentException("City " + city + " is not on route " + id);
    }
}
