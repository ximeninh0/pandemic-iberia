package com.iberia.network;

import com.iberia.model.GameAction;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** TCP client: sends commands to the authoritative server and dispatches its packets to a listener. */
public final class GameClient {
    private final NetworkEventListener listener;
    private final Object writeLock = new Object();
    private Socket socket;
    private BufferedWriter out;
    private volatile boolean open;
    private volatile String clientId;

    public GameClient(NetworkEventListener listener) {
        this.listener = listener;
    }

    public void connect(String host, int port, String playerName, String hostToken) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setTcpNoDelay(true);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        open = true;
        Thread reader = new Thread(() -> readLoop(in), "iberia-client-reader");
        reader.setDaemon(true);
        reader.start();
        send(Packet.connect(playerName, hostToken));
    }

    private void readLoop(BufferedReader in) {
        String reason = "Conexão encerrada pelo servidor.";
        try {
            String line;
            while (open && (line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                dispatch(Packet.decode(line));
            }
        } catch (IOException e) {
            reason = "Conexão perdida: " + e.getMessage();
        } catch (RuntimeException e) {
            reason = "Pacote inválido do servidor: " + e.getMessage();
        }
        if (open) {
            open = false;
            closeQuietly();
            listener.onConnectionClosed(reason);
        }
    }

    private void dispatch(Packet p) {
        switch (p.type) {
            case WELCOME: {
                Packet.Welcome w = p.as(Packet.Welcome.class);
                clientId = w.clientId;
                listener.onWelcome(w);
                break;
            }
            case LOBBY_UPDATE:
                listener.onLobbyUpdate(p.as(Packet.LobbyUpdate.class));
                break;
            case GAME_START:
                listener.onGameStart(p.as(Packet.GameStart.class));
                break;
            case STATE_BROADCAST:
                listener.onStateBroadcast(p.as(Packet.StateBroadcast.class));
                break;
            case CHAT_MESSAGE:
                listener.onChat(p.as(Packet.ChatMessage.class));
                break;
            case ERROR:
                listener.onError(p.as(Packet.Error.class).message);
                break;
            case DISCONNECT:
                listener.onPlayerDisconnected(p.as(Packet.Disconnect.class));
                break;
            default:
                break;
        }
    }

    public void send(Packet p) {
        if (!open) return;
        synchronized (writeLock) {
            try {
                out.write(p.encode());
                out.newLine();
                out.flush();
            } catch (IOException e) {
                if (open) {
                    open = false;
                    closeQuietly();
                    listener.onConnectionClosed("Falha ao enviar: " + e.getMessage());
                }
            }
        }
    }

    public void sendAction(GameAction a) {
        send(Packet.playerAction(a));
    }

    public void sendChat(String text) {
        send(Packet.chat(null, text));
    }

    public void sendLobby(Packet.LobbyCommand c) {
        send(Packet.lobbyCommand(c));
    }

    public String clientId() {
        return clientId;
    }

    public boolean isOpen() {
        return open;
    }

    public void disconnect() {
        open = false;
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
