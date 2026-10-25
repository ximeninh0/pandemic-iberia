package com.iberia.network;

import com.iberia.engine.GameEngine;
import com.iberia.model.GameAction;
import com.iberia.model.GameSettings;
import com.iberia.model.Player;
import com.iberia.model.Role;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Authoritative game host. All lobby and game state is touched only from a single
 * "loop" thread, so packets are processed strictly in arrival order. Every
 * accepted action results in a canonical (redacted) state broadcast; rejected
 * actions are answered with an error packet to the sender only.
 */
public final class GameServer {
    public static final int DEFAULT_PORT = 8080;
    public static final int MAX_SEATS = 5;
    private static final Logger LOG = Logger.getLogger(GameServer.class.getName());

    private final int port;
    private final String serverName;
    private final String hostToken;
    private final ExecutorService loop = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "iberia-server-loop");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger ids = new AtomicInteger();

    // ---- loop-thread state
    private final Map<String, Conn> conns = new LinkedHashMap<>();
    private final List<Packet.Seat> seats = new ArrayList<>();
    private final Map<String, Boolean> ready = new HashMap<>();
    private GameSettings settings = new GameSettings();
    private String hostId;
    private GameEngine engine;

    // ---- snapshot for the discovery thread
    private volatile int seatCount;
    private volatile boolean inGame;

    private volatile boolean running;
    private ServerSocket serverSocket;
    private LanDiscovery.Responder responder;

    /**
     * @param hostToken secret the hosting client presents to become lobby host;
     *                  {@code null} makes the first client to join the host (dedicated server).
     */
    public GameServer(int port, String serverName, String hostToken) {
        this.port = port;
        this.serverName = serverName == null || serverName.isBlank() ? "Iberia 1848" : serverName.replace(";", ",");
        this.hostToken = hostToken;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        running = true;
        Thread accept = new Thread(this::acceptLoop, "iberia-accept");
        accept.setDaemon(true);
        accept.start();
        try {
            responder = LanDiscovery.startResponder(() -> serverName + ";" + port + ";" + seatCount + ";" + inGame);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Descoberta LAN indisponível: " + e.getMessage());
        }
        LOG.info("Servidor Iberia ouvindo na porta " + port);
    }

    public void stop() {
        running = false;
        if (responder != null) responder.close();
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        loop.execute(() -> {
            for (Conn c : new ArrayList<>(conns.values())) c.close();
            conns.clear();
        });
        loop.shutdown();
    }

    public int port() {
        return port;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                Conn c = new Conn(socket, "c" + ids.incrementAndGet());
                loop.execute(() -> conns.put(c.id, c));
                c.startThreads();
            } catch (IOException e) {
                if (running) LOG.log(Level.WARNING, "Erro ao aceitar conexão", e);
            }
        }
    }

    // ================================================================= connection

    private final class Conn {
        final String id;
        final Socket socket;
        final BlockingQueue<String> outbox = new LinkedBlockingQueue<>();
        String name;
        boolean welcomed;
        volatile boolean closed;

        Conn(Socket socket, String id) {
            this.socket = socket;
            this.id = id;
        }

        void startThreads() throws IOException {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            Thread reader = new Thread(() -> {
                try {
                    String line;
                    while (!closed && (line = in.readLine()) != null) {
                        if (line.isBlank()) continue;
                        Packet p;
                        try {
                            p = Packet.decode(line);
                        } catch (RuntimeException e) {
                            send(Packet.error("Pacote inválido."));
                            continue;
                        }
                        loop.execute(() -> safeHandle(this, p));
                    }
                } catch (IOException ignored) {
                    // connection dropped
                }
                loop.execute(() -> onDisconnect(this));
            }, "iberia-reader-" + id);
            Thread writer = new Thread(() -> {
                try {
                    while (!closed) {
                        String msg = outbox.take();
                        out.write(msg);
                        out.newLine();
                        out.flush();
                    }
                } catch (IOException | InterruptedException ignored) {
                    // connection dropped
                }
            }, "iberia-writer-" + id);
            reader.setDaemon(true);
            writer.setDaemon(true);
            reader.start();
            writer.start();
        }

        void send(Packet p) {
            if (!closed) outbox.offer(p.encode());
        }

        void close() {
            closed = true;
            outbox.offer("");
            try {
                socket.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    // ================================================================= packet handling (loop thread)

    private void safeHandle(Conn c, Packet p) {
        try {
            handle(c, p);
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Erro ao processar pacote " + p.type, e);
            c.send(Packet.error("Erro interno do servidor: " + e.getMessage()));
        }
    }

    private void handle(Conn c, Packet p) {
        if (p.type == null) return;
        if (!c.welcomed && p.type != PacketType.CONNECT) {
            c.send(Packet.error("Envie CONNECT primeiro."));
            return;
        }
        switch (p.type) {
            case CONNECT:
                onConnect(c, p.as(Packet.Connect.class));
                break;
            case LOBBY_COMMAND:
                onLobbyCommand(c, p.as(Packet.LobbyCommand.class));
                break;
            case PLAYER_ACTION:
                onAction(c, p.as(Packet.PlayerAction.class).action);
                break;
            case CHAT_MESSAGE: {
                String msg = p.as(Packet.ChatMessage.class).message;
                if (msg == null || msg.isBlank()) return;
                broadcast(Packet.chat(c.name, msg.length() > 300 ? msg.substring(0, 300) : msg.trim()));
                break;
            }
            default:
                c.send(Packet.error("Pacote inesperado: " + p.type));
        }
    }

    private void onConnect(Conn c, Packet.Connect hello) {
        if (c.welcomed) return;
        if (hello == null || !Packet.PROTOCOL_VERSION.equals(hello.version)) {
            c.send(Packet.error("Versão de protocolo incompatível."));
            c.close();
            return;
        }
        String name = sanitize(hello.name);
        if (engine != null) {
            List<Packet.Seat> orphan = new ArrayList<>();
            for (Packet.Seat s : seats) if (!s.connected && s.ownerName.equalsIgnoreCase(name)) orphan.add(s);
            if (orphan.isEmpty()) {
                c.send(Packet.error("Partida em andamento. Só jogadores desconectados podem voltar (use o mesmo nome)."));
                c.close();
                return;
            }
            c.name = orphan.get(0).ownerName;
            c.welcomed = true;
            for (Packet.Seat s : orphan) {
                s.ownerId = c.id;
                s.connected = true;
            }
            c.send(Packet.welcome(c.id, c.id.equals(hostId)));
            broadcastLobby();
            broadcastSystemChat(c.name + " voltou à partida.");
            broadcastState();
            return;
        }
        if (seats.size() >= MAX_SEATS) {
            c.send(Packet.error("A mesa já tem " + MAX_SEATS + " jogadores."));
            c.close();
            return;
        }
        c.name = uniqueName(name);
        c.welcomed = true;
        boolean isHost = hostToken != null ? hostToken.equals(hello.hostToken) : hostId == null;
        if (isHost) hostId = c.id;
        Packet.Seat seat = new Packet.Seat();
        seat.name = c.name;
        seat.ownerId = c.id;
        seat.ownerName = c.name;
        seats.add(seat);
        ready.put(c.id, false);
        c.send(Packet.welcome(c.id, isHost));
        broadcastLobby();
        broadcastSystemChat(c.name + " entrou no saguão.");
    }

    private void onLobbyCommand(Conn c, Packet.LobbyCommand cmd) {
        if (cmd == null || cmd.cmd == null) return;
        boolean host = c.id.equals(hostId);
        if (engine != null && cmd.cmd != Packet.LobbyCommand.Cmd.TAKE_SEAT) {
            c.send(Packet.error("A partida já começou."));
            return;
        }
        switch (cmd.cmd) {
            case SET_READY:
                ready.put(c.id, cmd.ready);
                break;
            case SET_ROLE: {
                Packet.Seat s = seat(cmd.seat);
                if (s == null || (!s.ownerId.equals(c.id) && !host)) {
                    c.send(Packet.error("Você não controla esse assento."));
                    return;
                }
                if (cmd.role != null) {
                    for (Packet.Seat o : seats) {
                        if (o != s && o.role == cmd.role) {
                            c.send(Packet.error(cmd.role.title + " já foi escolhido por " + o.name + "."));
                            return;
                        }
                    }
                }
                s.role = cmd.role;
                break;
            }
            case ADD_LOCAL_SEAT: {
                if (seats.size() >= MAX_SEATS) {
                    c.send(Packet.error("A mesa já está cheia."));
                    return;
                }
                Packet.Seat s = new Packet.Seat();
                s.name = uniqueName(sanitize(cmd.name == null || cmd.name.isBlank() ? "Jogador " + (seats.size() + 1) : cmd.name));
                s.ownerId = c.id;
                s.ownerName = c.name;
                seats.add(s);
                break;
            }
            case REMOVE_SEAT: {
                Packet.Seat s = seat(cmd.seat);
                long owned = seats.stream().filter(o -> o.ownerId.equals(c.id)).count();
                if (s == null || !s.ownerId.equals(c.id) || owned <= 1) {
                    c.send(Packet.error("Só é possível remover assentos locais extras."));
                    return;
                }
                seats.remove(s);
                break;
            }
            case SET_SETTINGS:
                if (!host) {
                    c.send(Packet.error("Só o anfitrião altera as configurações."));
                    return;
                }
                if (cmd.settings == null || cmd.settings.epidemics < 4 || cmd.settings.epidemics > 6) {
                    c.send(Packet.error("Configuração inválida."));
                    return;
                }
                settings = cmd.settings.copy();
                break;
            case START_GAME:
                if (!host) {
                    c.send(Packet.error("Só o anfitrião inicia a partida."));
                    return;
                }
                startGame(c);
                return;
            case TAKE_SEAT: {
                Packet.Seat s = seat(cmd.seat);
                if (engine == null || s == null || s.connected) {
                    c.send(Packet.error("Esse assento não está livre."));
                    return;
                }
                s.ownerId = c.id;
                s.ownerName = c.name;
                s.connected = true;
                broadcastSystemChat(c.name + " assumiu o controle de " + s.name + ".");
                broadcastState();
                break;
            }
            default:
                return;
        }
        broadcastLobby();
    }

    private void startGame(Conn host) {
        if (seats.size() < 2) {
            host.send(Packet.error("São necessários pelo menos 2 jogadores (adicione um jogador local para jogar sozinho)."));
            return;
        }
        for (Conn c : conns.values()) {
            if (c.welcomed && !c.id.equals(hostId) && !Boolean.TRUE.equals(ready.get(c.id))) {
                host.send(Packet.error(c.name + " ainda não está pronto."));
                return;
            }
        }
        List<String> names = new ArrayList<>();
        List<Role> roles = new ArrayList<>();
        for (Packet.Seat s : seats) {
            names.add(s.name);
            roles.add(s.role);
        }
        engine = GameEngine.newGame(names, roles, settings);
        engine.setSameController((a, b) -> Objects.equals(seats.get(a).ownerId, seats.get(b).ownerId));
        Packet.GameStart gs = new Packet.GameStart();
        gs.state = Json.redacted(engine.state());
        for (Player p : engine.state().players) {
            gs.assignedRoles.add(p.role);
            gs.playerHands.add(new ArrayList<>(p.hand));
            seats.get(p.id).role = p.role;
        }
        gs.seats = seatsSnapshot();
        inGame = true;
        broadcast(Packet.gameStart(gs));
        broadcastLobby();
    }

    private void onAction(Conn c, GameAction a) {
        if (engine == null) {
            c.send(Packet.error("A partida ainda não começou."));
            return;
        }
        if (a == null || a.player < 0 || a.player >= seats.size()) {
            c.send(Packet.error("Ação inválida."));
            return;
        }
        if (!c.id.equals(seats.get(a.player).ownerId)) {
            c.send(Packet.error("Você não controla " + seats.get(a.player).name + "."));
            return;
        }
        GameEngine.Result r = engine.apply(a);
        if (!r.ok) {
            c.send(Packet.error(r.error));
            return;
        }
        broadcastState();
    }

    private void onDisconnect(Conn c) {
        if (conns.remove(c.id) == null) return;
        c.close();
        if (!c.welcomed) return;
        if (engine == null) {
            seats.removeIf(s -> s.ownerId.equals(c.id));
            ready.remove(c.id);
            if (c.id.equals(hostId)) {
                hostId = conns.values().stream().filter(o -> o.welcomed).map(o -> o.id).findFirst().orElse(null);
            }
            broadcastLobby();
        } else {
            for (Packet.Seat s : seats) if (s.ownerId.equals(c.id)) s.connected = false;
            broadcastState();
        }
        broadcast(Packet.disconnect(c.id, c.name));
    }

    // ================================================================= broadcasting

    private void broadcast(Packet p) {
        for (Conn c : conns.values()) if (c.welcomed) c.send(p);
    }

    private void broadcastSystemChat(String text) {
        broadcast(Packet.chat("Servidor", text));
    }

    private void broadcastLobby() {
        seatCount = seats.size();
        Packet.LobbyUpdate u = new Packet.LobbyUpdate();
        u.seats = seatsSnapshot();
        u.settings = settings.copy();
        u.hostId = hostId;
        u.inGame = engine != null;
        u.serverName = serverName;
        broadcast(Packet.lobbyUpdate(u));
    }

    private void broadcastState() {
        if (engine == null) return;
        broadcast(Packet.stateBroadcast(Json.redacted(engine.state()), seatsSnapshot()));
    }

    private List<Packet.Seat> seatsSnapshot() {
        List<Packet.Seat> out = new ArrayList<>();
        for (int i = 0; i < seats.size(); i++) {
            Packet.Seat s = seats.get(i);
            Packet.Seat copy = new Packet.Seat();
            copy.seat = i;
            copy.name = s.name;
            copy.ownerId = s.ownerId;
            copy.ownerName = s.ownerName;
            copy.role = s.role;
            copy.connected = s.connected;
            copy.ready = s.ownerId.equals(hostId) || Boolean.TRUE.equals(ready.get(s.ownerId));
            out.add(copy);
        }
        return out;
    }

    private Packet.Seat seat(int i) {
        return i >= 0 && i < seats.size() ? seats.get(i) : null;
    }

    private String uniqueName(String base) {
        String name = base;
        int n = 2;
        while (true) {
            final String candidate = name;
            if (seats.stream().noneMatch(s -> s.name.equalsIgnoreCase(candidate))) return name;
            name = base + " " + n++;
        }
    }

    private static String sanitize(String name) {
        if (name == null) return "Jogador";
        String n = name.replaceAll("[\\p{Cntrl};]", "").trim();
        if (n.isEmpty()) return "Jogador";
        return n.length() > 20 ? n.substring(0, 20) : n;
    }
}
