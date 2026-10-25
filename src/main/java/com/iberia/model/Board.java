package com.iberia.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Static topology of the Iberian Peninsula board: 48 cities, 81 routes and the
 * regions enclosed by them. Regions are derived at start-up as the bounded faces
 * of the planar route graph (the unbounded outer face is not a region).
 */
public final class Board {
    /** Board coordinate space (cities, coastline and regions share it). */
    public static final double WIDTH = 1078;
    public static final double HEIGHT = 788;

    private static final Board INSTANCE = new Board();

    public final List<City> cities;
    public final List<Route> routes;
    public final List<Region> regions;

    private final int[][] neighbors;
    private final int[][] routesOfCity;
    private final int[][] regionsOfCity;
    private final Route[][] routeMatrix;

    public static Board get() {
        return INSTANCE;
    }

    private Board() {
        this.cities = Collections.unmodifiableList(BoardData.cities());
        this.routes = Collections.unmodifiableList(BoardData.routes(cities));
        int n = cities.size();
        routeMatrix = new Route[n][n];
        List<List<Integer>> nb = new ArrayList<>();
        List<List<Integer>> rc = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            nb.add(new ArrayList<>());
            rc.add(new ArrayList<>());
        }
        for (Route r : routes) {
            routeMatrix[r.a][r.b] = r;
            routeMatrix[r.b][r.a] = r;
            nb.get(r.a).add(r.b);
            nb.get(r.b).add(r.a);
            rc.get(r.a).add(r.id);
            rc.get(r.b).add(r.id);
        }
        neighbors = new int[n][];
        routesOfCity = new int[n][];
        for (int i = 0; i < n; i++) {
            neighbors[i] = nb.get(i).stream().mapToInt(Integer::intValue).toArray();
            routesOfCity[i] = rc.get(i).stream().mapToInt(Integer::intValue).toArray();
        }
        this.regions = Collections.unmodifiableList(computeRegions());
        List<List<Integer>> rg = new ArrayList<>();
        for (int i = 0; i < n; i++) rg.add(new ArrayList<>());
        for (Region r : regions) for (int c : r.cities) rg.get(c).add(r.id);
        regionsOfCity = new int[n][];
        for (int i = 0; i < n; i++) regionsOfCity[i] = rg.get(i).stream().mapToInt(Integer::intValue).toArray();
    }

    public City city(int id) {
        return cities.get(id);
    }

    public int cityCount() {
        return cities.size();
    }

    public Route route(int id) {
        return routes.get(id);
    }

    /** Route between two cities or {@code null} if they are not connected. */
    public Route routeBetween(int a, int b) {
        return routeMatrix[a][b];
    }

    public boolean adjacent(int a, int b) {
        return a != b && routeMatrix[a][b] != null;
    }

    public int[] neighbors(int city) {
        return neighbors[city];
    }

    public int[] routesOf(int city) {
        return routesOfCity[city];
    }

    public int[] regionsOf(int city) {
        return regionsOfCity[city];
    }

    public Region region(int id) {
        return regions.get(id);
    }

    public boolean regionTouches(int region, int city) {
        return regions.get(region).borders(city);
    }

    /** Every city sharing at least one region with {@code city} (excluding itself). */
    public Set<Integer> citiesInAdjacentRegions(int city) {
        Set<Integer> out = new HashSet<>();
        for (int r : regionsOfCity[city]) for (int c : regions.get(r).cities) if (c != city) out.add(c);
        return out;
    }

    /** Cities at exactly two connections from {@code city}. */
    public Set<Integer> citiesAtDistanceTwo(int city) {
        Set<Integer> one = new HashSet<>();
        for (int c : neighbors[city]) one.add(c);
        Set<Integer> two = new HashSet<>();
        for (int c : one) for (int d : neighbors[c]) if (d != city && !one.contains(d)) two.add(d);
        return two;
    }

    public City byName(String name) {
        for (City c : cities) if (c.name.equalsIgnoreCase(name)) return c;
        throw new IllegalArgumentException("Unknown city " + name);
    }

    public String regionLabel(int region) {
        StringBuilder sb = new StringBuilder();
        for (int c : regions.get(region).cities) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(cities.get(c).name);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ regions

    private List<Region> computeRegions() {
        int n = cities.size();
        // neighbours sorted by angle around each city
        int[][] sorted = new int[n][];
        for (int i = 0; i < n; i++) {
            final City ci = cities.get(i);
            sorted[i] = Arrays.stream(neighbors[i]).boxed()
                    .sorted(Comparator.comparingDouble(j -> Math.atan2(cities.get(j).y - ci.y, cities.get(j).x - ci.x)))
                    .mapToInt(Integer::intValue).toArray();
        }
        boolean[][] used = new boolean[n][n];
        List<int[]> faces = new ArrayList<>();
        for (Route r : routes) {
            int[][] starts = {{r.a, r.b}, {r.b, r.a}};
            for (int[] s : starts) {
                if (used[s[0]][s[1]]) continue;
                List<Integer> face = new ArrayList<>();
                int u = s[0], v = s[1];
                while (!used[u][v]) {
                    used[u][v] = true;
                    face.add(u);
                    int[] nb = sorted[v];
                    int idx = indexOf(nb, u);
                    int next = nb[(idx - 1 + nb.length) % nb.length];
                    u = v;
                    v = next;
                }
                faces.add(face.stream().mapToInt(Integer::intValue).toArray());
            }
        }
        // the outer face is the one with the most negative signed area
        int outer = 0;
        for (int i = 1; i < faces.size(); i++) if (signedArea(faces.get(i)) < signedArea(faces.get(outer))) outer = i;
        faces.remove(outer);

        List<double[]> anchors = new ArrayList<>();
        for (int[] f : faces) anchors.add(labelPoint(f));
        Integer[] order = new Integer[faces.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, Comparator.<Integer>comparingDouble(i -> Math.round(anchors.get(i)[1] / 10.0))
                .thenComparingDouble(i -> anchors.get(i)[0]));
        List<Region> out = new ArrayList<>();
        for (int k = 0; k < order.length; k++) {
            int i = order[k];
            out.add(new Region(k, faces.get(i), anchors.get(i)[0], anchors.get(i)[1]));
        }
        return out;
    }

    private static int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        throw new IllegalStateException();
    }

    private double signedArea(int[] f) {
        double a = 0;
        for (int i = 0; i < f.length; i++) {
            City p = cities.get(f[i]);
            City q = cities.get(f[(i + 1) % f.length]);
            a += p.x * q.y - q.x * p.y;
        }
        return a / 2;
    }

    private double[] labelPoint(int[] f) {
        double area = signedArea(f);
        double cx = 0, cy = 0;
        for (int i = 0; i < f.length; i++) {
            City p = cities.get(f[i]);
            City q = cities.get(f[(i + 1) % f.length]);
            double cross = p.x * q.y - q.x * p.y;
            cx += (p.x + q.x) * cross;
            cy += (p.y + q.y) * cross;
        }
        cx /= (6 * area);
        cy /= (6 * area);
        if (inside(f, cx, cy)) return new double[]{cx, cy};
        double ax = 0, ay = 0;
        for (int c : f) {
            ax += cities.get(c).x;
            ay += cities.get(c).y;
        }
        return new double[]{ax / f.length, ay / f.length};
    }

    private boolean inside(int[] f, double x, double y) {
        boolean in = false;
        for (int i = 0, j = f.length - 1; i < f.length; j = i++) {
            City a = cities.get(f[i]);
            City b = cities.get(f[j]);
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) in = !in;
        }
        return in;
    }
}
