package com.iberia.ui;

import com.iberia.model.LogEntry;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.function.Consumer;

/** Game log (actions, draws, infections, outbreaks) merged with the chat. */
public final class LogView extends VBox {
    private static final class Item {
        final LogEntry.Kind kind;
        final String text;

        Item(LogEntry.Kind kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    private final ListView<Item> list = new ListView<>();
    private final TextField input = new TextField();
    private int lastSeq;

    public LogView(Consumer<String> onSend) {
        getStyleClass().add("log-view");
        setSpacing(4);
        setPadding(new Insets(6));
        Label title = new Label("Diário da expedição");
        title.getStyleClass().add("panel-title");
        list.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(Item it, boolean empty) {
                super.updateItem(it, empty);
                if (empty || it == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText(it.text);
                setWrapText(true);
                setPrefWidth(0);
                setTextFill(color(it.kind));
                setStyle(it.kind == LogEntry.Kind.OUTBREAK || it.kind == LogEntry.Kind.EPIDEMIC
                        || it.kind == LogEntry.Kind.BAD || it.kind == LogEntry.Kind.GOOD ? "-fx-font-weight: bold;" : "");
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);
        input.setPromptText("Mensagem para a mesa…");
        Button send = new Button("Enviar");
        Runnable doSend = () -> {
            String t = input.getText().trim();
            if (!t.isEmpty()) onSend.accept(t);
            input.clear();
        };
        send.setOnAction(e -> doSend.run());
        input.setOnAction(e -> doSend.run());
        HBox.setHgrow(input, Priority.ALWAYS);
        getChildren().addAll(title, list, new HBox(4, input, send));
    }

    private static Color color(LogEntry.Kind k) {
        switch (k) {
            case OUTBREAK:
            case BAD:
                return Color.web("#9e1b13");
            case EPIDEMIC:
                return Color.web("#2f5d27");
            case INFECTION:
                return Color.web("#7a3b12");
            case GOOD:
                return Color.web("#1f6b3a");
            case EVENT:
                return Color.web("#7a4e1d");
            case CHAT:
                return Color.web("#1d4f7a");
            case CARD:
                return Color.web("#4b3a6b");
            default:
                return Color.web("#3b2a1a");
        }
    }

    /** Appends state log entries not shown yet. */
    public void syncLog(java.util.List<LogEntry> log) {
        boolean added = false;
        for (LogEntry e : log) {
            if (e.seq > lastSeq) {
                list.getItems().add(new Item(e.kind, e.text));
                lastSeq = e.seq;
                added = true;
            }
        }
        if (added) scrollToEnd();
    }

    public void appendChat(String sender, String message) {
        list.getItems().add(new Item(LogEntry.Kind.CHAT, sender + ": " + message));
        scrollToEnd();
    }

    public void appendSystem(String text, boolean bad) {
        list.getItems().add(new Item(bad ? LogEntry.Kind.BAD : LogEntry.Kind.SYSTEM, text));
        scrollToEnd();
    }

    private void scrollToEnd() {
        if (list.getItems().size() > 1500) list.getItems().remove(0, 300);
        list.scrollTo(list.getItems().size() - 1);
    }
}
