package com.iberia.model;

/**
 * A region is an area of the board enclosed by routes (a bounded face of the
 * planar route graph). Purification tokens and the Nurse's prevention token
 * are placed in regions; a city is adjacent to every region it borders.
 */
public final class Region {
    public final int id;
    /** Boundary cities in polygon order. */
    public final int[] cities;
    /** Anchor point used to draw tokens. */
    public final double cx;
    public final double cy;

    public Region(int id, int[] cities, double cx, double cy) {
        this.id = id;
        this.cities = cities;
        this.cx = cx;
        this.cy = cy;
    }

    public boolean borders(int city) {
        for (int c : cities) if (c == city) return true;
        return false;
    }
}
