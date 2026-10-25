package com.iberia.ui;

import com.iberia.model.Board;
import com.iberia.model.GamePhase;
import com.iberia.model.GameState;
import com.iberia.model.Player;
import com.iberia.model.PlayerCard;
import com.iberia.network.Packet;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;

import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

/** Table overview: every seat with role, location, connection status and a colour summary of the hand. */
public final class PlayersPanel extends VBox {
    private IntConsumer onSelect = i -> {
    };
    private IntConsumer onTakeSeat = i -> {
    };

    public PlayersPanel() {
        getStyleClass().add("players-panel");
        setSpacing(4);
        setPadding(new Insets(6));
    }

    public void setOnSelect(IntConsumer c) {
        this.onSelect = c;
    }

    public void setOnTakeSeat(IntConsumer c) {
        this.onTakeSeat = c;
    }

    public void update(GameState s, List<Packet.Seat> seats, Set<Integer> mySeats, int focused) {
        getChildren().clear();
        Label title = new Label("Expedição");
        title.getStyleClass().add("panel-title");
        getChildren().add(title);
        for (Player p : s.players) {
            Packet.Seat seat = p.id < seats.size() ? seats.get(p.id) : null;
            Circle pawn = new Circle(9, Theme.pawn(p.role));
            pawn.setStroke(Color.web("#1a120b"));
            pawn.setStrokeWidth(mySeats.contains(p.id) ? 2.5 : 1);
            Label name = new Label(p.name + (mySeats.contains(p.id) ? "  (você)" : ""));
            name.getStyleClass().add("player-name");
            String where = p.city >= 0 ? Board.get().city(p.city).name : "a escolher";
            Label info = new Label(p.role.title + " · " + where);
            info.getStyleClass().add("player-info");
            FlowPane chips = new FlowPane(2, 2);
            for (int card : p.hand) {
                Rectangle r = new Rectangle(9, 13);
                if (PlayerCard.isCity(card)) r.setFill(Theme.color(PlayerCard.color(card)));
                else r.setFill(Color.web("#c99a3c"));
                r.setStroke(Color.web("#3b2a1a"));
                r.setArcWidth(3);
                r.setArcHeight(3);
                Tooltip.install(r, new Tooltip(Dialogs.cardLabel(card)));
                chips.getChildren().add(r);
            }
            VBox text = new VBox(1, name, info, chips);
            HBox row = new HBox(8, pawn, text);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(4, 6, 4, 6));
            row.getStyleClass().add("player-row");
            if (s.currentPlayer == p.id && s.phase != GamePhase.SETUP) row.getStyleClass().add("player-current");
            if (focused == p.id) row.getStyleClass().add("player-focused");
            if (seat != null && !seat.connected) {
                Label off = new Label("desconectado — clique duas vezes para assumir");
                off.getStyleClass().add("player-offline");
                text.getChildren().add(off);
            }
            Tooltip.install(row, new Tooltip(p.role.title + "\n" + p.role.abilities()));
            final int id = p.id;
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && seat != null && !seat.connected) onTakeSeat.accept(id);
                else onSelect.accept(id);
            });
            getChildren().add(row);
        }
    }
}
