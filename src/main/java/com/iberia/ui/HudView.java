package com.iberia.ui;

import com.iberia.model.Board;
import com.iberia.model.DiseaseType;
import com.iberia.model.GamePhase;
import com.iberia.model.GameState;
import com.iberia.model.Player;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;

/** Top bar: whose turn, actions left, infection-rate and outbreak tracks, research, supplies and decks. */
public final class HudView extends HBox {
    private final Label turnLabel = new Label();
    private final Label playerLabel = new Label();
    private final Circle playerPawn = new Circle(8);
    private final Label phaseLabel = new Label();
    private final HBox actionPips = new HBox(4);
    private final HBox rateTrack = new HBox(3);
    private final HBox outbreakTrack = new HBox(2);
    private final HBox research = new HBox(8);
    private final HBox supplies = new HBox(10);
    private final Label decks = new Label();
    private final Button discardsButton = new Button("Descartes");

    public HudView() {
        getStyleClass().add("hud");
        setSpacing(18);
        setPadding(new Insets(6, 12, 6, 12));
        setAlignment(Pos.CENTER_LEFT);

        turnLabel.getStyleClass().add("hud-small");
        playerLabel.getStyleClass().add("hud-title");
        phaseLabel.getStyleClass().add("hud-small");
        playerPawn.setStroke(Color.web("#1a120b"));
        HBox who = new HBox(6, playerPawn, playerLabel);
        who.setAlignment(Pos.CENTER_LEFT);
        VBox turnBox = new VBox(1, turnLabel, who, new HBox(8, phaseLabel, actionPips));
        turnBox.setMinWidth(250);

        VBox rate = section("Taxa de infecção", rateTrack);
        VBox outb = section("Surtos", outbreakTrack);
        VBox res = section("Pesquisas e hospitais", research);
        VBox sup = section("Reserva", supplies);
        decks.getStyleClass().add("hud-small");
        discardsButton.getStyleClass().add("small-button");
        VBox deck = section("Baralhos", new HBox(8, decks, discardsButton));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        getChildren().addAll(turnBox, rate, outb, spacer, res, sup, deck);
    }

    public Button discardsButton() {
        return discardsButton;
    }

    private static VBox section(String title, Node content) {
        Label t = new Label(title);
        t.getStyleClass().add("hud-caption");
        VBox v = new VBox(3, t, content);
        v.setAlignment(Pos.CENTER_LEFT);
        return v;
    }

