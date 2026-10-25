package com.iberia.ui;

import com.iberia.network.GameServer;
import com.iberia.network.LanDiscovery;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

/** Start screen: choose a name, then host a LAN game or join one (direct IP or UDP discovery). */
public final class MainMenuScreen extends StackPane {
    /** Callbacks implemented by the application. */
    public interface Actions {
        void host(String name, int port);

        void join(String name, String address, int port);
    }

    private final TextField name = new TextField();
    private final TextField hostPort = new TextField(String.valueOf(GameServer.DEFAULT_PORT));
    private final TextField joinAddress = new TextField("127.0.0.1");
    private final TextField joinPort = new TextField(String.valueOf(GameServer.DEFAULT_PORT));
    private final ListView<LanDiscovery.HostInfo> found = new ListView<>();
    private final Label status = new Label();
    private final Button hostButton = new Button("Hospedar partida");
    private final Button joinButton = new Button("Entrar na partida");

    public MainMenuScreen(Actions actions) {
        BoardCanvas backdrop = new BoardCanvas();
        backdrop.setOpacity(0.55);
        backdrop.setMouseTransparent(true);

        Label title = new Label("IBERIA · 1848");
        title.getStyleClass().add("menu-title");
        Label subtitle = new Label("Um clone de Pandemic: Iberia — cooperativo, em rede local");
        subtitle.getStyleClass().add("menu-subtitle");

        String user = System.getProperty("user.name", "Jogador");
        name.setText(user.length() > 20 ? user.substring(0, 20) : user);
        name.setPromptText("Seu nome");
        name.setPrefColumnCount(18);
        HBox nameRow = new HBox(8, new Label("Seu nome:"), name);
        nameRow.setAlignment(Pos.CENTER);

        // host
        Label hostTitle = new Label("Hospedar");
        hostTitle.getStyleClass().add("section-title");
        hostPort.setPrefColumnCount(6);
        hostButton.getStyleClass().add("big-button");
        hostButton.setOnAction(e -> {
            Integer port = parsePort(hostPort.getText());
            if (port == null) return;
            setStatus("Iniciando servidor na porta " + port + "…", false);
            actions.host(name.getText().trim(), port);
        });
        GridPane hostGrid = new GridPane();
        hostGrid.setHgap(8);
        hostGrid.setVgap(8);
        hostGrid.addRow(0, new Label("Porta TCP:"), hostPort);
        Label hostHelp = new Label("Os outros jogadores entram pelo seu IP\nnesta porta (mesma rede local).");
        hostHelp.getStyleClass().add("hint");
        VBox hostBox = new VBox(10, hostTitle, hostGrid, hostHelp, hostButton);
        hostBox.setAlignment(Pos.TOP_LEFT);
        hostBox.setPrefWidth(300);

        // join
        Label joinTitle = new Label("Entrar");
        joinTitle.getStyleClass().add("section-title");
        joinAddress.setPrefColumnCount(14);
        joinPort.setPrefColumnCount(6);
        GridPane joinGrid = new GridPane();
        joinGrid.setHgap(8);
        joinGrid.setVgap(8);
        joinGrid.addRow(0, new Label("IP do anfitrião:"), joinAddress);
        joinGrid.addRow(1, new Label("Porta:"), joinPort);
        joinButton.getStyleClass().add("big-button");
        joinButton.setOnAction(e -> {
            Integer port = parsePort(joinPort.getText());
            if (port == null) return;
            setStatus("Conectando a " + joinAddress.getText().trim() + ":" + port + "…", false);
            actions.join(name.getText().trim(), joinAddress.getText().trim(), port);
        });
        Button discover = new Button("Procurar na rede local");
        discover.getStyleClass().add("small-button");
        found.setPrefHeight(96);
        found.setPlaceholder(new Label("Nenhuma partida encontrada ainda."));
        found.getSelectionModel().selectedItemProperty().addListener((o, a, h) -> {
            if (h != null) {
                joinAddress.setText(h.address);
                joinPort.setText(String.valueOf(h.port));
            }
        });
        found.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && found.getSelectionModel().getSelectedItem() != null) joinButton.fire();
        });
        discover.setOnAction(e -> {
            discover.setDisable(true);
            setStatus("Procurando partidas (UDP " + LanDiscovery.PORT + ")…", false);
            Thread t = new Thread(() -> {
                List<LanDiscovery.HostInfo> hosts = LanDiscovery.discover(1500);
                Platform.runLater(() -> {
                    found.getItems().setAll(hosts);
                    discover.setDisable(false);
                    setStatus(hosts.isEmpty() ? "Nenhuma partida respondeu. Tente o IP direto." : hosts.size() + " partida(s) encontrada(s).",
                            hosts.isEmpty());
                });
            }, "iberia-discover");
            t.setDaemon(true);
            t.start();
        });
        VBox joinBox = new VBox(10, joinTitle, joinGrid, joinButton, discover, found);
        joinBox.setPrefWidth(340);

        HBox columns = new HBox(28, hostBox, new Separator(javafx.geometry.Orientation.VERTICAL), joinBox);
        columns.setAlignment(Pos.TOP_CENTER);

        Button rules = new Button("Resumo das regras");
        rules.getStyleClass().add("small-button");
        rules.setOnAction(e -> Dialogs.info("Resumo das regras", RULES));

        VBox panel = new VBox(16, title, subtitle, nameRow, new Separator(), columns, status, rules);
        panel.setAlignment(Pos.TOP_CENTER);
        panel.setPadding(new Insets(26, 34, 22, 34));
        panel.getStyleClass().add("menu-panel");
        panel.setMaxSize(760, 560);
        getChildren().addAll(backdrop, panel);
    }

    private Integer parsePort(String text) {
        try {
            int p = Integer.parseInt(text.trim());
            if (p < 1 || p > 65535) throw new NumberFormatException();
            return p;
        } catch (NumberFormatException ex) {
            setStatus("Porta inválida.", true);
            return null;
        }
    }

    public void setStatus(String text, boolean error) {
        status.setText(text);
        status.getStyleClass().removeAll("status-error", "status-ok");
        status.getStyleClass().add(error ? "status-error" : "status-ok");
    }

    public void setBusy(boolean busy) {
        hostButton.setDisable(busy);
        joinButton.setDisable(busy);
    }

    private static final String RULES = String.join("\n",
            "Objetivo: pesquisar as 4 doenças (Malária, Tifo, Cólera, Febre Amarela).",
            "Derrota: 8 surtos, faltar cubos de uma cor, ou não haver 2 cartas para comprar.",
            "",
            "Turno: 4 ações → comprar 2 cartas (Epidemias!) → infectar cidades (taxa de infecção).",
            "",
            "Ações: Carruagem/Barco (linha vizinha), Trem (ferrovia contínua), Navio (porto a porto,",
            "descartando carta da cor do destino), Construir ferrovia, Construir hospital (carta da sua",
            "cidade), Tratar (remove 1 cubo), Compartilhar (carta da cidade em que ambos estão),",
            "Pesquisar (5 cartas da cor no hospital), Purificar água (2 fichas numa região adjacente).",
            "",
            "Cada ficha de purificação impede 1 cubo numa cidade vizinha à região.",
            "Mão: máximo de 7 cartas. Eventos podem ser jogados a qualquer momento e não custam ação.");
}
