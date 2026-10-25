package com.iberia.ui;

import com.iberia.model.DiseaseType;
import com.iberia.model.GameSettings;
import com.iberia.model.Role;
import com.iberia.network.Packet;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.util.StringConverter;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** Lobby: seats and role selection, ready states, host settings (difficulty, Historical Diseases) and chat. */
public final class LobbyScreen extends StackPane {
    private final String myClientId;
    private final Consumer<Packet.LobbyCommand> sender;
    private final VBox seatList = new VBox(6);
    private final Label header = new Label();
    private final Label address = new Label();
    private final ChoiceBox<Integer> difficulty = new ChoiceBox<>();
    private final List<CheckBox> historical = new ArrayList<>();
    private final ToggleButton ready = new ToggleButton("Estou pronto");
    private final Button start = new Button("Iniciar partida");
    private final TextField localName = new TextField();
    private final ListView<String> chat = new ListView<>();
    private final Label status = new Label();
    private boolean updating;
    private boolean host;

    public LobbyScreen(String myClientId, int port, Consumer<Packet.LobbyCommand> sender, Consumer<String> chatSender, Runnable onLeave) {
        this.myClientId = myClientId;
        this.sender = sender;
        BoardCanvas backdrop = new BoardCanvas();
        backdrop.setOpacity(0.4);
        backdrop.setMouseTransparent(true);

        header.getStyleClass().add("menu-title");
        header.setStyle("-fx-font-size: 30px;");
        address.getStyleClass().add("hint");
        address.setText("Outros jogadores entram em: " + localAddresses() + " · porta " + port);

        Label seatsTitle = new Label("Mesa (2 a 5 jogadores)");
        seatsTitle.getStyleClass().add("section-title");
        localName.setPromptText("Nome do jogador local");
        localName.setPrefColumnCount(12);
        Button addLocal = new Button("Adicionar jogador local");
        addLocal.getStyleClass().add("small-button");
        addLocal.setTooltip(new Tooltip("Para jogar vários papéis neste mesmo computador (ex.: partida solo)."));
        addLocal.setOnAction(e -> {
            Packet.LobbyCommand c = cmd(Packet.LobbyCommand.Cmd.ADD_LOCAL_SEAT);
            c.name = localName.getText().trim();
            sender.accept(c);
            localName.clear();
        });
        HBox addRow = new HBox(6, localName, addLocal);
        addRow.setAlignment(Pos.CENTER_LEFT);
        VBox seatsBox = new VBox(8, seatsTitle, seatList, addRow);
        seatsBox.setPrefWidth(470);

        Label settingsTitle = new Label("Configurações");
        settingsTitle.getStyleClass().add("section-title");
        difficulty.getItems().addAll(4, 5, 6);
        difficulty.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer n) {
                return n == null ? "" : n + " Epidemias — " + (n == 4 ? "Introdutório" : n == 5 ? "Padrão" : "Heroico");
            }

