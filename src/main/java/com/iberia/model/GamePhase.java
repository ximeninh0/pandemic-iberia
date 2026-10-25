package com.iberia.model;

/** Turn phases of the deterministic game state machine. */
public enum GamePhase {
    /** Players choose start cities (and the Nurse places her token). */
    SETUP("Preparação"),
    /** Current player performs up to 4 actions. */
    ACTIONS("Ações"),
    /** Current player must draw 2 Player cards. */
    DRAW("Comprar cartas"),
    /** Two Epidemics were drawn together; the second waits so Events can be played in between. */
    EPIDEMIC_PENDING("Epidemia pendente"),
    /** Current player flips Infection cards one at a time. */
    INFECTION("Infecção"),
    GAME_OVER("Fim de jogo");

    public final String title;

    GamePhase(String title) {
        this.title = title;
    }
}
