package com.iberia.engine;

import com.iberia.model.Board;
import com.iberia.model.DiseaseType;
import com.iberia.model.EventType;
import com.iberia.model.GamePhase;
import com.iberia.model.GameSettings;
import com.iberia.model.GameState;
import com.iberia.model.Hospital;
import com.iberia.model.LogEntry;
import com.iberia.model.PendingChoice;
import com.iberia.model.Player;
import com.iberia.model.PlayerCard;
import com.iberia.model.Role;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Builds the initial state following the rulebook setup (steps 1–7). */
public final class GameSetup {
    private GameSetup() {
    }

    /** Events shuffled into the Player deck and starting hand size, indexed by player count. */
    static final int[] EVENTS_BY_PLAYERS = {0, 0, 4, 5, 6, 8};
    static final int[] HAND_BY_PLAYERS = {0, 0, 4, 3, 2, 2};

    /**
     * @param names seat names (2–5)
     * @param roles requested role per seat, {@code null} entries are assigned randomly
     */
    public static GameState create(List<String> names, List<Role> roles, GameSettings settings, Random rng) {
        int n = names.size();
        if (n < 2 || n > 5) throw new IllegalArgumentException("Pandemic Iberia é para 2 a 5 jogadores.");
        Board b = Board.get();
        GameState s = new GameState();
        s.settings = settings.copy();
        s.phase = GamePhase.SETUP;

        // roles
        List<Role> pool = new ArrayList<>(Arrays.asList(Role.values()));
        for (Role r : roles) if (r != null) pool.remove(r);
        Collections.shuffle(pool, rng);
        for (int i = 0; i < n; i++) {
            Role r = i < roles.size() ? roles.get(i) : null;
            if (r == null) r = pool.remove(0);
            s.players.add(new Player(i, names.get(i), r));
        }

        // board pieces (step 1-2)
        s.cubes = new int[b.cityCount()][DiseaseType.COUNT];
        Arrays.fill(s.cubeSupply, GameState.CUBES_PER_COLOR);
        Arrays.fill(s.hospitals, Hospital.NOT_BUILT);
        s.rails = new boolean[b.routes.size()];
        s.purification = new int[b.regions.size()];
        s.railSupply = GameState.RAILROAD_TOKENS;
        s.purificationSupply = GameState.PURIFICATION_TOKENS;
        s.infectionRateIndex = 0;
        s.outbreaks = 0;

        // step 3: infect 9 cities (3x3, 3x2, 3x1)
        for (int c = 0; c < b.cityCount(); c++) s.infectionDeck.add(c);
        Collections.shuffle(s.infectionDeck, rng);
        s.log(LogEntry.Kind.SYSTEM, "Nova partida — dificuldade " + s.settings.difficultyName()
                + " (" + s.settings.epidemics + " Epidemias).");
        for (int cubes = 3; cubes >= 1; cubes--) {
            for (int k = 0; k < 3; k++) {
                int city = s.infectionDeck.remove(0);
                DiseaseType col = b.city(city).color;
                s.cubes[city][col.ordinal()] += cubes;
                s.cubeSupply[col.ordinal()] -= cubes;
                s.infectionDiscard.add(city);
                s.log(LogEntry.Kind.INFECTION, "Infecção inicial: " + b.city(city).name + " recebe " + cubes
                        + (cubes == 1 ? " cubo " : " cubos ") + col.colorName + ".");
            }
        }

        // step 5: events + starting hands
        List<EventType> events = new ArrayList<>(Arrays.asList(EventType.values()));
        Collections.shuffle(events, rng);
        List<Integer> cards = new ArrayList<>();
        for (int c = 0; c < b.cityCount(); c++) cards.add(c);
        for (int i = 0; i < EVENTS_BY_PLAYERS[n]; i++) cards.add(PlayerCard.ofEvent(events.get(i)));
        Collections.shuffle(cards, rng);
        for (Player p : s.players) {
            for (int i = 0; i < HAND_BY_PLAYERS[n]; i++) p.hand.add(cards.remove(0));
        }

        // step 6: split into piles, one Epidemic in each, smaller piles at the bottom
        int piles = s.settings.epidemics;
        List<List<Integer>> stacks = new ArrayList<>();
        int base = cards.size() / piles, extra = cards.size() % piles;
        int idx = 0;
        for (int i = 0; i < piles; i++) {
            int size = base + (i < extra ? 1 : 0);
            List<Integer> pile = new ArrayList<>(cards.subList(idx, idx + size));
            idx += size;
            pile.add(PlayerCard.epidemic(i));
            Collections.shuffle(pile, rng);
            stacks.add(pile);
        }
        for (List<Integer> pile : stacks) s.playerDeck.addAll(pile);

        // step 7 is resolved once everyone picked a start city
        for (Player p : s.players) {
            List<Integer> opts = new ArrayList<>();
            for (int card : p.hand) if (PlayerCard.isCity(card)) opts.add(card);
            if (opts.isEmpty()) for (int c = 0; c < b.cityCount(); c++) opts.add(c);
            s.choices.add(new PendingChoice(PendingChoice.Type.START_CITY, p.id, opts,
                    p.name + ": escolha a cidade inicial entre as cidades das suas cartas."));
            s.log(LogEntry.Kind.SYSTEM, p.name + " será o(a) " + p.role.title + ".");
        }
        s.syncCounts();
        return s;
    }
}