            @Override
            public Integer fromString(String s) {
                return null;
            }
        });
        difficulty.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> pushSettings());
        Label histTitle = new Label("Desafio Doenças Históricas:");
        VBox hist = new VBox(4, histTitle);
        String[] effects = {"novas infecções põem 2 cubos", "tratar com 2+ cubos custa 2 ações",
                "surtos atingem cidades vazias a 2 conexões", "cubos em portos atingem portos vizinhos"};
        for (DiseaseType d : DiseaseType.values()) {
            CheckBox cb = new CheckBox(d.diseaseName + " — " + effects[d.ordinal()]);
            cb.selectedProperty().addListener((o, a, b) -> pushSettings());
            historical.add(cb);
            hist.getChildren().add(cb);
        }
        VBox settingsBox = new VBox(8, settingsTitle, new HBox(8, new Label("Dificuldade:"), difficulty), hist);

        Label chatTitle = new Label("Conversa");
        chatTitle.getStyleClass().add("section-title");
        chat.setPrefHeight(150);
        TextField chatInput = new TextField();
        chatInput.setPromptText("Mensagem…");
        chatInput.setOnAction(e -> {
            String t = chatInput.getText().trim();
            if (!t.isEmpty()) chatSender.accept(t);
            chatInput.clear();
        });
        HBox.setHgrow(chatInput, Priority.ALWAYS);
        VBox chatBox = new VBox(6, chatTitle, chat, chatInput);
        VBox right = new VBox(18, settingsBox, chatBox);
        right.setPrefWidth(420);

        ready.getStyleClass().add("big-button");
        ready.setOnAction(e -> {
            Packet.LobbyCommand c = cmd(Packet.LobbyCommand.Cmd.SET_READY);
            c.ready = ready.isSelected();
            sender.accept(c);
        });
        start.getStyleClass().add("big-button");
        start.setOnAction(e -> sender.accept(cmd(Packet.LobbyCommand.Cmd.START_GAME)));
        Button leave = new Button("Sair");
        leave.setOnAction(e -> onLeave.run());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bottom = new HBox(10, leave, status, spacer, ready, start);
        bottom.setAlignment(Pos.CENTER_LEFT);

        HBox columns = new HBox(26, seatsBox, right);
        VBox panel = new VBox(14, header, address, columns, bottom);
        panel.setPadding(new Insets(22, 28, 20, 28));
        panel.getStyleClass().add("lobby-panel");
        panel.setMaxSize(1000, 640);
        getChildren().addAll(backdrop, panel);
    }

    private Packet.LobbyCommand cmd(Packet.LobbyCommand.Cmd c) {
        Packet.LobbyCommand l = new Packet.LobbyCommand();
        l.cmd = c;
        return l;
    }

    private void pushSettings() {
        if (updating || !host || difficulty.getValue() == null) return;
        GameSettings s = new GameSettings();
        s.epidemics = difficulty.getValue();
        for (int i = 0; i < historical.size(); i++) s.historical[i] = historical.get(i).isSelected();
        Packet.LobbyCommand c = cmd(Packet.LobbyCommand.Cmd.SET_SETTINGS);
        c.settings = s;
        sender.accept(c);
    }

    public void update(Packet.LobbyUpdate u) {
        updating = true;
        host = myClientId.equals(u.hostId);
        header.setText(u.serverName == null ? "Saguão" : "Saguão — " + u.serverName);
        difficulty.setValue(u.settings.epidemics);
        difficulty.setDisable(!host);
        for (int i = 0; i < historical.size(); i++) {
            historical.get(i).setSelected(u.settings.historical[i]);
            historical.get(i).setDisable(!host);
        }
        ready.setVisible(!host);
        ready.setManaged(!host);
        start.setVisible(host);
        start.setManaged(host);
        boolean everyoneReady = u.seats.stream().allMatch(s -> s.ready);
        start.setDisable(u.seats.size() < 2 || !everyoneReady);
        status.setText(u.seats.size() < 2 ? "Aguardando jogadores (adicione um jogador local para jogar sozinho)."
                : everyoneReady ? "Todos prontos!" : "Aguardando todos ficarem prontos…");

        seatList.getChildren().clear();
        long mine = u.seats.stream().filter(s -> myClientId.equals(s.ownerId)).count();
        for (Packet.Seat s : u.seats) {
            boolean own = myClientId.equals(s.ownerId);
            Circle dot = new Circle(8, s.role == null ? Color.web("#bfa678") : Theme.pawn(s.role));
            dot.setStroke(Color.web("#1a120b"));
            Label name = new Label(s.name + (own ? " (você)" : ""));
            name.getStyleClass().add("player-name");
            boolean primary = s.ownerName.equals(s.name);
            Label owner = new Label(primary ? (s.ownerId.equals(u.hostId) ? "anfitrião" : "") : "jogador local de " + s.ownerName);
            owner.getStyleClass().add("player-info");
            VBox who = new VBox(1, name, owner);
            ChoiceBox<String> role = new ChoiceBox<>();
            role.getItems().add("Papel aleatório");
            for (Role r : Role.values()) role.getItems().add(r.title);
            role.getSelectionModel().select(s.role == null ? 0 : s.role.ordinal() + 1);
            role.setDisable(!own && !host);
            role.setPrefWidth(175);
            if (s.role != null) role.setTooltip(new Tooltip(s.role.abilities()));
            final int seatIndex = s.seat;
            role.getSelectionModel().selectedIndexProperty().addListener((o, a, b) -> {
                if (updating || b.intValue() < 0) return;
                Packet.LobbyCommand c = cmd(Packet.LobbyCommand.Cmd.SET_ROLE);
                c.seat = seatIndex;
                c.role = b.intValue() == 0 ? null : Role.values()[b.intValue() - 1];
                sender.accept(c);
            });
            Label readyLabel = new Label(s.ready ? "pronto" : "aguarda");
            readyLabel.setMinWidth(Region.USE_PREF_SIZE);
            name.setMinWidth(Region.USE_PREF_SIZE);
            readyLabel.setTextFill(s.ready ? Color.web("#2f6b2a") : Color.web("#a3261c"));
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox row = new HBox(10, dot, who, spacer, role, readyLabel);
            if (own && mine > 1) {
                Button remove = new Button("×");
                remove.getStyleClass().add("small-button");
                remove.setOnAction(e -> {
                    Packet.LobbyCommand c = cmd(Packet.LobbyCommand.Cmd.REMOVE_SEAT);
                    c.seat = seatIndex;
                    sender.accept(c);
                });
                row.getChildren().add(remove);
            }
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("seat-row");
            seatList.getChildren().add(row);
        }
        updating = false;
    }

    public void appendChat(String sender, String text) {
        chat.getItems().add(sender + ": " + text);
        chat.scrollTo(chat.getItems().size() - 1);
    }

    public void showError(String message) {
        status.setText(message);
        status.getStyleClass().add("status-error");
        appendChat("Aviso", message);
    }

    private static String localAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {
            // no interfaces
        }
        return out.isEmpty() ? "127.0.0.1" : String.join(" / ", out);
    }
}