    public void update(GameState s) {
        Player cur = s.current();
        turnLabel.setText(s.phase == GamePhase.SETUP ? "Preparação da partida" : "Turno " + s.turn + " · dificuldade "
                + s.settings.difficultyName());
        if (s.phase == GamePhase.SETUP) {
            playerLabel.setText("Escolham as cidades iniciais");
            playerPawn.setFill(Color.TRANSPARENT);
        } else {
            playerLabel.setText(cur.name + " — " + cur.role.title);
            playerPawn.setFill(Theme.pawn(cur.role));
        }
        phaseLabel.setText("Fase: " + s.phase.title);

        actionPips.getChildren().clear();
        if (s.phase == GamePhase.ACTIONS) {
            int total = Math.max(GameState.ACTIONS_PER_TURN, s.actionsLeft);
            for (int i = 0; i < total; i++) {
                Circle c = new Circle(5.5);
                boolean left = i < s.actionsLeft;
                c.setFill(left ? Theme.GOLD : Color.web("#d8c9a6"));
                c.setStroke(Color.web("#5a4027"));
                actionPips.getChildren().add(c);
            }
            Label n = new Label(s.actionsLeft + " ação(ões)");
            n.getStyleClass().add("hud-small");
            actionPips.getChildren().add(n);
        } else if (s.phase == GamePhase.INFECTION) {
            Label n = new Label(s.infectionsLeft + " carta(s) a revelar" + (s.infectFromBottom ? " (do fundo)" : ""));
            n.getStyleClass().add("hud-small");
            actionPips.getChildren().add(n);
        }

        rateTrack.getChildren().clear();
        for (int i = 0; i < GameState.INFECTION_RATE_TRACK.length; i++) {
            boolean active = i == s.infectionRateIndex;
            Circle c = new Circle(active ? 12 : 10);
            c.setFill(active ? Color.web("#a3261c") : Color.web("#e8d7b0"));
            c.setStroke(Color.web("#5a4027"));
            c.setStrokeWidth(active ? 2 : 1);
            Label l = new Label(String.valueOf(GameState.INFECTION_RATE_TRACK[i]));
            l.setTextFill(active ? Color.WHITE : Theme.INK);
            l.getStyleClass().add("hud-number");
            rateTrack.getChildren().add(new StackPane(c, l));
        }
        rateTrack.setAlignment(Pos.CENTER_LEFT);

        outbreakTrack.getChildren().clear();
        for (int i = 0; i <= GameState.MAX_OUTBREAKS; i++) {
            boolean reached = i <= s.outbreaks;
            boolean marker = i == s.outbreaks;
            Circle c = new Circle(marker ? 11 : 8.5);
            c.setFill(i == GameState.MAX_OUTBREAKS ? Color.web("#2a1d12") : reached ? Color.web("#c0392b") : Color.web("#e8d7b0"));
            c.setStroke(marker ? Theme.GOLD : Color.web("#5a4027"));
            c.setStrokeWidth(marker ? 2.5 : 1);
            Label l = new Label(i == GameState.MAX_OUTBREAKS ? "†" : String.valueOf(i));
            l.setTextFill(reached || i == GameState.MAX_OUTBREAKS ? Color.WHITE : Theme.INK);
            l.getStyleClass().add("hud-number");
            outbreakTrack.getChildren().add(new StackPane(c, l));
        }
        outbreakTrack.setAlignment(Pos.CENTER_LEFT);

        research.getChildren().clear();
        for (DiseaseType d : DiseaseType.values()) {
            boolean done = s.researched[d.ordinal()];
            int hosp = s.hospitals[d.ordinal()];
            Node flask = flask(Theme.color(d), done);
            Label where = new Label(hosp >= 0 ? Board.get().city(hosp).name : "—");
            where.getStyleClass().add("hud-tiny");
            VBox box = new VBox(1, flask, where);
            box.setAlignment(Pos.CENTER);
            box.setMinWidth(62);
            Tooltip.install(box, new Tooltip(d.diseaseName + (done ? " — PESQUISADA" : " — não pesquisada")
                    + "\nHospital " + d.colorName + ": " + (hosp >= 0 ? Board.get().city(hosp).name : "não construído")));
            research.getChildren().add(box);
        }

        supplies.getChildren().clear();
        for (DiseaseType d : DiseaseType.values()) {
            Rectangle cube = new Rectangle(11, 11, Theme.color(d));
            cube.setStroke(Color.web("#1a120b"));
            Label n = new Label(String.valueOf(s.cubeSupply[d.ordinal()]));
            n.getStyleClass().add("hud-number");
            if (s.cubeSupply[d.ordinal()] <= 4) n.setTextFill(Theme.DANGER);
            HBox h = new HBox(3, cube, n);
            h.setAlignment(Pos.CENTER_LEFT);
            Tooltip.install(h, new Tooltip("Cubos de " + d.diseaseName + " restantes na reserva"));
            supplies.getChildren().add(h);
        }
        Label rails = new Label("Trilhos " + s.railSupply);
        rails.getStyleClass().add("hud-number");
        Label water = new Label("Purificação " + s.purificationSupply);
        water.getStyleClass().add("hud-number");
        supplies.getChildren().add(new VBox(0, rails, water));
        supplies.setAlignment(Pos.CENTER_LEFT);

        decks.setText("Jogador: " + s.playerDeckSize() + "\nInfecção: " + s.infectionDeckSize());
        if (s.playerDeckSize() < 4) decks.setTextFill(Theme.DANGER);
        else decks.setTextFill(Theme.INK);
    }

    private static Node flask(Color col, boolean done) {
        Polygon p = new Polygon(-4, -11, 4, -11, 4, -4, 11, 9, -11, 9, -4, -4);
        p.setFill(done ? col : col.deriveColor(0, 0.35, 1.15, 0.45));
        p.setStroke(done ? Theme.GOLD : Color.web("#5a4027"));
        p.setStrokeWidth(done ? 2.5 : 1.2);
        StackPane sp = new StackPane(p);
        if (done) {
            javafx.scene.shape.Polyline check = new javafx.scene.shape.Polyline(-4, 3, -1, 6, 5, -1);
            check.setStroke(Color.WHITE);
            check.setStrokeWidth(2.2);
            check.setTranslateY(3);
            sp.getChildren().add(check);
        }
        return sp;
    }
}
