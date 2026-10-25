package com.iberia.network;

import com.iberia.engine.RuleValidator;
import com.iberia.model.ActionType;
import com.iberia.model.GameAction;
import com.iberia.model.GamePhase;
import com.iberia.model.GameState;
import com.iberia.model.PendingChoice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end test over real TCP sockets: lobby, game start, authoritative validation, chat, reconnection. */
class NetworkTest {
    private GameServer server;
    private final List<GameClient> clients = new CopyOnWriteArrayList<>();

    static final class Recorder implements NetworkEventListener {
        volatile Packet.Welcome welcome;
        volatile Packet.LobbyUpdate lobby;
        volatile Packet.StateBroadcast state;
        volatile Packet.GameStart start;
        final List<String> errors = new CopyOnWriteArrayList<>();
        final List<String> chat = new CopyOnWriteArrayList<>();
        volatile boolean closed;

        @Override
        public void onWelcome(Packet.Welcome w) {
            welcome = w;
        }

        @Override
        public void onLobbyUpdate(Packet.LobbyUpdate u) {
            lobby = u;
        }

        @Override
        public void onGameStart(Packet.GameStart s) {
            start = s;
            Packet.StateBroadcast b = new Packet.StateBroadcast();
            b.state = s.state;
            b.seats = s.seats;
            state = b;
        }

        @Override
        public void onStateBroadcast(Packet.StateBroadcast s) {
            state = s;
        }

        @Override
        public void onChat(Packet.ChatMessage m) {
            chat.add(m.sender + ": " + m.message);
        }

        @Override
        public void onError(String message) {
            errors.add(message);
        }

        @Override
        public void onPlayerDisconnected(Packet.Disconnect info) {
        }

        @Override
        public void onConnectionClosed(String reason) {
            closed = true;
        }

        GameState gs() {
            return state.state;
        }

        int mySeat() {
            return state.seats.stream().filter(s -> s.ownerId.equals(welcome.clientId)).findFirst().orElseThrow().seat;
        }
    }

