package com.iberia;

import com.iberia.network.GameClient;
import com.iberia.network.GameServer;
import com.iberia.network.NetworkEventListener;
import com.iberia.network.Packet;
import com.iberia.ui.Dialogs;
import com.iberia.ui.GameScreen;
import com.iberia.ui.LobbyScreen;
import com.iberia.ui.MainMenuScreen;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.BindException;
import java.util.UUID;

/**
 * JavaFX entry point. "Host Game" starts an authoritative {@link GameServer} in
 * this process and connects to it over TCP like any other client; "Join Game"
 * connects to a host on the LAN. All game logic runs on the server.
 */
public class Main extends Application implements NetworkEventListener {
    private Stage stage;
    private Scene scene;
    private MainMenuScreen menu;
    private LobbyScreen lobby;
    private GameScreen game;
    private GameClient client;
    private GameServer server;
    private String clientId;
    private int port;

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        Dialogs.setOwner(primaryStage);
        menu = new MainMenuScreen(new MainMenuScreen.Actions() {
            @Override
            public void host(String name, int p) {
                hostGame(name, p);
            }

            @Override
            public void join(String name, String address, int p) {
                connect(address, p, name, null);
            }
        });
        scene = new Scene(menu, 1560, 960);
        scene.getStylesheets().add(getClass().getResource("/com/iberia/iberia.css").toExternalForm());
        stage.setTitle("Iberia 1848 — clone de Pandemic: Iberia");
        stage.setMinWidth(1200);
        stage.setMinHeight(780);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> shutdown());
        stage.show();
    }

    @Override
    public void stop() {
        shutdown();
    }

    private void show(Parent root) {
        scene.setRoot(root);
    }

    // ------------------------------------------------------------------ host / join

    private void hostGame(String name, int p) {
        String token = UUID.randomUUID().toString();
        try {
            server = new GameServer(p, name.isBlank() ? "Iberia 1848" : "Mesa de " + name, token);
            server.start();
        } catch (BindException e) {
            server = null;
            menu.setStatus("A porta " + p + " já está em uso. Escolha outra.", true);
            return;
        } catch (IOException e) {
            server = null;
            menu.setStatus("Não foi possível abrir o servidor: " + e.getMessage(), true);
            return;
        }
        connect("127.0.0.1", p, name, token);
    }

    private void connect(String address, int p, String name, String hostToken) {
        menu.setBusy(true);
        this.port = p;
        GameClient c = new GameClient(this);
        this.client = c;
        Thread t = new Thread(() -> {
            try {
                c.connect(address, p, name.isBlank() ? "Jogador" : name, hostToken);
            } catch (IOException e) {
                Platform.runLater(() -> {
                    menu.setBusy(false);
                    menu.setStatus("Falha ao conectar em " + address + ":" + p + " — " + e.getMessage(), true);
                    if (server != null) {
                        server.stop();
                        server = null;
                    }
                });
            }
        }, "iberia-connect");
        t.setDaemon(true);
        t.start();
    }

    private void backToMenu(String message, boolean error) {
        if (client != null) client.disconnect();
        if (server != null) server.stop();
        client = null;
        server = null;
        lobby = null;
        game = null;
        clientId = null;
        menu.setBusy(false);
        if (message != null) menu.setStatus(message, error);
        show(menu);
    }

    private void shutdown() {
        if (client != null) client.disconnect();
        if (server != null) server.stop();
    }

    private void sendChat(String text) {
        if (client != null) client.sendChat(text);
    }

    private void sendLobby(Packet.LobbyCommand c) {
        if (client != null) client.sendLobby(c);
    }

    private void ensureGameScreen() {
        if (game == null) {
            game = new GameScreen(clientId, a -> {
                if (client != null) client.sendAction(a);
            }, this::sendChat, this::sendLobby, () -> backToMenu("Partida encerrada.", false));
            show(game);
        }
    }

    // ------------------------------------------------------------------ network events

    @Override
    public void onWelcome(Packet.Welcome welcome) {
        Platform.runLater(() -> {
            clientId = welcome.clientId;
            menu.setBusy(false);
            lobby = new LobbyScreen(clientId, port, this::sendLobby, this::sendChat,
                    () -> backToMenu("Você saiu do saguão.", false));
            show(lobby);
        });
    }

    @Override
    public void onLobbyUpdate(Packet.LobbyUpdate update) {
        Platform.runLater(() -> {
            if (lobby != null && game == null && !update.inGame) lobby.update(update);
        });
    }

    @Override
    public void onGameStart(Packet.GameStart start) {
        Platform.runLater(() -> {
            game = null;
            ensureGameScreen();
            game.update(start.state, start.seats);
        });
    }

    @Override
    public void onStateBroadcast(Packet.StateBroadcast state) {
        Platform.runLater(() -> {
            if (clientId == null) return;
            ensureGameScreen();
            game.update(state.state, state.seats);
        });
    }

    @Override
    public void onChat(Packet.ChatMessage message) {
        Platform.runLater(() -> {
            if (game != null) game.appendChat(message.sender, message.message);
            else if (lobby != null) lobby.appendChat(message.sender, message.message);
        });
    }

    @Override
    public void onError(String message) {
        Platform.runLater(() -> {
            if (game != null) game.showError(message);
            else if (lobby != null) lobby.showError(message);
            else menu.setStatus(message, true);
        });
    }

    @Override
    public void onPlayerDisconnected(Packet.Disconnect info) {
        Platform.runLater(() -> {
            String text = info.name + " desconectou." + (game != null ? " Ele(a) pode voltar entrando com o mesmo nome." : "");
            if (game != null) game.showNotice(text);
            else if (lobby != null) lobby.appendChat("Servidor", text);
        });
    }

    @Override
    public void onConnectionClosed(String reason) {
        Platform.runLater(() -> backToMenu(reason, true));
    }

    public static void main(String[] args) throws Exception {
        Launcher.main(args);
    }
}
