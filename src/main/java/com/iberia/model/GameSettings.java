package com.iberia.model;

/** Host-selected options for a game. */
public final class GameSettings {
    /** 4 = Introductory, 5 = Standard, 6 = Heroic. */
    public int epidemics = 4;
    /** Historical Diseases challenge, indexed by {@link DiseaseType#ordinal()}. */
    public boolean[] historical = new boolean[DiseaseType.COUNT];
    /** 0 = random seed. */
    public long seed;

    public GameSettings() {
    }

    public GameSettings copy() {
        GameSettings s = new GameSettings();
        s.epidemics = epidemics;
        s.historical = historical.clone();
        s.seed = seed;
        return s;
    }

    public boolean historical(DiseaseType d) {
        return historical != null && historical[d.ordinal()];
    }

    public String difficultyName() {
        switch (epidemics) {
            case 4:
                return "Introdutório";
            case 5:
                return "Padrão";
            default:
                return "Heroico";
        }
    }
}
