package com.iberia.network;

/** Wire packet types (one JSON object per line over TCP). */
public enum PacketType {
    /** client → server: {@link Packet.Connect} */
    CONNECT,
    /** server → client: {@link Packet.Welcome} */
    WELCOME,
    /** server → clients: {@link Packet.LobbyUpdate} */
    LOBBY_UPDATE,
    /** client → server: {@link Packet.LobbyCommand} */
    LOBBY_COMMAND,
    /** server → clients: {@link Packet.GameStart} */
    GAME_START,
    /** client → server: {@link Packet.PlayerAction} */
    PLAYER_ACTION,
    /** server → clients: {@link Packet.StateBroadcast} */
    STATE_BROADCAST,
    /** both ways: {@link Packet.ChatMessage} */
    CHAT_MESSAGE,
    /** server → client: {@link Packet.Error} */
    ERROR,
    /** server → clients: {@link Packet.Disconnect} */
    DISCONNECT
}
