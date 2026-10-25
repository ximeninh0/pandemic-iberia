package com.iberia.ui;

import com.iberia.engine.RuleValidator;
import com.iberia.model.ActionType;
import com.iberia.model.Board;
import com.iberia.model.City;
import com.iberia.model.DiseaseType;
import com.iberia.model.EventType;
import com.iberia.model.GameAction;
import com.iberia.model.GamePhase;
import com.iberia.model.GameState;
import com.iberia.model.PendingChoice;
import com.iberia.model.Player;
import com.iberia.model.PlayerCard;
import com.iberia.model.Role;
import com.iberia.model.Route;
import com.iberia.network.Packet;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Main game view. It renders the canonical state received from the server and
 * turns clicks into {@link GameAction} packets; it never changes the state
 * itself, so every move is validated by the authoritative host.
 */
public final class GameScreen extends BorderPane {
    private static final Board B = Board.get();

    private final Consumer<GameAction> sender;
    private final Consumer<Packet.LobbyCommand> lobbySender;
    private final Runnable onLeave;
    private final String myClientId;

    private final HudView hud = new HudView();
    private final BoardCanvas board = new BoardCanvas();
    private final PlayerHandView hand = new PlayerHandView();
    private final PlayersPanel players = new PlayersPanel();
    private final ActionControlsView controls = new ActionControlsView();
    private final LogView log;
    private final HBox promptBar = new HBox(10);
    private final Label promptLabel = new Label();
    private final HBox promptButtons = new HBox(6);
    private final Label toast = new Label();
    private final StackPane overlay = new StackPane();

    private GameState state;
    private List<Packet.Seat> seats = new ArrayList<>();
    private Set<Integer> mySeats = new HashSet<>();
    private int focusedSeat = -1;
    /** User-started multi-step flow (cancelled when the game situation changes). */
    private boolean flowActive;
    private String flowSignature = "";
    private String flowPrompt;
    private List<Button> flowButtons = new ArrayList<>();
    private String autoSelectionKey = "";

    public GameScreen(String myClientId, Consumer<GameAction> sender, Consumer<String> chatSender,
                      Consumer<Packet.LobbyCommand> lobbySender, Runnable onLeave) {
        this.myClientId = myClientId;
        this.sender = sender;
        this.lobbySender = lobbySender;
        this.onLeave = onLeave;
        this.log = new LogView(chatSender);
        getStyleClass().add("game-screen");

        promptBar.getStyleClass().add("prompt-bar");
        promptBar.setAlignment(Pos.CENTER_LEFT);
        promptBar.setPadding(new Insets(6, 12, 6, 12));
        promptBar.setMinHeight(44);
        promptLabel.getStyleClass().add("prompt-label");
        promptLabel.setWrapText(true);
        HBox.setHgrow(promptLabel, Priority.ALWAYS);
        promptLabel.setMaxWidth(Double.MAX_VALUE);
        promptBar.getChildren().addAll(promptLabel, promptButtons);
        VBox.setMargin(promptBar, new Insets(6, 10, 4, 10));

        toast.getStyleClass().add("toast");
        toast.setVisible(false);
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toast, new Insets(0, 0, 18, 0));
        overlay.setVisible(false);
        overlay.setPickOnBounds(true);

        StackPane boardStack = new StackPane(board, toast, overlay);
        VBox center = new VBox(promptBar, boardStack);
        VBox.setVgrow(boardStack, Priority.ALWAYS);
        center.getStyleClass().add("board-area");
        setCenter(center);
        setTop(hud);
        VBox right = new VBox(6, players, log);
        right.setPrefWidth(320);
        right.setMinWidth(280);
        VBox.setVgrow(log, Priority.ALWAYS);
        right.getStyleClass().add("side-panel");
        setRight(right);
        HBox bottom = new HBox(8, hand, controls);
        HBox.setHgrow(hand, Priority.ALWAYS);
        bottom.getStyleClass().add("bottom-panel");
        bottom.setPrefHeight(205);
        setBottom(bottom);