    private static void await(BooleanSupplier cond, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(15);
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private GameClient connect(Recorder r, int port, String name, String token) throws Exception {
        GameClient c = new GameClient(r);
        c.connect("127.0.0.1", port, name, token);
        clients.add(c);
        await(() -> r.welcome != null, name + " welcome");
        return c;
    }

    @AfterEach
    void tearDown() {
        clients.forEach(GameClient::disconnect);
        if (server != null) server.stop();
    }

    @Test
    void fullLobbyAndGameFlowOverTcp() throws Exception {
        int port = freePort();
        server = new GameServer(port, "Mesa de teste", "secret");
        server.start();

        Recorder ana = new Recorder();
        Recorder bruno = new Recorder();
        GameClient ca = connect(ana, port, "Ana", "secret");
        GameClient cb = connect(bruno, port, "Bruno", null);
        assertTrue(ana.welcome.host);
        assertFalse(bruno.welcome.host);
        await(() -> ana.lobby != null && ana.lobby.seats.size() == 2, "lobby with 2 seats");

        // guest cannot start, host cannot start before everyone is ready
        cb.sendLobby(cmd(Packet.LobbyCommand.Cmd.START_GAME));
        await(() -> !bruno.errors.isEmpty(), "guest start rejected");
        ca.sendLobby(cmd(Packet.LobbyCommand.Cmd.START_GAME));
        await(() -> !ana.errors.isEmpty(), "start rejected while not ready");

        Packet.LobbyCommand settings = cmd(Packet.LobbyCommand.Cmd.SET_SETTINGS);
        settings.settings = new com.iberia.model.GameSettings();
        settings.settings.epidemics = 6;
        ca.sendLobby(settings);
        Packet.LobbyCommand ready = cmd(Packet.LobbyCommand.Cmd.SET_READY);
        ready.ready = true;
        cb.sendLobby(ready);
        await(() -> ana.lobby.seats.stream().allMatch(s -> s.ready) && ana.lobby.settings.epidemics == 6, "everyone ready");
        ca.sendLobby(cmd(Packet.LobbyCommand.Cmd.START_GAME));
        await(() -> ana.start != null && bruno.start != null, "game start on both clients");
        assertEquals(2, ana.start.assignedRoles.size());
        assertEquals(6, ana.gs().settings.epidemics);
        assertTrue(ana.gs().redacted);
        assertTrue(ana.gs().playerDeck.isEmpty(), "deck order must be hidden from clients");
        assertTrue(ana.gs().playerDeckSize() > 0);

        // setup: everyone picks a start city, then resolve any Nurse choice
        for (Recorder r : List.of(ana, bruno)) {
            GameClient c = r == ana ? ca : cb;
            int seat = r.mySeat();
            PendingChoice pc = r.gs().choices.stream().filter(x -> x.player == seat).findFirst().orElseThrow();
            c.sendAction(GameAction.of(ActionType.CHOOSE_START_CITY, seat).city(pc.options.get(0)));
        }
        await(() -> RuleValidator.blockingChoice(ana.gs()) != null || ana.gs().phase == GamePhase.ACTIONS, "setup done");
        PendingChoice nurse = RuleValidator.blockingChoice(ana.gs());
        if (nurse != null) {
            GameClient c = nurse.player == ana.mySeat() ? ca : cb;
            c.sendAction(GameAction.of(ActionType.CHOOSE, nurse.player).region(nurse.options.get(0)));
        }
        await(() -> ana.gs().phase == GamePhase.ACTIONS && bruno.gs().phase == GamePhase.ACTIONS, "first turn");

        int current = ana.gs().currentPlayer;
        Recorder me = current == ana.mySeat() ? ana : bruno;
        Recorder other = me == ana ? bruno : ana;
        GameClient myClient = me == ana ? ca : cb;
        GameClient otherClient = me == ana ? cb : ca;

        // only the controller of the active seat may act
        int errorsBefore = other.errors.size();
        otherClient.sendAction(GameAction.of(ActionType.END_ACTIONS, current));
        await(() -> other.errors.size() > errorsBefore, "foreign action rejected");
        assertTrue(other.errors.get(other.errors.size() - 1).contains("não controla"));

        // invalid move is rejected by the authoritative rules
        int myErrors = me.errors.size();
        myClient.sendAction(GameAction.of(ActionType.RESEARCH, current));
        await(() -> me.errors.size() > myErrors, "illegal action rejected");

        long v = me.gs().version;
        myClient.sendAction(GameAction.of(ActionType.END_ACTIONS, current));
        await(() -> me.gs().version > v && other.gs().phase == GamePhase.DRAW, "draw phase broadcast");
        myClient.sendAction(GameAction.of(ActionType.DRAW_CARDS, current));
        await(() -> me.gs().phase != GamePhase.DRAW, "cards drawn");
        assertEquals(me.gs().log.size(), other.gs().log.size());

        // chat reaches everyone
        otherClient.sendChat("Olá, mesa!");
        await(() -> me.chat.stream().anyMatch(m -> m.endsWith("Olá, mesa!")), "chat");

        // reconnection: Bruno drops and comes back with the same name
        cb.disconnect();
        await(() -> ana.state.seats.stream().anyMatch(s -> !s.connected), "seat marked disconnected");
        Recorder bruno2 = new Recorder();
        connect(bruno2, port, "Bruno", null);
        await(() -> bruno2.state != null && ana.state.seats.stream().allMatch(s -> s.connected), "reconnected");
        assertNotNull(bruno2.gs());
        assertEquals(ana.gs().turn, bruno2.gs().turn);
    }

    @Test
    void lanDiscoveryAnswersOnLoopback() throws Exception {
        int port = freePort();
        server = new GameServer(port, "Descoberta", null);
        server.start();
        List<LanDiscovery.HostInfo> hosts = LanDiscovery.discover(800);
        System.out.println("Discovered: " + hosts);
        assertTrue(hosts.stream().anyMatch(h -> h.port == port), "server should answer UDP discovery on loopback");
    }

    private static Packet.LobbyCommand cmd(Packet.LobbyCommand.Cmd c) {
        Packet.LobbyCommand l = new Packet.LobbyCommand();
        l.cmd = c;
        return l;
    }
}
