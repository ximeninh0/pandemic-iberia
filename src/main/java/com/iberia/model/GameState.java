package com.iberia.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Complete canonical game state. Plain data only: all rules live in the engine
 * package, so this object can be serialised as-is and broadcast to clients
 * (with the hidden deck order redacted).
 */
public final class GameState {
    public static final int[] INFECTION_RATE_TRACK = {2, 2, 2, 3, 3, 4, 4};
    public static final int MAX_OUTBREAKS = 8;
    public static final int HAND_LIMIT = 7;
    public static final int CUBES_PER_COLOR = 24;
    public static final int RAILROAD_TOKENS = 20;
    public static final int PURIFICATION_TOKENS = 14;
    public static final int ACTIONS_PER_TURN = 4;
    public static final int RESEARCH_CARDS = 5;
    private static final int MAX_LOG = 600;

    /** Disease cubes to place in one city, processed by the epidemic resolver. */
    public static final class CubeTask {
        public int city;
        public DiseaseType color;
        public int amount;
        /** Yellow Fever spill-over cube: never spreads to further ports. */
        public boolean secondary;
        /** Cholera: only placed if the city has no cubes at all. */
        public boolean onlyIfEmpty;
        /** True once Malaria's "2 cubes instead of 1" rule has been evaluated. */
        public boolean adjusted;
        /** Cubes from an outbreak (logged differently). */
        public boolean fromOutbreak;
        public int placed;
        public int prevented;

        public CubeTask() {
        }

        public CubeTask(int city, DiseaseType color, int amount) {
            this.city = city;
            this.color = color;
            this.amount = amount;
        }
    }

    /** In-progress resolution of one Infection or Epidemic card (resumable after choices). */
    public static final class Resolution {
        public List<CubeTask> queue = new ArrayList<>();
        public List<Integer> outbroken = new ArrayList<>();
        public boolean epidemic;
        public int sourceCity = -1;
    }

    public GameSettings settings = new GameSettings();
    public GamePhase phase = GamePhase.SETUP;
    public List<Player> players = new ArrayList<>();
    public int currentPlayer;
    public int actionsLeft;
    public int turn;
    public boolean railwaymanDoubleUsed;

    public int infectionRateIndex;
    public int outbreaks;

    /** cubes[city][color] */
    public int[][] cubes;
    public int[] cubeSupply = new int[DiseaseType.COUNT];
    public boolean[] researched = new boolean[DiseaseType.COUNT];
    /** City of each hospital by colour, or {@link Hospital#NOT_BUILT}. */
    public int[] hospitals = new int[DiseaseType.COUNT];
    /** rails[route] */
    public boolean[] rails;
    public int railSupply = RAILROAD_TOKENS;
    /** purification[region] */
    public int[] purification;
    public int purificationSupply = PURIFICATION_TOKENS;
    public int nurseRegion = -1;

    public List<Integer> playerDeck = new ArrayList<>();
    public List<Integer> playerDiscard = new ArrayList<>();
    public List<Integer> removedCards = new ArrayList<>();
    public int playerDeckCount;
    public List<Integer> infectionDeck = new ArrayList<>();
    public List<Integer> infectionDiscard = new ArrayList<>();
    public int infectionDeckCount;

    public int epidemicsPending;
    public int infectionsLeft;
    public boolean goodSeasonQueued;
    public boolean infectFromBottom;

    public Resolution resolution;
    public List<PendingChoice> choices = new ArrayList<>();
    public PendingConsent consent;

    public boolean won;
    public String gameOverReason;

    public List<LogEntry> log = new ArrayList<>();
    public int logSeq;
    public List<Integer> lastDrawn = new ArrayList<>();
    public int lastInfectedCity = -1;
    /** True on client copies: deck contents removed, only counts kept. */
    public boolean redacted;
    public long version;

    public GameState() {
    }

    // ---------------------------------------------------------------- queries

    public Player player(int id) {
        return players.get(id);
    }

    public Player current() {
        return players.get(currentPlayer);
    }

    public int infectionRate() {
        return INFECTION_RATE_TRACK[Math.min(infectionRateIndex, INFECTION_RATE_TRACK.length - 1)];
    }

    public int cubesAt(int city, DiseaseType color) {
        return cubes[city][color.ordinal()];
    }

    public int totalCubes(int city) {
        int t = 0;
        for (int v : cubes[city]) t += v;
        return t;
    }

    public int playerDeckSize() {
        return redacted ? playerDeckCount : playerDeck.size();
    }

    public int infectionDeckSize() {
        return redacted ? infectionDeckCount : infectionDeck.size();
    }

    public int researchedCount() {
        int n = 0;
        for (boolean b : researched) if (b) n++;
        return n;
    }

    /** The choice currently blocking the game, or {@code null}. */
    public PendingChoice activeChoice() {
        return choices.isEmpty() ? null : choices.get(0);
    }

    public boolean isOver() {
        return phase == GamePhase.GAME_OVER;
    }

    public int playerWithRole(Role r) {
        for (Player p : players) if (p.role == r) return p.id;
        return -1;
    }

    // ---------------------------------------------------------------- mutation helpers

    public void log(LogEntry.Kind kind, String text) {
        log.add(new LogEntry(++logSeq, turn, kind, text));
        if (log.size() > MAX_LOG) log.subList(0, log.size() - MAX_LOG).clear();
    }

    public void syncCounts() {
        if (!redacted) {
            playerDeckCount = playerDeck.size();
            infectionDeckCount = infectionDeck.size();
        }
    }
}
