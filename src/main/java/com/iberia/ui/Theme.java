package com.iberia.ui;

import com.iberia.model.DiseaseType;
import com.iberia.model.Role;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;

import java.util.List;

/** 19th-century sepia palette and fonts shared by every view. */
public final class Theme {
    private Theme() {
    }

    public static final Color PARCHMENT = Color.web("#ecdfc1");
    public static final Color PARCHMENT_LIGHT = Color.web("#f5ecd6");
    public static final Color PARCHMENT_DARK = Color.web("#d6c194");
    public static final Color INK = Color.web("#3b2a1a");
    public static final Color INK_SOFT = Color.web("#6b5236");
    public static final Color SEA = Color.web("#b4c3ad");
    public static final Color SEA_DEEP = Color.web("#93a998");
    public static final Color LAND = Color.web("#f0e2bf");
    public static final Color LAND_SHADE = Color.web("#dcc38f");
    public static final Color PORTUGAL = Color.web("#e9c98d");
    public static final Color ROUTE = Color.web("#7a5a36");
    public static final Color RAIL = Color.web("#2b2119");
    public static final Color GOLD = Color.web("#c9a227");
    public static final Color HIGHLIGHT = Color.web("#ffcf33");
    public static final Color WATER = Color.web("#3d8ec9");
    public static final Color DANGER = Color.web("#a3261c");

    public static final String SERIF = pickFamily("Palatino Linotype", "Book Antiqua", "Georgia", "Times New Roman");

    private static String pickFamily(String... families) {
        List<String> installed = Font.getFamilies();
        for (String f : families) if (installed.contains(f)) return f;
        return "Serif";
    }

    public static Font serif(double size) {
        return Font.font(SERIF, FontWeight.NORMAL, size);
    }

    public static Font serifBold(double size) {
        return Font.font(SERIF, FontWeight.BOLD, size);
    }

    public static Font serifItalic(double size) {
        return Font.font(SERIF, FontWeight.NORMAL, FontPosture.ITALIC, size);
    }

    public static Color color(DiseaseType d) {
        return Color.web(d.hex);
    }

    public static Color text(DiseaseType d) {
        return Color.web(d.textHex);
    }

    public static Color pawn(Role r) {
        return Color.web(r.pawnHex);
    }

    public static String css(Color c) {
        return String.format("#%02x%02x%02x", (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255));
    }
}