        players.setOnSelect(i -> {
            focusedSeat = i;
            refreshViews();
        });
        players.setOnTakeSeat(i -> {
            if (Dialogs.confirm("Assumir jogador", "Assumir o controle de " + state.player(i).name + " (desconectado)?")) {
                Packet.LobbyCommand c = new Packet.LobbyCommand();
                c.cmd = Packet.LobbyCommand.Cmd.TAKE_SEAT;
                c.seat = i;
                lobbySender.accept(c);
            }
        });
        hand.setOnCardClick(this::onCardClicked);
        board.setCityClickHandler(this::onCityClicked);
        addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE && flowActive) cancelFlow();
        });
        hud.discardsButton().setOnAction(e -> showDiscards());
        wireControls();
    }

    // ================================================================= state updates

    public void update(GameState s, List<Packet.Seat> seatList) {
        GameState old = this.state;
        this.state = s;
        this.seats = seatList;
        this.mySeats = seatList.stream().filter(x -> myClientId.equals(x.ownerId)).map(x -> x.seat).collect(Collectors.toSet());
        if (focusedSeat < 0 || old == null || old.turn != s.turn) focusedSeat = actingSeat();
        String sig = signature(s);
        if (flowActive && !sig.equals(flowSignature)) cancelFlow();
        log.syncLog(s.log);
        refreshViews();
        if (s.isOver()) showGameOver();
    }

    public void appendChat(String sender, String text) {
        log.appendChat(sender, text);
    }

    public void showError(String message) {
        log.appendSystem("Ação recusada: " + message, true);
        showToast(message);
    }

    public void showNotice(String message) {
        log.appendSystem(message, false);
        showToast(message);
    }

    private void showToast(String message) {
        toast.setText(message);
        toast.setVisible(true);
        PauseTransition p = new PauseTransition(Duration.seconds(4));
        p.setOnFinished(e -> toast.setVisible(false));
        p.play();
    }

    private static String signature(GameState s) {
        return s.phase + "/" + s.currentPlayer + "/" + s.actionsLeft + "/" + s.choices.size() + "/" + (s.consent != null)
                + "/" + (s.resolution != null);
    }

    /** The seat this client acts as right now. */
    private int actingSeat() {
        if (state == null || mySeats.isEmpty()) return 0;
        if (state.consent != null) {
            for (int a : state.consent.awaiting) if (mySeats.contains(a)) return a;
            if (mySeats.contains(state.consent.actor)) return state.consent.actor;
        }
        PendingChoice c = RuleValidator.blockingChoice(state);
        if (c != null && mySeats.contains(c.player)) return c.player;
        for (PendingChoice d : state.choices) {
            if ((d.type == PendingChoice.Type.DISCARD || d.type == PendingChoice.Type.START_CITY) && mySeats.contains(d.player)) return d.player;
        }
        if (mySeats.contains(state.currentPlayer)) return state.currentPlayer;
        return Collections.min(mySeats);
    }

    private void refreshViews() {
        if (state == null) return;
        hud.update(state);
        int acting = actingSeat();
        if (focusedSeat < 0) focusedSeat = acting;
        board.setState(state, mySeats);
        players.update(state, seats, mySeats, focusedSeat);
        refreshControls(acting);
        refreshPrompt(acting);
        refreshHand(acting);
    }

    private void refreshHand(int acting) {
        int seat = focusedSeat >= 0 ? focusedSeat : acting;
        PendingChoice discard = RuleValidator.discardChoiceFor(state, seat);
        String hint = null;
        if (discard != null && mySeats.contains(seat)) hint = "Clique numa carta para descartar ou jogar um Evento.";
        else if (mySeats.contains(seat) && state.player(seat).hand.stream().anyMatch(PlayerCard::isEvent)) {
            hint = "Clique num Evento para jogá-lo (não custa ação).";
        }
        hand.show(state, seat, mySeats.contains(seat), null, hint);
    }

    // ================================================================= controls

    private void wireControls() {
        controls.setOnAction(ActionControlsView.Key.CARRIAGE, this::flowCarriage);
        controls.setOnAction(ActionControlsView.Key.TRAIN, this::flowTrain);
        controls.setOnAction(ActionControlsView.Key.SHIP, this::flowShip);
        controls.setOnAction(ActionControlsView.Key.RAIL, this::flowRailroad);
        controls.setOnAction(ActionControlsView.Key.HOSPITAL, this::flowHospital);
        controls.setOnAction(ActionControlsView.Key.TREAT, this::flowTreat);
        controls.setOnAction(ActionControlsView.Key.PURIFY, this::flowPurify);
        controls.setOnAction(ActionControlsView.Key.SHARE, this::flowShare);
        controls.setOnAction(ActionControlsView.Key.RESEARCH, this::flowResearch);
        controls.setOnAction(ActionControlsView.Key.ROLE1, () -> flowRole(1));
        controls.setOnAction(ActionControlsView.Key.ROLE2, () -> flowRole(2));
        controls.setOnAction(ActionControlsView.Key.END_ACTIONS, () -> {
            if (state.actionsLeft <= 0 || Dialogs.confirm("Encerrar ações", "Abrir mão de " + state.actionsLeft + " ação(ões)?")) {
                send(GameAction.of(ActionType.END_ACTIONS, state.currentPlayer));
            }
        });
        controls.setOnAction(ActionControlsView.Key.DRAW, () -> send(GameAction.of(ActionType.DRAW_CARDS, state.currentPlayer)));
        controls.setOnAction(ActionControlsView.Key.EPIDEMIC, () -> send(GameAction.of(ActionType.RESOLVE_EPIDEMIC, state.currentPlayer)));
        controls.setOnAction(ActionControlsView.Key.INFECT, () -> send(GameAction.of(ActionType.INFECT_NEXT, state.currentPlayer)));
        controls.setOnAction(ActionControlsView.Key.INFECT_ALL, () -> send(GameAction.of(ActionType.INFECT_ALL, state.currentPlayer)));
    }

    private void refreshControls(int acting) {
        controls.disableAll();
        Player me = state.player(acting);
        switch (me.role) {
            case AGRONOMIST:
                controls.configureRole(ActionControlsView.Key.ROLE1, "Agrônomo: ficha", Role.AGRONOMIST.ability1);
                controls.configureRole(ActionControlsView.Key.ROLE2, null, null);
                break;
            case POLITICIAN:
                controls.configureRole(ActionControlsView.Key.ROLE1, "Político: enviar", Role.POLITICIAN.ability1);
                controls.configureRole(ActionControlsView.Key.ROLE2, "Político: trocar", Role.POLITICIAN.ability2);
                break;
            case ROYAL_ACADEMY_SCIENTIST:
                controls.configureRole(ActionControlsView.Key.ROLE1, "Cientista: ver 3", Role.ROYAL_ACADEMY_SCIENTIST.ability2);
                controls.configureRole(ActionControlsView.Key.ROLE2, null, null);
                break;
            default:
                controls.configureRole(ActionControlsView.Key.ROLE1, null, null);
                controls.configureRole(ActionControlsView.Key.ROLE2, null, null);
        }
        if (state.isOver() || !mySeats.contains(acting)) {
            board.setMoveHints(Collections.emptySet());
            return;
        }
        boolean act = RuleValidator.canAct(state, acting);
        boolean free = RuleValidator.hasFreeMove(state, acting);
        int city = me.city;
        if (act || free) {
            controls.setEnabled(ActionControlsView.Key.CARRIAGE, true);
            controls.setEnabled(ActionControlsView.Key.TRAIN, !RuleValidator.railReachable(state, city).isEmpty());
            controls.setEnabled(ActionControlsView.Key.SHIP, !RuleValidator.shipDestinations(state, acting).isEmpty());
        }
        if (act) {
            controls.setEnabled(ActionControlsView.Key.RAIL, !RuleValidator.buildableRoutes(state, city).isEmpty());
            controls.setEnabled(ActionControlsView.Key.HOSPITAL, me.hand.contains(city)
                    && state.hospitals[B.city(city).color.ordinal()] != city);
            controls.setEnabled(ActionControlsView.Key.TREAT, state.totalCubes(city) > 0);
            controls.setEnabled(ActionControlsView.Key.PURIFY, state.purificationSupply > 0
                    && Arrays.stream(B.regionsOf(city)).anyMatch(r -> !RuleValidator.purifyCards(state, acting, r).isEmpty()));
            boolean others = !RuleValidator.playersInCity(state, city, acting).isEmpty();
            boolean shareable = others && (me.hand.contains(city)
                    || RuleValidator.playersInCity(state, city, acting).stream().anyMatch(o -> state.player(o).hand.contains(city)));
            controls.setEnabled(ActionControlsView.Key.SHARE, shareable);
            int hc = RuleValidator.researchHospitalColorAt(state, city);
            controls.setEnabled(ActionControlsView.Key.RESEARCH, hc >= 0 && !state.researched[hc]
                    && RuleValidator.researchCandidates(state, acting, DiseaseType.values()[hc]).size() >= GameState.RESEARCH_CARDS);
            switch (me.role) {
                case AGRONOMIST:
                    controls.setEnabled(ActionControlsView.Key.ROLE1, state.purificationSupply > 0);
                    break;
                case POLITICIAN:
                    controls.setEnabled(ActionControlsView.Key.ROLE1, me.hand.contains(city));
                    controls.setEnabled(ActionControlsView.Key.ROLE2, me.hand.stream().anyMatch(PlayerCard::isCity)
                            && state.playerDiscard.stream().anyMatch(PlayerCard::isCity));
                    break;
                case ROYAL_ACADEMY_SCIENTIST:
                    controls.setEnabled(ActionControlsView.Key.ROLE1, state.playerDeckSize() > 0);
                    break;
                default:
                    break;
            }
        }
        boolean myTurnFree = state.currentPlayer == acting && mySeats.contains(state.currentPlayer) && RuleValidator.isFree(state);
        controls.setEnabled(ActionControlsView.Key.END_ACTIONS, myTurnFree && state.phase == GamePhase.ACTIONS);
        controls.setEnabled(ActionControlsView.Key.DRAW, myTurnFree && state.phase == GamePhase.DRAW);
        controls.setEnabled(ActionControlsView.Key.EPIDEMIC, myTurnFree && state.phase == GamePhase.EPIDEMIC_PENDING);
        controls.setEnabled(ActionControlsView.Key.INFECT, myTurnFree && state.phase == GamePhase.INFECTION);
        controls.setEnabled(ActionControlsView.Key.INFECT_ALL, myTurnFree && state.phase == GamePhase.INFECTION);

        Set<Integer> hints = new HashSet<>();
        if ((act || free) && !flowActive) {
            hints.addAll(RuleValidator.carriageDestinations(state, acting));
            hints.addAll(RuleValidator.railReachable(state, city));
        }
        board.setMoveHints(hints);
    }

    // ================================================================= prompt bar

    private void setPrompt(String text, Button... buttons) {
        promptLabel.setText(text == null ? "" : text);
        promptButtons.getChildren().setAll(buttons);
    }

    private Button button(String label, Runnable r) {
        Button b = new Button(label);
        b.getStyleClass().add("prompt-button");
        b.setOnAction(e -> r.run());
        return b;
    }

    private void refreshPrompt(int acting) {
        if (state.isOver()) {
            setPrompt(state.won ? "Vitória!" : "Derrota.");
            return;
        }
        // 1. consent requests
        if (state.consent != null) {
            for (int a : state.consent.awaiting) {
                if (mySeats.contains(a)) {
                    final int seat = a;
                    setPrompt(state.player(a).name + ": " + state.consent.description,
                            button("Aceitar", () -> send(GameAction.of(ActionType.CONSENT_REPLY, seat).accept(true))),
                            button("Recusar", () -> send(GameAction.of(ActionType.CONSENT_REPLY, seat).accept(false))));
                    return;
                }
            }
            if (mySeats.contains(state.consent.actor)) {
                int actor = state.consent.actor;
                setPrompt("Aguardando resposta: " + state.consent.description,
                        button("Cancelar pedido", () -> send(GameAction.of(ActionType.CANCEL_REQUEST, actor))));
            } else {
                setPrompt("Pedido em andamento: " + state.consent.description);
            }
            return;
        }
        // 2. blocking choice
        PendingChoice c = RuleValidator.blockingChoice(state);
        if (c != null) {
            if (mySeats.contains(c.player)) {
                handleMyChoice(c);
            } else {
                setPrompt("Aguardando " + state.player(c.player).name + ": " + c.prompt);
                board.clearSelection();
            }
            return;
        }
        // 3. hand-limit discards
        for (PendingChoice d : state.choices) {
            if (d.type == PendingChoice.Type.DISCARD && mySeats.contains(d.player)) {
                focusedSeat = d.player;
                setPrompt(d.prompt + " Clique numa carta da mão.");
                return;
            }
        }
        // 4. setup
        if (state.phase == GamePhase.SETUP) {
            for (PendingChoice d : state.choices) {
                if (d.type == PendingChoice.Type.START_CITY && mySeats.contains(d.player)) {
                    String key = "start" + d.player + "/" + state.version;
                    setPrompt(d.prompt + " (cidades destacadas no mapa)");
                    if (!key.equals(autoSelectionKey) || board.mode() == BoardCanvas.Mode.NONE) {
                        autoSelectionKey = key;
                        final int seat = d.player;
                        board.selectCity(new HashSet<>(d.options), city -> send(GameAction.of(ActionType.CHOOSE_START_CITY, seat).city(city)));
                    }
                    return;
                }
            }
            setPrompt("Aguardando os outros jogadores escolherem suas cidades iniciais…");
            return;
        }
        for (PendingChoice d : state.choices) {
            if (d.type == PendingChoice.Type.DISCARD) {
                setPrompt("Aguardando " + state.player(d.player).name + " descartar até 7 cartas.");
                return;
            }
        }
        // 5. user flow in progress
        if (flowActive) {
            setPrompt(flowPrompt, flowButtons.toArray(new Button[0]));
            return;
        }
        if (board.mode() != BoardCanvas.Mode.NONE) board.clearSelection();
        // 6. status
        Player cur = state.current();
        boolean mine = mySeats.contains(state.currentPlayer);
        switch (state.phase) {
            case ACTIONS:
                setPrompt(mine ? "Sua vez, " + cur.name + ": " + state.actionsLeft
                        + " ação(ões). Clique numa cidade destacada para viajar ou use a barra de ações."
                        : "Vez de " + cur.name + " (" + cur.role.title + ") — " + state.actionsLeft + " ação(ões) restante(s).");
                break;
            case DRAW:
                setPrompt(mine ? cur.name + ": compre 2 cartas do baralho de Jogador." : cur.name + " vai comprar 2 cartas.",
                        mine ? new Button[]{button("Comprar 2 cartas", () -> send(GameAction.of(ActionType.DRAW_CARDS, cur.id)))} : new Button[0]);
                break;
            case EPIDEMIC_PENDING:
                setPrompt("Segunda Epidemia! Eventos podem ser jogados agora.",
                        mine ? new Button[]{button("Resolver epidemia", () -> send(GameAction.of(ActionType.RESOLVE_EPIDEMIC, cur.id)))} : new Button[0]);
                break;
            case INFECTION:
                setPrompt(mine ? cur.name + ": revele " + state.infectionsLeft + " carta(s) de Infecção." : cur.name + " está infectando cidades…",
                        mine ? new Button[]{button("Infectar", () -> send(GameAction.of(ActionType.INFECT_NEXT, cur.id))),
                                button("Todas", () -> send(GameAction.of(ActionType.INFECT_ALL, cur.id)))} : new Button[0]);
                break;
            default:
                setPrompt("");
        }
    }

    private void handleMyChoice(PendingChoice c) {
        String key = c.type + "/" + c.player + "/" + state.version;
        switch (c.type) {
            case NURSE_TOKEN:
            case PURIFICATION_REMOVAL:
                setPrompt(c.prompt + " (regiões destacadas)");
                if (!key.equals(autoSelectionKey) || board.mode() == BoardCanvas.Mode.NONE) {
                    autoSelectionKey = key;
                    board.selectRegion(new HashSet<>(c.options), r -> send(GameAction.of(ActionType.CHOOSE, c.player).region(r)));
                }
                break;
            case SCIENTIST_ORDER:
                setPrompt(c.prompt, button("Reordenar", () -> Dialogs.reorder("Cientista da Academia Real",
                        "Ordem das próximas cartas (esquerda = topo do baralho)", c.options)
                        .ifPresent(order -> send(GameAction.of(ActionType.CHOOSE, c.player).values(order)))));
                if (!key.equals(autoSelectionKey)) {
                    autoSelectionKey = key;
                    javafx.application.Platform.runLater(() -> Dialogs.reorder("Cientista da Academia Real",
                            "Ordem das próximas cartas (esquerda = topo do baralho)", c.options)
                            .ifPresent(order -> send(GameAction.of(ActionType.CHOOSE, c.player).values(order))));
                }
                break;
            case FREE_MOVE:
                focusedSeat = c.player;
                if (flowActive) {
                    setPrompt(flowPrompt, flowButtons.toArray(new Button[0]));
                } else {
                    setPrompt(c.prompt + " Clique numa cidade destacada ou use Carruagem/Trem/Navio.",
                            button("Passar", () -> send(GameAction.of(ActionType.CHOOSE, c.player).accept(false))));
                }
                break;
            default:
                setPrompt(c.prompt);
        }
    }

    // ================================================================= flows

    private void send(GameAction a) {
        sender.accept(a);
    }

    private void startFlow(String prompt, Button... extra) {
        flowActive = true;
        flowSignature = signature(state);
        flowPrompt = prompt;
        flowButtons = new ArrayList<>(Arrays.asList(extra));
        flowButtons.add(button("Cancelar", this::cancelFlow));
        setPrompt(flowPrompt, flowButtons.toArray(new Button[0]));
        board.setMoveHints(Collections.emptySet());
    }

    private void endFlow() {
        flowActive = false;
        board.clearSelection();
        refreshViews();
    }

    private void cancelFlow() {
        flowActive = false;
        board.clearSelection();
        if (state != null) refreshViews();
    }

    private void flowCarriage() {
        int me = actingSeat();
        startFlow("Carruagem/Barco: escolha uma cidade ligada à sua.");
        board.selectCity(RuleValidator.carriageDestinations(state, me), c -> {
            endFlow();
            send(GameAction.of(ActionType.MOVE_CARRIAGE, me).city(c));
        });
    }

    private void flowTrain() {
        int me = actingSeat();
        startFlow("Trem: escolha uma cidade ligada por ferrovia contínua.");
        board.selectCity(RuleValidator.railReachable(state, state.player(me).city), c -> {
            endFlow();
            sendTrain(me, c);
        });
    }

    private void sendTrain(int me, int dest) {
        GameAction a = GameAction.of(ActionType.MOVE_TRAIN, me).city(dest);
        if (!askPassenger(a, Role.RAILWAYMAN)) return;
        send(a);
    }

    /** Railwayman/Sailor may take one pawn from their city. Returns false if cancelled. */
    private boolean askPassenger(GameAction a, Role role) {
        Player p = state.player(a.player);
        if (!p.is(role) || RuleValidator.hasFreeMove(state, a.player)) return true;
        List<Integer> others = RuleValidator.playersInCity(state, p.city, a.player);
        if (others.isEmpty()) return true;
        List<Integer> opts = new ArrayList<>();
        opts.add(-1);
        opts.addAll(others);
        Optional<Integer> pick = Dialogs.chooseButtons("Passageiro", p.role.title + ": levar alguém junto?", opts,
                i -> i < 0 ? "Viajar sozinho" : "Levar " + state.player(i).name + " (" + state.player(i).role.title + ")");
        if (pick.isEmpty()) return false;
        a.passenger = pick.get();
        return true;
    }

    private void flowShip() {
        int me = actingSeat();
        startFlow("Navio: escolha o porto de destino.");
        board.selectCity(RuleValidator.shipDestinations(state, me), c -> {
            endFlow();
            sendShip(me, c, null);
        });
    }

    private void sendShip(int me, int dest, Integer card) {
        GameAction a = GameAction.of(ActionType.MOVE_SHIP, me).city(dest);
        if (!state.player(me).is(Role.SAILOR)) {
            Integer use = card;
            if (use == null) {
                Optional<Integer> pick = Dialogs.chooseCard("Navio", "Descarte uma carta " + B.city(dest).color.colorName
                        + " para navegar até " + B.city(dest).name, RuleValidator.shipCards(state, me, dest));
                if (pick.isEmpty()) return;
                use = pick.get();
            }
            a.cards(use);
        }
        if (!askPassenger(a, Role.SAILOR)) return;
        send(a);
    }

    private void onCityClicked(int city, double sx, double sy) {
        if (state == null) return;
        int me = actingSeat();
        if (!mySeats.contains(me) || !(RuleValidator.canAct(state, me) || RuleValidator.hasFreeMove(state, me))) return;
        Player p = state.player(me);
        ContextMenu menu = new ContextMenu();
        if (B.adjacent(p.city, city)) {
            boolean dashed = B.routeBetween(p.city, city).dashed;
            MenuItem m = new MenuItem((dashed ? "Barco" : "Carruagem") + " até " + B.city(city).name);
            m.setOnAction(e -> send(GameAction.of(ActionType.MOVE_CARRIAGE, me).city(city)));
            menu.getItems().add(m);
        }
        if (RuleValidator.railReachable(state, p.city).contains(city)) {
            MenuItem m = new MenuItem("Trem até " + B.city(city).name);
            m.setOnAction(e -> sendTrain(me, city));
            menu.getItems().add(m);
        }
        if (RuleValidator.shipDestinations(state, me).contains(city)) {
            if (p.is(Role.SAILOR)) {
                MenuItem m = new MenuItem("Navio até " + B.city(city).name + " (sem carta)");
                m.setOnAction(e -> sendShip(me, city, null));
                menu.getItems().add(m);
            } else {
                for (int card : RuleValidator.shipCards(state, me, city)) {
                    MenuItem m = new MenuItem("Navio até " + B.city(city).name + " (descartar " + PlayerCard.name(card) + ")");
                    m.setOnAction(e -> sendShip(me, city, card));
                    menu.getItems().add(m);
                }
            }
        }
        if (menu.getItems().isEmpty()) return;
        menu.getItems().add(new SeparatorMenuItem());
        MenuItem cancel = new MenuItem("Cancelar");
        menu.getItems().add(cancel);
        menu.show(board, sx, sy);
    }

    private void flowRailroad() {
        int me = actingSeat();
        int city = state.player(me).city;
        startFlow("Construir ferrovia: escolha uma linha que sai da sua cidade.");
        board.selectRoute(RuleValidator.buildableRoutes(state, city), r1 -> {
            if (RuleValidator.canUseRailwaymanDouble(state, me) && !RuleValidator.secondRailRoutes(state, city, r1).isEmpty()) {
                board.setMarked(Set.of(r1));
                flowPrompt = "Ferroviário: escolha o 2º trilho consecutivo (ou conclua com 1).";
                flowButtons = new ArrayList<>(List.of(
                        button("Só 1 trilho", () -> {
                            endFlow();
                            send(GameAction.of(ActionType.BUILD_RAILROAD, me).route(r1));
                        }),
                        button("Cancelar", this::cancelFlow)));
                setPrompt(flowPrompt, flowButtons.toArray(new Button[0]));
                board.selectRoute(RuleValidator.secondRailRoutes(state, city, r1), r2 -> {
                    endFlow();
                    send(GameAction.of(ActionType.BUILD_RAILROAD, me).route(r1).route2(r2));
                });
                board.setMarked(Set.of(r1));
            } else {
                endFlow();
                send(GameAction.of(ActionType.BUILD_RAILROAD, me).route(r1));
            }
        });
    }

    private void flowHospital() {
        int me = actingSeat();
        City c = B.city(state.player(me).city);
        int old = state.hospitals[c.color.ordinal()];
        String text = "Descartar a carta " + c.name + " para " + (old < 0 ? "construir" : "transferir de " + B.city(old).name)
                + " o hospital " + c.color.colorName + " em " + c.name + "?";
        if (Dialogs.confirm("Construir hospital", text)) send(GameAction.of(ActionType.BUILD_HOSPITAL, me));
    }

    private void flowTreat() {
        int me = actingSeat();
        Player p = state.player(me);
        Optional<DiseaseType> col = Dialogs.chooseColor("Tratar doença", "Qual cubo remover de " + B.city(p.city).name + "?",
                RuleValidator.colorsIn(state, p.city));
        if (col.isEmpty()) return;
        GameAction a = GameAction.of(ActionType.TREAT_DISEASE, me).color(col.get());
        if (p.is(Role.RURAL_DOCTOR)) {
            Set<Integer> cities = new LinkedHashSet<>();
            for (int c : RuleValidator.ruralDoctorCities(state, me)) {
                int avail = state.totalCubes(c) - (c == p.city ? 1 : 0);
                if (avail > 0) cities.add(c);
            }
            if (!cities.isEmpty()) {
                startFlow("Médico Rural: escolha onde remover o cubo adicional.",
                        button("Sem cubo extra", () -> {
                            endFlow();
                            send(a);
                        }));
                board.selectCity(cities, c2 -> {
                    endFlow();
                    List<DiseaseType> cols = new ArrayList<>();
                    for (DiseaseType d : DiseaseType.values()) {
                        int avail = state.cubesAt(c2, d) - (c2 == p.city && d == col.get() ? 1 : 0);
                        if (avail > 0) cols.add(d);
                    }
                    Optional<DiseaseType> col2 = Dialogs.chooseColor("Médico Rural", "Cubo adicional em " + B.city(c2).name, cols);
                    if (col2.isEmpty()) return;
                    send(a.city2(c2).color2(col2.get()));
                });
                return;
            }
        }
        send(a);
    }

    private void flowPurify() {
        int me = actingSeat();
        int city = state.player(me).city;
        Set<Integer> regions = new LinkedHashSet<>();
        for (int r : B.regionsOf(city)) if (!RuleValidator.purifyCards(state, me, r).isEmpty()) regions.add(r);
        startFlow("Purificar água: escolha uma região adjacente.");
        board.selectRegion(regions, r -> {
            endFlow();
            Optional<Integer> card = Dialogs.chooseCard("Purificar água", "Descarte uma carta para purificar " + B.regionLabel(r),
                    RuleValidator.purifyCards(state, me, r));
            card.ifPresent(cd -> send(GameAction.of(ActionType.PURIFY_WATER, me).region(r).cards(cd)));
        });
    }

    private void flowShare() {
        int me = actingSeat();
        int city = state.player(me).city;
        List<String> labels = new ArrayList<>();
        List<GameAction> options = new ArrayList<>();
        for (int o : RuleValidator.playersInCity(state, city, me)) {
            if (state.player(me).hand.contains(city)) {
                labels.add("Dar " + B.city(city).name + " para " + state.player(o).name);
                options.add(GameAction.of(ActionType.SHARE_GIVE, me).target(o));
            }
            if (state.player(o).hand.contains(city)) {
                labels.add("Pegar " + B.city(city).name + " de " + state.player(o).name);
                options.add(GameAction.of(ActionType.SHARE_TAKE, me).target(o));
            }
        }
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) idx.add(i);
        Dialogs.chooseButtons("Compartilhar conhecimento", "O outro jogador precisa concordar.", idx, labels::get)
                .ifPresent(i -> send(options.get(i)));
    }

    private void flowResearch() {
        int me = actingSeat();
        int city = state.player(me).city;
        DiseaseType col = DiseaseType.values()[RuleValidator.researchHospitalColorAt(state, city)];
        List<Integer> cands = RuleValidator.researchCandidates(state, me, col);
        Dialogs.chooseCards("Pesquisar " + col.diseaseName, "Escolha 5 cartas " + col.colorName + "s", cands, 5, 5)
                .ifPresent(cards -> send(GameAction.of(ActionType.RESEARCH, me).cards(cards)));
    }

    private void flowRole(int which) {
        int me = actingSeat();
        Player p = state.player(me);
        switch (p.role) {
            case AGRONOMIST: {
                Set<Integer> regions = new LinkedHashSet<>();
                for (int r : B.regionsOf(p.city)) regions.add(r);
                startFlow("Agrônomo: escolha a região que recebe 1 ficha de purificação.");
                board.selectRegion(regions, r -> {
                    endFlow();
                    send(GameAction.of(ActionType.AGRONOMIST_PURIFY, me).region(r));
                });
                break;
            }
            case POLITICIAN:
                if (which == 1) {
                    List<Integer> others = new ArrayList<>();
                    for (Player o : state.players) if (o.id != me) others.add(o.id);
                    Dialogs.chooseButtons("Político", "Enviar a carta " + B.city(p.city).name + " para:", others,
                            i -> state.player(i).name + " (" + B.city(state.player(i).city).name + ")")
                            .ifPresent(t -> send(GameAction.of(ActionType.POLITICIAN_GIVE, me).target(t)));
                } else {
                    politicianSwap(me);
                }
                break;
            case ROYAL_ACADEMY_SCIENTIST:
                if (Dialogs.confirm("Cientista da Academia Real", "Gastar 1 ação para ver e reordenar as 3 próximas cartas?")) {
                    send(GameAction.of(ActionType.SCIENTIST_PEEK, me));
                }
                break;
            default:
                break;
        }
    }

    private void politicianSwap(int me) {
        Player p = state.player(me);
        List<Integer> mine = p.hand.stream().filter(PlayerCard::isCity).collect(Collectors.toList());
        Optional<Integer> give = Dialogs.chooseCard("Político — trocar", "Carta da sua mão que vai para o descarte", mine);
        if (give.isEmpty()) return;
        List<Integer> disc = state.playerDiscard.stream().filter(PlayerCard::isCity)
                .filter(c -> give.get() == p.city || c == p.city).collect(Collectors.toList());
        if (disc.isEmpty()) {
            showError("Você precisa estar na cidade de uma das cartas trocadas.");
            return;
        }
        Dialogs.chooseCard("Político — trocar", "Carta do descarte que você pega", disc)
                .ifPresent(d -> send(GameAction.of(ActionType.POLITICIAN_SWAP, me).cards(give.get(), d)));
    }

    // ================================================================= cards & events

    private void onCardClicked(int seat, int card) {
        if (state == null || state.isOver()) return;
        PendingChoice discard = RuleValidator.discardChoiceFor(state, seat);
        if (PlayerCard.isEvent(card)) {
            EventType e = PlayerCard.event(card);
            List<String> opts = new ArrayList<>();
            opts.add("Jogar \"" + e.title + "\"");
            if (discard != null) opts.add("Descartar");
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < opts.size(); i++) idx.add(i);
            Optional<Integer> pick = Dialogs.chooseButtons(e.title, e.text, idx, opts::get);
            if (pick.isEmpty()) return;
            if (pick.get() == 0) playEvent(seat, e, null);
            else send(GameAction.of(ActionType.DISCARD_CARD, seat).cards(card));
            return;
        }
        if (discard != null) {
            send(GameAction.of(ActionType.DISCARD_CARD, seat).cards(card));
        }
    }

    private void playEvent(int seat, EventType event, EventType sub) {
        if (!RuleValidator.canPlayEvents(state)) {
            showError("Eventos não podem ser jogados agora.");
            return;
        }
        EventType e = sub != null ? sub : event;
        GameAction a = GameAction.of(ActionType.PLAY_EVENT, seat).event(event);
        if (sub != null) a.subEvent(sub);
        Player p = state.player(seat);
        switch (e) {
            case WHEN_THE_PLANS_WERE_GOOD: {
                List<EventType> inDiscard = state.playerDiscard.stream().filter(PlayerCard::isEvent).map(PlayerCard::event)
                        .filter(x -> x != EventType.WHEN_THE_PLANS_WERE_GOOD).distinct().collect(Collectors.toList());
                if (inDiscard.isEmpty()) {
                    showError("Não há Eventos no descarte.");
                    return;
                }
                Dialogs.chooseButtons("Quando os Planos Eram Bons", "Escolha o Evento do descarte:", inDiscard, x -> x.title)
                        .ifPresent(x -> playEvent(seat, event, x));
                return;
            }
            case GOOD_SEASON:
            case GOVERNMENT_MOBILIZATION:
            case ONE_MORE_DAY:
            case SECOND_CHANCE:
                if (Dialogs.confirm(e.title, e.text)) send(a);
                return;
            case HOSPITAL_FOUNDING:
                Dialogs.chooseColor(e.title, "Qual hospital?", Arrays.asList(DiseaseType.values())).ifPresent(col -> {
                    Set<Integer> cities = B.cities.stream().filter(c -> c.color == col && state.hospitals[col.ordinal()] != c.id)
                            .map(c -> c.id).collect(Collectors.toSet());
                    startFlow(e.title + ": escolha uma cidade " + col.colorName + ".");
                    board.selectCity(cities, c -> {
                        endFlow();
                        send(a.color(col).city(c));
                    });
                });
                return;
            case NEW_RAILS:
            case RING_RAILROADS:
                pickRoutes(a, e, e == EventType.NEW_RAILS ? 2 : 3, new ArrayList<>());
                return;
            case PURIFY_WATER:
                pickRegions(a, new ArrayList<>());
                return;
            case OVERSEAS_MIGRATION:
                pickCubes(a, new ArrayList<>());
                return;
            case SCIENCE_TRIUMPH: {
                Set<Integer> regions = new HashSet<>();
                for (int r = 0; r < B.regions.size(); r++) {
                    for (int c : B.region(r).cities) if (state.totalCubes(c) > 0) regions.add(r);
                }
                startFlow(e.title + ": escolha a região.");
                board.selectRegion(regions, r -> {
                    endFlow();
                    List<String> labels = new ArrayList<>();
                    List<List<String>> options = new ArrayList<>();
                    List<Integer> cities = new ArrayList<>();
                    List<List<DiseaseType>> colors = new ArrayList<>();
                    for (int c : B.region(r).cities) {
                        List<DiseaseType> cols = RuleValidator.colorsIn(state, c);
                        if (cols.isEmpty()) continue;
                        cities.add(c);
                        colors.add(cols);
                        labels.add(B.city(c).name);
                        List<String> o = new ArrayList<>();
                        for (DiseaseType d : cols) o.add("Remover " + d.colorName);
                        o.add("Nada");
                        options.add(o);
                    }
                    Dialogs.form(e.title, "Até 1 cubo de cada cidade da região", labels, options).ifPresent(sel -> {
                        List<Integer> v = new ArrayList<>();
                        for (int i = 0; i < sel.length; i++) {
                            if (sel[i] < colors.get(i).size()) {
                                v.add(cities.get(i));
                                v.add(colors.get(i).get(sel[i]).ordinal());
                            }
                        }
                        send(a.region(r).values(v));
                    });
                });
                return;
            }
            case MAIL_CORRESPONDENCE: {
                List<String> names = state.players.stream().map(x -> x.name).collect(Collectors.toList());
                Dialogs.form(e.title, "Dois jogadores trocam 1 carta", List.of("Jogador A", "Jogador B"),
                        List.of(names, names)).ifPresent(sel -> {
                    if (sel[0] == sel[1]) {
                        showError("Escolha dois jogadores diferentes.");
                        return;
                    }
                    int eventCard = PlayerCard.ofEvent(event);
                    List<Integer> ha = state.player(sel[0]).hand.stream().filter(c -> c != eventCard || sel[0] != seat).collect(Collectors.toList());
                    List<Integer> hb = state.player(sel[1]).hand.stream().filter(c -> c != eventCard || sel[1] != seat).collect(Collectors.toList());
                    if (ha.isEmpty() || hb.isEmpty()) {
                        showError("Os dois precisam ter cartas.");
                        return;
                    }
                    Optional<Integer> ca = Dialogs.chooseCard(e.title, "Carta de " + state.player(sel[0]).name, ha);
                    if (ca.isEmpty()) return;
                    Optional<Integer> cb = Dialogs.chooseCard(e.title, "Carta de " + state.player(sel[1]).name, hb);
                    if (cb.isEmpty()) return;
                    send(a.values(sel[0], ca.get(), sel[1], cb.get()));
                });
                return;
            }
            case TELEGRAPH_MESSAGE: {
                List<String> names = state.players.stream().map(x -> x.name).collect(Collectors.toList());
                Dialogs.form(e.title, "Um jogador dá 1 ou 2 cartas de Cidade a outro", List.of("Quem dá", "Quem recebe"),
                        List.of(names, names)).ifPresent(sel -> {
                    if (sel[0] == sel[1]) {
                        showError("Escolha dois jogadores diferentes.");
                        return;
                    }
                    List<Integer> cards = state.player(sel[0]).hand.stream().filter(PlayerCard::isCity).collect(Collectors.toList());
                    if (cards.isEmpty()) {
                        showError(state.player(sel[0]).name + " não tem cartas de Cidade.");
                        return;
                    }
                    Dialogs.chooseCards(e.title, "Cartas que " + state.player(sel[0]).name + " envia", cards, 1, 2).ifPresent(cs -> {
                        List<Integer> v = new ArrayList<>(List.of(sel[0], sel[1]));
                        v.addAll(cs);
                        send(a.values(v));
                    });
                });
                return;
            }
            case SHIPS_ARRIVE: {
                Set<Integer> ports = B.cities.stream().filter(c -> c.port).map(c -> c.id).collect(Collectors.toSet());
                startFlow(e.title + ": escolha a cidade portuária.");
                board.selectCity(ports, port -> {
                    endFlow();
                    List<Integer> all = state.players.stream().map(x -> x.id).filter(i -> state.player(i).city != port)
                            .collect(Collectors.toList());
                    Dialogs.chooseMany(e.title, "Quais peões vão para " + B.city(port).name + "? (com permissão)", all,
                            i -> state.player(i).name + " (" + B.city(state.player(i).city).name + ")", List.of(seat)).ifPresent(list -> {
                        if (list.isEmpty()) return;
                        send(a.city(port).values(list));
                    });
                });
                return;
            }
            case TRAVEL_DAY_AND_NIGHT: {
                List<Integer> all = state.players.stream().map(x -> x.id).collect(Collectors.toList());
                Dialogs.chooseButtons(e.title, "Qual peão viaja?", all, i -> state.player(i).name + " (" + state.player(i).role.title + ")")
                        .ifPresent(t -> {
                            Set<Integer> dests = B.cities.stream().map(c -> c.id).filter(c -> c != state.player(t).city)
                                    .collect(Collectors.toSet());
                            startFlow(e.title + ": escolha o destino de " + state.player(t).name + ".");
                            board.selectCity(dests, c -> {
                                endFlow();
                                send(a.target(t).city(c));
                            });
                        });
                return;
            }
            default:
                send(a);
        }
    }

    private void pickRoutes(GameAction a, EventType e, int max, List<Integer> chosen) {
        Set<Integer> options = new HashSet<>();
        for (Route r : B.routes) {
            if (chosen.contains(r.id) || !RuleValidator.canRail(state, r.id)) continue;
            if (e == EventType.RING_RAILROADS && !RuleValidator.isPortRoute(r.id)) continue;
            options.add(r.id);
        }
        if (chosen.size() >= Math.min(max, state.railSupply) || options.isEmpty()) {
            endFlow();
            if (!chosen.isEmpty()) send(a.values(chosen));
            return;
        }
        startFlow(e.title + ": escolha a linha " + (chosen.size() + 1) + " de até " + max + ".",
                button("Concluir", () -> {
                    endFlow();
                    if (!chosen.isEmpty()) send(a.values(chosen));
                }));
        board.setMarked(new HashSet<>(chosen));
        board.selectRoute(options, r -> {
            chosen.add(r);
            pickRoutes(a, e, max, chosen);
        });
        board.setMarked(new HashSet<>(chosen));
    }

    private void pickRegions(GameAction a, List<Integer> chosen) {
        if (chosen.size() >= Math.min(2, state.purificationSupply)) {
            endFlow();
            send(a.values(chosen));
            return;
        }
        Set<Integer> all = new HashSet<>();
        for (int r = 0; r < B.regions.size(); r++) all.add(r);
        startFlow("Purificar Água (Evento): escolha a região da ficha " + (chosen.size() + 1) + " de 2.",
                button("Concluir", () -> {
                    endFlow();
                    if (!chosen.isEmpty()) send(a.values(chosen));
                }));
        board.selectRegion(all, r -> {
            chosen.add(r);
            pickRegions(a, chosen);
        });
    }

    private void pickCubes(GameAction a, List<Integer> chosen) {
        if (chosen.size() >= 4) {
            endFlow();
            send(a.values(chosen));
            return;
        }
        Set<Integer> cities = new HashSet<>();
        for (City c : B.cities) {
            int already = 0;
            for (int i = 0; i < chosen.size(); i += 2) if (chosen.get(i) == c.id) already++;
            if (state.totalCubes(c.id) - already > 0) cities.add(c.id);
        }
        startFlow("Migração Ultramarina: escolha a cidade do cubo " + (chosen.size() / 2 + 1) + " de 2.",
                button("Concluir", () -> {
                    endFlow();
                    if (!chosen.isEmpty()) send(a.values(chosen));
                }));
        board.selectCity(cities, c -> {
            List<DiseaseType> cols = new ArrayList<>();
            for (DiseaseType d : DiseaseType.values()) {
                int used = 0;
                for (int i = 0; i < chosen.size(); i += 2) if (chosen.get(i) == c && chosen.get(i + 1) == d.ordinal()) used++;
                if (state.cubesAt(c, d) - used > 0) cols.add(d);
            }
            Optional<DiseaseType> col = Dialogs.chooseColor("Migração Ultramarina", "Qual cubo remover de " + B.city(c).name + "?", cols);
            if (col.isEmpty()) {
                cancelFlow();
                return;
            }
            chosen.add(c);
            chosen.add(col.get().ordinal());
            pickCubes(a, chosen);
        });
    }

    // ================================================================= misc

    private void showDiscards() {
        if (state == null) return;
        String pd = state.playerDiscard.isEmpty() ? "(vazio)" : state.playerDiscard.stream().map(Dialogs::cardLabel)
                .collect(Collectors.joining(", "));
        String id = state.infectionDiscard.isEmpty() ? "(vazio)" : state.infectionDiscard.stream().map(c -> B.city(c).name)
                .collect(Collectors.joining(", "));
        String removed = state.removedCards.isEmpty() ? "nenhuma" : String.valueOf(state.removedCards.size());
        Dialogs.info("Pilhas de descarte", "Descarte de Jogador (" + state.playerDiscard.size() + "):\n" + pd
                + "\n\nDescarte de Infecção (" + state.infectionDiscard.size() + "):\n" + id
                + "\n\nEpidemias já resolvidas: " + removed);
    }

    private void showGameOver() {
        if (overlay.isVisible()) return;
        Label title = new Label(state.won ? "VITÓRIA!" : "DERROTA");
        title.getStyleClass().add(state.won ? "gameover-win" : "gameover-lose");
        Label reason = new Label(state.gameOverReason);
        reason.getStyleClass().add("gameover-reason");
        reason.setWrapText(true);
        reason.setMaxWidth(520);
        Label stats = new Label("Turnos: " + state.turn + " · Surtos: " + state.outbreaks + " · Doenças pesquisadas: "
                + state.researchedCount() + "/4");
        stats.getStyleClass().add("gameover-reason");
        Button back = new Button("Voltar ao menu");
        back.setOnAction(e -> onLeave.run());
        Button stay = new Button("Ver o tabuleiro");
        stay.setOnAction(e -> overlay.setVisible(false));
        HBox buttons = new HBox(10, stay, back);
        buttons.setAlignment(Pos.CENTER);
        VBox box = new VBox(14, title, reason, stats, buttons);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("gameover-box");
        box.setMaxSize(600, 300);
        overlay.getChildren().setAll(box);
        overlay.setStyle("-fx-background-color: rgba(30,20,10,0.45);");
        overlay.setVisible(true);
    }
}
