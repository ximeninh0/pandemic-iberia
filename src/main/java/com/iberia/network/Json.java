package com.iberia.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.iberia.model.GameState;

/** Shared Gson instance and state helpers. */
public final class Json {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {
    }

    public static GameState copy(GameState s) {
        return GSON.fromJson(GSON.toJson(s), GameState.class);
    }

    /** Client view of the state: hidden deck order removed, sizes kept. */
    public static GameState redacted(GameState s) {
        s.syncCounts();
        GameState c = copy(s);
        c.playerDeck.clear();
        c.infectionDeck.clear();
        c.redacted = true;
        return c;
    }
}
