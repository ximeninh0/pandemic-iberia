package com.iberia.engine;

import com.iberia.model.ActionType;
import com.iberia.model.Board;
import com.iberia.model.DiseaseType;
import com.iberia.model.EventType;
import com.iberia.model.GameAction;
import com.iberia.model.GamePhase;
import com.iberia.model.GameSettings;
import com.iberia.model.GameState;
import com.iberia.model.PendingChoice;
import com.iberia.model.Player;
import com.iberia.model.PlayerCard;
import com.iberia.model.Role;
import com.iberia.network.Json;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineTest {
    private final Board b = Board.get();

    private int city(String name) {
        return b.byName(name).id;
    }

    private static void ok(GameEngine e, GameAction a) {
        GameEngine.Result r = e.apply(a);
        assertTrue(r.ok, () -> a.type + " rejected: " + r.error);
    }

    private static void rejected(GameEngine e, GameAction a) {
        assertFalse(e.apply(a).ok, () -> a.type + " should have been rejected");
    }

    /** New game with setup finished and an empty, predictable board on player 0's action phase. */
    private GameEngine game(GameSettings settings, Role... roles) {
        settings.seed = 1234;
        List<String> names = new ArrayList<>();
        List<Role> rl = new ArrayList<>();
        for (int i = 0; i < Math.max(2, roles.length); i++) {
            names.add("P" + i);
            rl.add(i < roles.length ? roles[i] : null);
        }
        GameEngine e = GameEngine.newGame(names, rl, settings);
        GameState s = e.state();
        for (Player p : s.players) {
            PendingChoice c = s.choices.stream().filter(x -> x.player == p.id && x.type == PendingChoice.Type.START_CITY)
                    .findFirst().orElseThrow();
            ok(e, GameAction.of(ActionType.CHOOSE_START_CITY, p.id).city(c.options.get(0)));
        }
        while (RuleValidator.blockingChoice(s) != null) {
            PendingChoice c = RuleValidator.blockingChoice(s);
            ok(e, GameAction.of(ActionType.CHOOSE, c.player).region(c.options.get(0)));
        }
        assertEquals(GamePhase.ACTIONS, s.phase);
        // clean, deterministic board
        for (int c = 0; c < b.cityCount(); c++) {
            for (DiseaseType d : DiseaseType.values()) {
                s.cubeSupply[d.ordinal()] += s.cubes[c][d.ordinal()];
                s.cubes[c][d.ordinal()] = 0;
            }
        }
        s.currentPlayer = 0;
        s.actionsLeft = 4;
        s.nurseRegion = -1;
        for (Player p : s.players) p.hand.clear();
        return e;
    }

    private GameEngine game(Role... roles) {
        return game(new GameSettings(), roles);
    }

    private void infectNow(GameEngine e, String cityName) {
        GameState s = e.state();
        s.infectionDeck.remove(Integer.valueOf(city(cityName)));
        s.infectionDiscard.remove(Integer.valueOf(city(cityName)));
        s.infectionDeck.add(0, city(cityName));
        s.phase = GamePhase.INFECTION;
        s.infectionsLeft = 1;
        ok(e, GameAction.of(ActionType.INFECT_NEXT, s.currentPlayer));
    }

    private int regionWith(String... names) {
        for (int r = 0; r < b.regions.size(); r++) {
            boolean all = true;
            for (String n : names) all &= b.regionTouches(r, city(n));
            if (all) return r;
        }
        throw new IllegalArgumentException(Arrays.toString(names));
    }

    // ---------------------------------------------------------------- setup

    @Test
    void setupFollowsTheRulebook() {
        GameSettings st = new GameSettings();
        st.epidemics = 5;
        st.seed = 7;
        GameEngine e = GameEngine.newGame(Arrays.asList("A", "B", "C"), Arrays.asList(null, null, null), st);
        GameState s = e.state();
        int placed = 0;
        for (int[] c : s.cubes) for (int v : c) placed += v;
        assertEquals(18, placed);
        assertEquals(9, s.infectionDiscard.size());
        assertEquals(39, s.infectionDeck.size());
        for (Player p : s.players) assertEquals(3, p.hand.size());
        // 48 cities + 5 events - 9 dealt + 5 epidemics
        assertEquals(48 + 5 - 9 + 5, s.playerDeck.size());
        assertEquals(5, s.playerDeck.stream().filter(PlayerCard::isEpidemic).count());
        assertEquals(3, s.choices.size());
        assertEquals(GamePhase.SETUP, s.phase);
    }

    // ---------------------------------------------------------------- infections

    @Test
    void purificationTokenAbsorbsTheInfection() {
        GameEngine e = game();
        GameState s = e.state();
        int r = regionWith("Albufeira", "Lisboa", "Huelva", "Évora");
        s.purification[r] = 1;
        s.purificationSupply = 13;
        infectNow(e, "Albufeira");
        assertEquals(0, s.cubesAt(city("Albufeira"), DiseaseType.BLUE));
        assertEquals(0, s.purification[r]);
        assertEquals(14, s.purificationSupply);
    }

    @Test
    void currentPlayerChoosesWhichTokenIsRemoved() {
        GameEngine e = game();
        GameState s = e.state();
        int r1 = regionWith("Cuenca", "Valencia", "Albacete");
        int r2 = regionWith("Madrid", "Cuenca", "Albacete", "Ciudad Real");
        s.purification[r1] = 1;
        s.purification[r2] = 2;
        s.purificationSupply = 11;
        infectNow(e, "Albacete");
        PendingChoice c = RuleValidator.blockingChoice(s);
        assertNotNull(c);
        assertEquals(PendingChoice.Type.PURIFICATION_REMOVAL, c.type);
        rejected(e, GameAction.of(ActionType.INFECT_NEXT, 0));
        ok(e, GameAction.of(ActionType.CHOOSE, 0).region(r2));
        assertEquals(1, s.purification[r1]);
        assertEquals(1, s.purification[r2]);
        assertEquals(0, s.cubesAt(city("Albacete"), DiseaseType.YELLOW));
        assertNull(s.resolution);
    }

    @Test
    void chainOutbreakFromTheRulebook() {
        GameEngine e = game();
        GameState s = e.state();
        s.cubes[city("Cuenca")][DiseaseType.YELLOW.ordinal()] = 3;
        s.cubes[city("Valencia")][DiseaseType.YELLOW.ordinal()] = 3;
        s.cubeSupply[DiseaseType.YELLOW.ordinal()] -= 6;
        infectNow(e, "Cuenca");
        assertEquals(2, s.outbreaks);
        assertEquals(1, s.cubesAt(city("Madrid"), DiseaseType.YELLOW));
        assertEquals(1, s.cubesAt(city("Teruel"), DiseaseType.YELLOW));
        assertEquals(2, s.cubesAt(city("Albacete"), DiseaseType.YELLOW));
        assertEquals(1, s.cubesAt(city("Tarragona"), DiseaseType.YELLOW));
        assertEquals(1, s.cubesAt(city("Alicante"), DiseaseType.YELLOW));
        assertEquals(1, s.cubesAt(city("Palma de Mallorca"), DiseaseType.YELLOW));
        assertEquals(3, s.cubesAt(city("Cuenca"), DiseaseType.YELLOW));
        assertEquals(3, s.cubesAt(city("Valencia"), DiseaseType.YELLOW));
    }

    @Test
    void nurseBlocksCubesAroundHerToken() {
        GameEngine e = game(Role.NURSE, Role.SAILOR);
        GameState s = e.state();
        s.nurseRegion = regionWith("Albufeira", "Lisboa", "Huelva", "Évora");
        infectNow(e, "Huelva");
        assertEquals(0, s.cubesAt(city("Huelva"), DiseaseType.BLACK));
        infectNow(e, "Sevilla");
        assertEquals(1, s.cubesAt(city("Sevilla"), DiseaseType.BLACK));
    }

    @Test
    void epidemicIncreasesInfectsAndIntensifies() {
        GameEngine e = game();
        GameState s = e.state();
        int sevilla = city("Sevilla");
        s.cubes[sevilla][DiseaseType.BLACK.ordinal()] = 1;
        s.infectionDeck.remove(Integer.valueOf(sevilla));
        s.infectionDiscard.remove(Integer.valueOf(sevilla));
        s.infectionDeck.add(sevilla);
        int discardBefore = s.infectionDiscard.size();
        s.playerDeck.removeIf(PlayerCard::isEpidemic);
        s.playerDeck.add(0, PlayerCard.epidemic(0));
        s.phase = GamePhase.DRAW;
        ok(e, GameAction.of(ActionType.DRAW_CARDS, 0));
        assertEquals(1, s.infectionRateIndex);
        assertEquals(3, s.cubesAt(sevilla, DiseaseType.BLACK));
        assertEquals(1, s.outbreaks);
        assertEquals(0, s.infectionDiscard.size());
        assertTrue(s.infectionDeck.subList(0, discardBefore + 1).contains(sevilla));
        assertEquals(GamePhase.INFECTION, s.phase);
        assertEquals(1, s.player(0).hand.size());
    }

    @Test
    void choleraOutbreakReachesEmptyCitiesTwoAway() {
        GameSettings st = new GameSettings();
        st.historical[DiseaseType.BLUE.ordinal()] = true;
        GameEngine e = game(st);
        GameState s = e.state();
        s.cubes[city("Albufeira")][DiseaseType.BLUE.ordinal()] = 3;
        s.cubes[city("Coimbra")][DiseaseType.BLUE.ordinal()] = 1;
        s.cubes[city("Sevilla")][DiseaseType.BLACK.ordinal()] = 1;
        infectNow(e, "Albufeira");
        assertEquals(1, s.cubesAt(city("Lisboa"), DiseaseType.BLUE));
        assertEquals(1, s.cubesAt(city("Huelva"), DiseaseType.BLUE));
        assertEquals(1, s.cubesAt(city("Porto"), DiseaseType.BLUE));
        assertEquals(1, s.cubesAt(city("Évora"), DiseaseType.BLUE));
        assertEquals(1, s.cubesAt(city("Coimbra"), DiseaseType.BLUE));
        assertEquals(0, s.cubesAt(city("Sevilla"), DiseaseType.BLUE));
    }

    @Test
    void yellowFeverSpillsIntoAdjacentPorts() {
        GameSettings st = new GameSettings();
        st.historical[DiseaseType.YELLOW.ordinal()] = true;
        GameEngine e = game(st);
        GameState s = e.state();
        infectNow(e, "Alicante");
        assertEquals(1, s.cubesAt(city("Alicante"), DiseaseType.YELLOW));
        assertEquals(1, s.cubesAt(city("Valencia"), DiseaseType.YELLOW));
        assertEquals(1, s.cubesAt(city("Cartagena"), DiseaseType.YELLOW));
        assertEquals(0, s.cubesAt(city("Albacete"), DiseaseType.YELLOW));
    }

    // ---------------------------------------------------------------- actions

    @Test
    void movementRules() {
        GameEngine e = game(Role.AGRONOMIST, Role.POLITICIAN);
        GameState s = e.state();
        Player p = s.player(0);
        p.city = city("Madrid");
        rejected(e, GameAction.of(ActionType.MOVE_CARRIAGE, 0).city(city("Sevilla")));
        ok(e, GameAction.of(ActionType.MOVE_CARRIAGE, 0).city(city("Toledo")));
        assertEquals(3, s.actionsLeft);
        // train along Toledo-Madrid-Zaragoza
        s.rails[b.routeBetween(city("Toledo"), city("Madrid")).id] = true;
        s.rails[b.routeBetween(city("Madrid"), city("Zaragoza")).id] = true;
        ok(e, GameAction.of(ActionType.MOVE_TRAIN, 0).city(city("Zaragoza")));
        rejected(e, GameAction.of(ActionType.MOVE_TRAIN, 0).city(city("Barcelona")));
        // ship needs a port and a card of the destination's colour
        p.city = city("Lisboa");
        p.hand.add(city("Porto"));
        rejected(e, GameAction.of(ActionType.MOVE_SHIP, 0).city(city("Málaga")).cards(city("Porto")));
        ok(e, GameAction.of(ActionType.MOVE_SHIP, 0).city(city("Vigo")).cards(city("Porto")));
        assertEquals(city("Vigo"), p.city);
        assertTrue(s.playerDiscard.contains(city("Porto")));
        assertEquals(1, s.actionsLeft);
        ok(e, GameAction.of(ActionType.END_ACTIONS, 0));
        assertEquals(GamePhase.DRAW, s.phase);
    }

    @Test
    void sailorAndRailwayman() {
        GameEngine e = game(Role.SAILOR, Role.RAILWAYMAN);
        GameState s = e.state();
        s.player(0).city = city("Cádiz");
        s.player(1).city = city("Cádiz");
        e.setSameController((a, c) -> true);
        ok(e, GameAction.of(ActionType.MOVE_SHIP, 0).city(city("Barcelona")).passenger(1));
        assertEquals(city("Barcelona"), s.player(1).city);
        // railwayman double railroad, once per turn
        s.currentPlayer = 1;
        s.actionsLeft = 4;
        int r1 = b.routeBetween(city("Barcelona"), city("Zaragoza")).id;
        int r2 = b.routeBetween(city("Zaragoza"), city("Madrid")).id;
        ok(e, GameAction.of(ActionType.BUILD_RAILROAD, 1).route(r1).route2(r2));
        assertEquals(18, s.railSupply);
        int r3 = b.routeBetween(city("Barcelona"), city("Girona")).id;
        int r4 = b.routeBetween(city("Barcelona"), city("Tarragona")).id;
        int r5 = b.routeBetween(city("Tarragona"), city("Valencia")).id;
        rejected(e, GameAction.of(ActionType.BUILD_RAILROAD, 1).route(r4).route2(r5));
        ok(e, GameAction.of(ActionType.BUILD_RAILROAD, 1).route(r3));
        // dashed lines never hold railroads
        int dashed = b.routeBetween(city("Barcelona"), city("Palma de Mallorca")).id;
        rejected(e, GameAction.of(ActionType.BUILD_RAILROAD, 1).route(dashed));
    }

    @Test
    void shareKnowledgeNeedsConsent() {
        GameEngine e = game(Role.AGRONOMIST, Role.POLITICIAN);
        GameState s = e.state();
        int madrid = city("Madrid");
        s.player(0).city = madrid;
        s.player(1).city = madrid;
        s.player(0).hand.add(madrid);
        ok(e, GameAction.of(ActionType.SHARE_GIVE, 0).target(1));
        assertNotNull(s.consent);
        rejected(e, GameAction.of(ActionType.TREAT_DISEASE, 0).color(DiseaseType.RED));
        ok(e, GameAction.of(ActionType.CONSENT_REPLY, 1).accept(true));
        assertNull(s.consent);
        assertTrue(s.player(1).hand.contains(madrid));
        assertEquals(3, s.actionsLeft);
    }

    @Test
    void handLimitForcesDiscard() {
        GameEngine e = game(Role.POLITICIAN, Role.AGRONOMIST);
        GameState s = e.state();
        int madrid = city("Madrid");
        s.player(0).city = madrid;
        s.player(0).hand.add(madrid);
        for (int c = 0; c < 7; c++) s.player(1).hand.add(c);
        ok(e, GameAction.of(ActionType.POLITICIAN_GIVE, 0).target(1));
        rejected(e, GameAction.of(ActionType.POLITICIAN_GIVE, 0).target(1));
        assertNotNull(RuleValidator.discardChoiceFor(s, 1));
        rejected(e, GameAction.of(ActionType.MOVE_CARRIAGE, 0).city(city("Toledo")));
        ok(e, GameAction.of(ActionType.DISCARD_CARD, 1).cards(0));
        assertNull(RuleValidator.discardChoiceFor(s, 1));
        ok(e, GameAction.of(ActionType.MOVE_CARRIAGE, 0).city(city("Toledo")));
    }

    @Test
    void purifyWaterCardRules() {
        GameEngine e = game(Role.AGRONOMIST, Role.SAILOR);
        GameState s = e.state();
        int barcelona = city("Barcelona");
        s.player(0).city = barcelona;
        int north = regionWith("Zaragoza", "Huesca", "Andorra la Vella", "Girona", "Barcelona");
        s.player(0).hand.add(city("Sevilla"));
        s.player(0).hand.add(city("Bilbao-Bilbo"));
        rejected(e, GameAction.of(ActionType.PURIFY_WATER, 0).region(north).cards(city("Sevilla")));
        ok(e, GameAction.of(ActionType.PURIFY_WATER, 0).region(north).cards(city("Bilbao-Bilbo")));
        assertEquals(3, s.purification[north]); // agronomist bonus
        s.researched[DiseaseType.BLACK.ordinal()] = true;
        ok(e, GameAction.of(ActionType.PURIFY_WATER, 0).region(north).cards(city("Sevilla")));
        assertEquals(6, s.purification[north]);
        assertEquals(8, s.purificationSupply);
    }

    @Test
    void researchAllFourWins() {
        GameEngine e = game();
        GameState s = e.state();
        int gijon = city("Gijón");
        Player p = s.player(0);
        p.city = gijon;
        p.hand.add(gijon);
        ok(e, GameAction.of(ActionType.BUILD_HOSPITAL, 0));
        assertEquals(gijon, s.hospitals[DiseaseType.RED.ordinal()]);
        List<Integer> reds = new ArrayList<>();
        for (int c = 0; c < b.cityCount() && reds.size() < 5; c++) if (b.city(c).color == DiseaseType.RED) reds.add(c);
        p.hand.addAll(reds);
        s.researched[DiseaseType.BLUE.ordinal()] = true;
        s.researched[DiseaseType.BLACK.ordinal()] = true;
        s.researched[DiseaseType.YELLOW.ordinal()] = true;
        ok(e, GameAction.of(ActionType.RESEARCH, 0).cards(reds));
        assertEquals(GamePhase.GAME_OVER, s.phase);
        assertTrue(s.won);
    }

    @Test
    void ruralDoctorRemovesTwoCubes() {
        GameEngine e = game(Role.RURAL_DOCTOR, Role.SAILOR);
        GameState s = e.state();
        s.player(0).city = city("Madrid");
        s.cubes[city("Madrid")][DiseaseType.RED.ordinal()] = 1;
        s.cubes[city("Zaragoza")][DiseaseType.YELLOW.ordinal()] = 2;
        ok(e, GameAction.of(ActionType.TREAT_DISEASE, 0).color(DiseaseType.RED).city2(city("Zaragoza")).color2(DiseaseType.YELLOW));
        assertEquals(0, s.cubesAt(city("Madrid"), DiseaseType.RED));
        assertEquals(1, s.cubesAt(city("Zaragoza"), DiseaseType.YELLOW));
        rejected(e, GameAction.of(ActionType.TREAT_DISEASE, 0).color(DiseaseType.RED));
    }

    @Test
    void eventsCanBePlayedOutOfTurn() {
        GameEngine e = game(Role.AGRONOMIST, Role.POLITICIAN);
        GameState s = e.state();
        s.player(1).hand.add(PlayerCard.ofEvent(EventType.ONE_MORE_DAY));
        ok(e, GameAction.of(ActionType.PLAY_EVENT, 1).event(EventType.ONE_MORE_DAY));
        assertEquals(6, s.actionsLeft);
        s.player(1).hand.add(PlayerCard.ofEvent(EventType.WHEN_THE_PLANS_WERE_GOOD));
        int r = b.routeBetween(city("Madrid"), city("Toledo")).id;
        ok(e, GameAction.of(ActionType.PLAY_EVENT, 1).event(EventType.WHEN_THE_PLANS_WERE_GOOD)
                .subEvent(EventType.ONE_MORE_DAY));
        assertEquals(8, s.actionsLeft);
        s.player(0).hand.add(PlayerCard.ofEvent(EventType.NEW_RAILS));
        ok(e, GameAction.of(ActionType.PLAY_EVENT, 0).event(EventType.NEW_RAILS).values(r));
        assertTrue(s.rails[r]);
    }

    @Test
    void governmentMobilizationGivesEveryoneAFreeMove() {
        GameEngine e = game(Role.AGRONOMIST, Role.POLITICIAN);
        GameState s = e.state();
        s.player(0).city = city("Madrid");
        s.player(1).city = city("Sevilla");
        s.player(1).hand.add(PlayerCard.ofEvent(EventType.GOVERNMENT_MOBILIZATION));
        ok(e, GameAction.of(ActionType.PLAY_EVENT, 1).event(EventType.GOVERNMENT_MOBILIZATION));
        ok(e, GameAction.of(ActionType.MOVE_CARRIAGE, 0).city(city("Toledo")));
        assertEquals(4, s.actionsLeft);
        ok(e, GameAction.of(ActionType.CHOOSE, 1).accept(false));
        assertNull(RuleValidator.blockingChoice(s));
        assertEquals(city("Sevilla"), s.player(1).city);
    }

    // ---------------------------------------------------------------- robustness

    @Test
    void stateSurvivesJsonRoundTrip() {
        GameEngine e = game(Role.NURSE, Role.RAILWAYMAN, Role.SAILOR);
        GameState s = e.state();
        s.cubes[3][1] = 2;
        GameState copy = Json.copy(s);
        assertEquals(Json.GSON.toJson(s), Json.GSON.toJson(copy));
        GameState red = Json.redacted(s);
        assertTrue(red.playerDeck.isEmpty());
        assertEquals(s.playerDeck.size(), red.playerDeckSize());
    }

    @Test
    void randomBotsAlwaysReachTheEnd() {
        for (int seed = 1; seed <= 25; seed++) {
            Random rnd = new Random(seed);
            GameSettings st = new GameSettings();
            st.seed = seed;
            st.epidemics = 4 + seed % 3;
            for (DiseaseType d : DiseaseType.values()) st.historical[d.ordinal()] = rnd.nextInt(3) == 0;
            int n = 2 + seed % 4;
            List<String> names = new ArrayList<>();
            List<Role> roles = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                names.add("B" + i);
                roles.add(null);
            }
            GameEngine e = GameEngine.newGame(names, roles, st);
            GameState s = e.state();
            int guard = 0;
            while (!s.isOver() && guard++ < 20000) botStep(e, s, rnd);
            assertTrue(s.isOver(), "seed " + seed + " did not finish");
            assertNotNull(s.gameOverReason);
            for (int v : s.cubeSupply) assertTrue(v >= 0);
        }
    }

    private void botStep(GameEngine e, GameState s, Random rnd) {
        if (s.consent != null) {
            ok(e, GameAction.of(ActionType.CONSENT_REPLY, s.consent.awaiting.get(0)).accept(rnd.nextBoolean()));
            return;
        }
        for (PendingChoice c : s.choices) {
            if (c.type == PendingChoice.Type.START_CITY) {
                ok(e, GameAction.of(ActionType.CHOOSE_START_CITY, c.player).city(c.options.get(rnd.nextInt(c.options.size()))));
                return;
            }
            if (c.type == PendingChoice.Type.DISCARD) {
                ok(e, GameAction.of(ActionType.DISCARD_CARD, c.player).cards(c.options.get(rnd.nextInt(c.options.size()))));
                return;
            }
        }
        PendingChoice c = RuleValidator.blockingChoice(s);
        if (c != null) {
            switch (c.type) {
                case SCIENTIST_ORDER: {
                    List<Integer> order = new ArrayList<>(c.options);
                    java.util.Collections.shuffle(order, rnd);
                    ok(e, GameAction.of(ActionType.CHOOSE, c.player).values(order));
                    break;
                }
                case FREE_MOVE: {
                    int[] nb = b.neighbors(s.player(c.player).city);
                    if (rnd.nextBoolean()) ok(e, GameAction.of(ActionType.MOVE_CARRIAGE, c.player).city(nb[rnd.nextInt(nb.length)]));
                    else ok(e, GameAction.of(ActionType.CHOOSE, c.player).accept(false));
                    break;
                }
                default:
                    ok(e, GameAction.of(ActionType.CHOOSE, c.player).region(c.options.get(rnd.nextInt(c.options.size()))));
            }
            return;
        }
        // occasionally play a random event from any hand
        if (rnd.nextInt(10) == 0) {
            for (Player p : s.players) {
                for (int card : p.hand) {
                    if (!PlayerCard.isEvent(card)) continue;
                    EventType ev = PlayerCard.event(card);
                    GameAction a = GameAction.of(ActionType.PLAY_EVENT, p.id).event(ev);
                    if (ev == EventType.GOOD_SEASON || ev == EventType.ONE_MORE_DAY || ev == EventType.GOVERNMENT_MOBILIZATION
                            || ev == EventType.SECOND_CHANCE) {
                        e.apply(a);
                        return;
                    }
                }
            }
        }
        Player cur = s.current();
        switch (s.phase) {
            case ACTIONS: {
                int roll = rnd.nextInt(10);
                if (roll < 4) {
                    int[] nb = b.neighbors(cur.city);
                    ok(e, GameAction.of(ActionType.MOVE_CARRIAGE, cur.id).city(nb[rnd.nextInt(nb.length)]));
                } else if (roll < 6 && !RuleValidator.colorsIn(s, cur.city).isEmpty()) {
                    e.apply(GameAction.of(ActionType.TREAT_DISEASE, cur.id).color(RuleValidator.colorsIn(s, cur.city).get(0)));
                } else if (roll < 7 && !RuleValidator.buildableRoutes(s, cur.city).isEmpty()) {
                    ok(e, GameAction.of(ActionType.BUILD_RAILROAD, cur.id).route(RuleValidator.buildableRoutes(s, cur.city).iterator().next()));
                } else if (roll < 8 && cur.hand.contains(cur.city)) {
                    e.apply(GameAction.of(ActionType.BUILD_HOSPITAL, cur.id));
                } else if (roll < 9) {
                    int[] regs = b.regionsOf(cur.city);
                    int reg = regs[rnd.nextInt(regs.length)];
                    List<Integer> cards = RuleValidator.purifyCards(s, cur.id, reg);
                    if (!cards.isEmpty() && s.purificationSupply > 0) {
                        ok(e, GameAction.of(ActionType.PURIFY_WATER, cur.id).region(reg).cards(cards.get(0)));
                    } else {
                        ok(e, GameAction.of(ActionType.END_ACTIONS, cur.id));
                    }
                } else {
                    ok(e, GameAction.of(ActionType.END_ACTIONS, cur.id));
                }
                break;
            }
            case DRAW:
                ok(e, GameAction.of(ActionType.DRAW_CARDS, cur.id));
                break;
            case EPIDEMIC_PENDING:
                ok(e, GameAction.of(ActionType.RESOLVE_EPIDEMIC, cur.id));
                break;
            case INFECTION:
                ok(e, GameAction.of(rnd.nextBoolean() ? ActionType.INFECT_ALL : ActionType.INFECT_NEXT, cur.id));
                break;
            default:
                throw new IllegalStateException("Unexpected phase " + s.phase);
        }
    }
}
