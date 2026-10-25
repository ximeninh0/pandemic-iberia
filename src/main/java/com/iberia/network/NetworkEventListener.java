package com.iberia.network;

/**
 * Callbacks raised by {@link GameClient}. They run on the network thread; UI
 * implementations must hop to the JavaFX thread themselves.
 */
public interface NetworkEventListener {
    void onWelcome(Packet.Welcome welcome);

    void onLobbyUpdate(Packet.LobbyUpdate update);

    void onGameStart(Packet.GameStart start);

    void onStateBroadcast(Packet.StateBroadcast state);

    void onChat(Packet.ChatMessage message);

    void onError(String message);

    void onPlayerDisconnected(Packet.Disconnect info);

    /** The connection to the server was lost or closed. */
    void onConnectionClosed(String reason);
}
