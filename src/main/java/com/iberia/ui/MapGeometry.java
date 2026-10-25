package com.iberia.ui;

import com.google.gson.reflect.TypeToken;
import com.iberia.network.Json;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Static cartography in board coordinates: coastline (traced from the peninsula's
 * outline, stored in {@code coast.json}), the Portugal–Spain border, rivers and
 * mountain ranges used purely for decoration.
 */
public final class MapGeometry {
    private MapGeometry() {
    }

    /** Preferred visible part of the board (x0, y0, x1, y1). */
    public static final double VIEW_X0 = 58, VIEW_Y0 = 78, VIEW_X1 = 925, VIEW_Y1 = 758;
    /** Limits of the drawn cartography; the view may grow up to them to fill wide or tall windows. */
    public static final double MIN_X = -320, MAX_X = 1480, MIN_Y = -120, MAX_Y = 760;

    private static final class Poly {
        String name;
        int[] points;
    }

    private static List<double[]> coast;

    /** Land polygons, each as a flat [x0, y0, x1, y1, ...] array (first = mainland). */
    public static synchronized List<double[]> coast() {
        if (coast == null) {
            try (InputStream in = MapGeometry.class.getResourceAsStream("/com/iberia/coast.json")) {
                List<Poly> polys = Json.GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
                        new TypeToken<List<Poly>>() {
                        }.getType());
                List<double[]> out = new ArrayList<>();
                for (Poly p : polys) {
                    double[] d = new double[p.points.length];
                    for (int i = 0; i < d.length; i++) d[i] = p.points[i];
                    if ("iberia".equals(p.name)) {
                        // the traced outline stops at the original board frame: push France beyond it
                        for (int i = 0; i < d.length; i += 2) {
                            if (d[i] >= 1044) d[i] = 1800;
                            if (d[i + 1] <= 40) d[i + 1] = -400;
                        }
                    }
                    out.add(d);
                }
                coast = Collections.unmodifiableList(out);
            } catch (Exception e) {
                coast = Collections.emptyList();
            }
        }
        return coast;
    }

    /** Approximate Portugal–Spain border, from the Minho mouth to the Guadiana mouth. */
    public static final double[] PORTUGAL_BORDER = {
            110, 256, 140, 262, 172, 262, 204, 258, 232, 266, 262, 280, 270, 300, 252, 318, 242, 350,
            238, 388, 232, 424, 212, 446, 208, 470, 216, 492, 222, 512, 212, 540, 205, 562, 198, 592,
            193, 616, 199, 643
    };

    /** Portugal as a polygon reaching into the ocean; clipped to the land when drawn. */
    public static final double[] PORTUGAL_AREA = {
            110, 256, 140, 262, 172, 262, 204, 258, 232, 266, 262, 280, 270, 300, 252, 318, 242, 350,
            238, 388, 232, 424, 212, 446, 208, 470, 216, 492, 222, 512, 212, 540, 205, 562, 198, 592,
            193, 616, 199, 643, 200, 720, 40, 720, 40, 252
    };

    public static final double[][] RIVERS = {
            // Duero / Douro
            {440, 278, 400, 292, 362, 300, 320, 318, 282, 312, 246, 300, 205, 312, 165, 322, 130, 330},
            // Tajo / Tejo
            {556, 360, 520, 392, 470, 412, 420, 420, 372, 432, 320, 446, 270, 452, 232, 474, 190, 498, 140, 508, 104, 516},
            // Guadiana
            {500, 455, 450, 470, 400, 476, 340, 492, 280, 506, 236, 520, 214, 548, 205, 590, 201, 640},
            // Guadalquivir
            {470, 548, 430, 570, 380, 584, 330, 598, 296, 620, 276, 650, 262, 680},
            // Ebro
            {404, 196, 450, 222, 500, 246, 552, 272, 600, 292, 646, 316, 690, 342, 712, 352},
            // Júcar
            {520, 380, 540, 420, 560, 470, 600, 492, 630, 490},
            // Miño / Minho
            {230, 214, 200, 232, 160, 248, 120, 254},
    };

    /** Mountain clusters: centre x, centre y, width, glyph count. */
    public static final double[][] MOUNTAINS = {
            {300, 168, 90, 7}, {380, 172, 70, 5}, {460, 182, 50, 4},          // Cordillera Cantábrica
            {610, 218, 70, 5}, {690, 200, 80, 6}, {780, 222, 60, 5},          // Pirineos
            {330, 372, 70, 6},                                                  // Sistema Central
            {540, 340, 60, 5}, {566, 452, 40, 3},                              // Sistema Ibérico
            {370, 456, 50, 4},                                                  // Montes de Toledo
            {330, 548, 80, 6}, {420, 540, 60, 5},                              // Sierra Morena
            {470, 600, 60, 5}, {520, 620, 50, 4}, {410, 640, 40, 3},          // Sistemas Béticos
            {250, 390, 40, 3}, {200, 330, 40, 3},                              // Serra da Estrela / Gerês
    };

    /** Decorative map labels: text, x, y, size, rotation (degrees). */
    public static final Object[][] LABELS = {
            {"OCÉANO  ATLÁNTICO", 66.0, 470.0, 15.0, -90.0},
            {"MAR  CANTÁBRICO", 380.0, 104.0, 13.0, 0.0},
            {"MAR  MEDITERRÁNEO", 862.0, 586.0, 14.0, -18.0},
            {"PORTUGAL", 150.0, 470.0, 13.0, -78.0},
            {"ESPAÑA", 470.0, 455.0, 22.0, 0.0},
            {"FRANCIA", 760.0, 140.0, 14.0, 0.0},
            {"ISLAS  BALEARES", 830.0, 395.0, 9.5, 0.0},
    };
}
