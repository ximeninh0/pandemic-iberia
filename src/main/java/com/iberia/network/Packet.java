package com.iberia.network;

import com.google.gson.JsonElement;
import com.iberia.model.GameAction;
import com.iberia.model.GameSettings;
import com.iberia.model.GameState;
import com.iberia.model.Role;

import java.util.ArrayList;
import java.util.List;

/**
 * Envelope {@code {"type": ..., "data": {...}}} plus the typed payloads of the
 * protocol. Factory methods mirror the protocol names (PacketConnect,
 * PacketLobbyUpdate, PacketGameStart, PacketPlayerAction, PacketStateBroadcast,
 * PacketChatMessage, PacketDisconnect).
 */
public final class Packet {
    public static final String PROTOCOL_VERSION = "iberia-1";

    public PacketType type;
    public JsonElement data;

    public Packet() {
    }

    public Packet(PacketType type, Object payload) {
        this.type = type;
        this.data = payload == null ? null : Json.GSON.toJsonTree(payload);
    }

    public <T> T as(Class<T> cls) {
        return Json.GSON.fromJson(data, cls);
    }

    public String encode() {
        return Json.GSON.toJson(this);
    }

    public static Packet decode(String line) {
        return Json.GSON.fromJson(line, Packet.class);
    }

    // ------------------------------------------------------------------ payloads

    public static final class Connect {
        public String name;
        public String version = PROTOCOL_VERSION;
        /** Secret given to the hosting client so the server can recognise it. */
        public String hostToken;
    }

    public static final class Welcome {
        public String clientId;
        public boolean host;
    }

    /** One seat at the table. A client may control several seats (hot-seat players). */
    public static final class Seat {
        public int seat;
        public String name;
        public String ownerId;
        public String ownerName;
        /** {@code null} = random role. */
        public Role role;
        public boolean ready;
        public boolean connected = true;
    }

    public static final class LobbyUpdate {
        public List<Seat> seats = new ArrayList<>();
        public GameSettings settings;
        public String hostId;
        public boolean inGame;
        public String serverName;
    }

    public static final class LobbyCommand {
        public enum Cmd {SET_READY, SET_ROLE, ADD_LOCAL_SEAT, REMOVE_SEAT, SET_SETTINGS, START_GAME, TAKE_SEAT}

        public Cmd cmd;
        public int seat = -1;
        public Role role;
        public String name;
        public boolean ready;
        public GameSettings settings;
    }

    public static final class GameStart {
        public GameState state;
        public List<Role> assignedRoles = new ArrayList<>();
        public List<List<Integer>> playerHands = new ArrayList<>();
        public List<Seat> seats = new ArrayList<>();
    }

    public static final class PlayerAction {
        public GameAction action;
    }

    public static final class StateBroadcast {
        public GameState state;
        public List<Seat> seats = new ArrayList<>();
    }

    public static final class ChatMessage {
        public String sender;
        public String message;
        public long time;
    }

    public static final class Error {
        public String message;
    }

    public static final class Disconnect {
        public String clientId;
        public String name;
    }

    // ------------------------------------------------------------------ factories

    public static Packet connect(String playerName, String hostToken) {
        Connect c = new Connect();
        c.name = playerName;
        c.hostToken = hostToken;
        return new Packet(PacketType.CONNECT, c);
    }

    public static Packet welcome(String clientId, boolean host) {
        Welcome w = new Welcome();
        w.clientId = clientId;
        w.host = host;
        return new Packet(PacketType.WELCOME, w);
    }

    public static Packet lobbyUpdate(LobbyUpdate u) {
        return new Packet(PacketType.LOBBY_UPDATE, u);
    }

    public static Packet lobbyCommand(LobbyCommand c) {
        return new Packet(PacketType.LOBBY_COMMAND, c);
    }

    public static Packet gameStart(GameStart g) {
        return new Packet(PacketType.GAME_START, g);
    }

    public static Packet playerAction(GameAction a) {
        PlayerAction p = new PlayerAction();
        p.action = a;
        return new Packet(PacketType.PLAYER_ACTION, p);
    }

    public static Packet stateBroadcast(GameState state, List<Seat> seats) {
        StateBroadcast b = new StateBroadcast();
        b.state = state;
        b.seats = seats;
        return new Packet(PacketType.STATE_BROADCAST, b);
    }

    public static Packet chat(String sender, String message) {
        ChatMessage m = new ChatMessage();
        m.sender = sender;
        m.message = message;
        m.time = System.currentTimeMillis();
        return new Packet(PacketType.CHAT_MESSAGE, m);
    }

    public static Packet error(String message) {
        Error e = new Error();
        e.message = message;
        return new Packet(PacketType.ERROR, e);
    }

    public static Packet disconnect(String clientId, String name) {
        Disconnect d = new Disconnect();
        d.clientId = clientId;
        d.name = name;
        return new Packet(PacketType.DISCONNECT, d);
    }
}
