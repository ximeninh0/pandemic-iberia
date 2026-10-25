package com.iberia.ui;

import com.iberia.engine.GameEngine;
import com.iberia.engine.RuleValidator;
import com.iberia.model.ActionType;
import com.iberia.model.Board;
import com.iberia.model.DiseaseType;
import com.iberia.model.GameAction;
import com.iberia.model.GameSettings;
import com.iberia.model.GameState;
import com.iberia.model.PendingChoice;
import com.iberia.model.Player;
import com.iberia.model.Role;
import com.iberia.network.Json;
import com.iberia.network.Packet;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dev utility: plays complete bot games and feeds every intermediate state to a
 * real {@link GameScreen}, forcing a render each step, to catch UI exceptions in
 * every phase (choices, consents, epidemics, game over).
 */
public class UiSmokeTool extends Application {
    public static final class Run {
        public static void main(String[] args) {
            Application.launch(UiSmokeTool.class, args);
        }
    }

    @Override
    public void start(Stage stage) {
        AtomicInteger failures = new AtomicInteger();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            failures.incrementAndGet();
            e.printStackTrace();
        });
        Board b = Board.get();
        int states = 0;
        for (int seed = 1; seed <= 12; seed++) {
            Random rnd = new Random(seed);
            GameSettings st = new GameSettings();
            st.seed = seed;
            st.epidemics = 4 + seed % 3;
            for (DiseaseType d : DiseaseType.values()) st.historical[d.ordinal()] = rnd.nextBoolean();
            List<Role> pool = new ArrayList<>(Arrays.asList(Role.AGRONOMIST, Role.NURSE, Role.POLITICIAN, Role.RAILWAYMAN,
                    Role.RURAL_DOCTOR, Role.SAILOR));
            java.util.Collections.shuffle(pool, rnd);
            int n = 2 + seed % 4;
            List<String> names = new ArrayList<>();
            for (int i = 0; i < n; i++) names.add("Bot" + i);
            GameEngine e = GameEngine.newGame(names, pool.subList(0, n), st);
            e.setSameController((x, y) -> x % 2 == y % 2);
            GameState s = e.state();
            List<Packet.Seat> seats = new ArrayList<>();
            for (Player p : s.players) {
                Packet.Seat seat = new Packet.Seat();
                seat.seat = p.id;
                seat.name = p.name;
                seat.ownerId = p.id % 2 == 0 ? "me" : "other";
                seat.ownerName = seat.ownerId;
                seat.role = p.role;
                seats.add(seat);
            }
            GameScreen screen = new GameScreen("me", a -> {
            }, t -> {
            }, c -> {
            }, () -> {
            });
            Scene scene = new Scene(screen, 1500, 920);
            scene.getStylesheets().add(getClass().getResource("/com/iberia/iberia.css").toExternalForm());
            stage.setScene(scene);
            stage.show();
            int guard = 0;
            while (!s.isOver() && guard++ < 5000) {
                step(e, s, rnd, b);
                screen.update(Json.redacted(s), seats);
                states++;
                if (guard % 25 == 0) {
                    screen.applyCss();
                    screen.layout();
                    scene.snapshot(null);
                }
            }
            screen.update(Json.redacted(s), seats);
            scene.snapshot(null);
            System.out.println("seed " + seed + ": " + n + " players, " + guard + " steps, " + (s.won ? "WON" : "lost") + " — " + s.gameOverReason);
        }
        System.out.println("UI smoke: " + states + " states rendered, " + failures.get() + " uncaught exceptions");
        Platform.exit();
    }

    private void step(GameEngine e, GameState s, Random rnd, Board b) {
        if (s.consent != null) {
            e.apply(GameAction.of(ActionType.CONSENT_REPLY, s.consent.awaiting.get(0)).accept(rnd.nextInt(4) > 0));
            return;
        }
        for (PendingChoice c : s.choices) {
            if (c.type == PendingChoice.Type.START_CITY) {
                e.apply(GameAction.of(ActionType.CHOOSE_START_CITY, c.player).city(c.options.get(rnd.nextInt(c.options.size()))));
                return;
            }
            if (c.type == PendingChoice.Type.DISCARD) {
                e.apply(GameAction.of(ActionType.DISCARD_CARD, c.player).cards(c.options.get(rnd.nextInt(c.options.size()))));
                return;
            }
        }
        PendingChoice c = RuleValidator.blockingChoice(s);
        if (c != null) {
            if (c.type == PendingChoice.Type.FREE_MOVE) {
                e.apply(GameAction.of(ActionType.CHOOSE, c.player).accept(false));
            } else {
                e.apply(GameAction.of(ActionType.CHOOSE, c.player).region(c.options.get(rnd.nextInt(c.options.size()))));
            }
            return;
        }
        Player cur = s.current();
        switch (s.phase) {
            case ACTIONS: {
                int roll = rnd.nextInt(12);
                if (roll < 4) {
                    int[] nb = b.neighbors(cur.city);
                    e.apply(GameAction.of(ActionType.MOVE_CARRIAGE, cur.id).city(nb[rnd.nextInt(nb.length)]));
                } else if (roll < 6 && !RuleValidator.colorsIn(s, cur.city).isEmpty()) {
                    e.apply(GameAction.of(ActionType.TREAT_DISEASE, cur.id).color(RuleValidator.colorsIn(s, cur.city).get(0)));
                } else if (roll < 7 && !RuleValidator.buildableRoutes(s, cur.city).isEmpty()) {
                    e.apply(GameAction.of(ActionType.BUILD_RAILROAD, cur.id).route(RuleValidator.buildableRoutes(s, cur.city).iterator().next()));
                } else if (roll < 8) {
                    e.apply(GameAction.of(ActionType.BUILD_HOSPITAL, cur.id));
                } else if (roll < 9) {
                    for (int o : RuleValidator.playersInCity(s, cur.city, cur.id)) {
                        e.apply(GameAction.of(ActionType.SHARE_GIVE, cur.id).target(o));
                        break;
                    }
                    e.apply(GameAction.of(ActionType.END_ACTIONS, cur.id));
                } else if (roll < 10) {
                    int[] regs = b.regionsOf(cur.city);
                    int reg = regs[rnd.nextInt(regs.length)];
                    List<Integer> cards = RuleValidator.purifyCards(s, cur.id, reg);
                    if (!cards.isEmpty()) e.apply(GameAction.of(ActionType.PURIFY_WATER, cur.id).region(reg).cards(cards.get(0)));
                    else e.apply(GameAction.of(ActionType.END_ACTIONS, cur.id));
                } else {
                    e.apply(GameAction.of(ActionType.END_ACTIONS, cur.id));
                }
                break;
            }
            case DRAW:
                e.apply(GameAction.of(ActionType.DRAW_CARDS, cur.id));
                break;
            case EPIDEMIC_PENDING:
                e.apply(GameAction.of(ActionType.RESOLVE_EPIDEMIC, cur.id));
                break;
            case INFECTION:
                e.apply(GameAction.of(ActionType.INFECT_NEXT, cur.id));
                break;
            default:
                break;
        }
    }
}
