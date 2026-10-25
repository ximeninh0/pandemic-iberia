package com.iberia.ui;

import com.iberia.model.GameState;
import com.iberia.model.Player;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Set;
import java.util.function.BiConsumer;

/** Shows the hand of one player (hands are public in Pandemic); cards of your own seats are clickable. */
public final class PlayerHandView extends VBox {
    private final Label header = new Label();
    private final Label hint = new Label();
    private final HBox cards = new HBox(8);
    private BiConsumer<Integer, Integer> onCardClick = (seat, card) -> {
    };

    public PlayerHandView() {
        getStyleClass().add("hand-view");
        setSpacing(4);
        setPadding(new Insets(6, 10, 6, 10));
        header.getStyleClass().add("panel-title");
        hint.getStyleClass().add("hint");
        cards.setPadding(new Insets(12, 4, 4, 4));
        cards.setAlignment(Pos.CENTER_LEFT);
        ScrollPane sp = new ScrollPane(cards);
        sp.setFitToHeight(true);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.getStyleClass().add("transparent-scroll");
        VBox.setVgrow(sp, Priority.ALWAYS);
        HBox top = new HBox(12, header, hint);
        top.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(top, sp);
    }

    public void setOnCardClick(BiConsumer<Integer, Integer> handler) {
        this.onCardClick = handler;
    }

    /**
     * @param usable cards to emphasise (others dimmed); {@code null} = all normal
     */
    public void show(GameState s, int seat, boolean mine, Set<Integer> usable, String hintText) {
        Player p = s.player(seat);
        header.setText("Mão de " + p.name + " (" + p.role.title + ") — " + p.hand.size() + "/" + GameState.HAND_LIMIT
                + (mine ? "" : "  · somente leitura"));
        hint.setText(hintText == null ? "" : hintText);
        cards.getChildren().clear();
        for (int card : p.hand) {
            CardView v = new CardView(card, 0.92);
            if (usable != null) v.setUsable(usable.contains(card));
            if (s.lastDrawn.contains(card) && s.currentPlayer == seat) v.getStyleClass().add("card-new");
            if (mine) {
                v.setOnMouseClicked(e -> onCardClick.accept(seat, card));
                v.setCursor(javafx.scene.Cursor.HAND);
            }
            cards.getChildren().add(v);
        }
        if (p.hand.isEmpty()) {
            Label empty = new Label("(sem cartas)");
            empty.getStyleClass().add("hint");
            cards.getChildren().add(empty);
        }
    }
}
