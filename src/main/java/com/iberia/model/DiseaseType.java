package com.iberia.model;

/**
 * The four diseases of Pandemic Iberia. Colour mapping follows the rulebook's
 * Historical Diseases cards: black = Malaria, red = Typhus, blue = Cholera,
 * yellow = Yellow Fever.
 */
public enum DiseaseType {
    BLACK("Malária", "preta", "#2b2b2e", "#f2ead8"),
    RED("Tifo", "vermelha", "#b3261e", "#fff4ea"),
    BLUE("Cólera", "azul", "#1f5fa8", "#f2f6ff"),
    YELLOW("Febre Amarela", "amarela", "#e3b51b", "#2b2010");

    /** Disease name shown in the UI. */
    public final String diseaseName;
    /** Colour name (feminine, as in "carta azul"). */
    public final String colorName;
    /** Main colour of cubes, cards and city markers. */
    public final String hex;
    /** Readable text colour on top of {@link #hex}. */
    public final String textHex;

    DiseaseType(String diseaseName, String colorName, String hex, String textHex) {
        this.diseaseName = diseaseName;
        this.colorName = colorName;
        this.hex = hex;
        this.textHex = textHex;
    }

    public static final int COUNT = 4;
}
