package com.iberia.model;

/** One line of the game log shown to every player. */
public final class LogEntry {
    public enum Kind {SYSTEM, ACTION, CARD, INFECTION, OUTBREAK, EPIDEMIC, EVENT, CHAT, GOOD, BAD}

    public int seq;
    public int turn;
    public Kind kind;
    public String text;

    public LogEntry() {
    }

    public LogEntry(int seq, int turn, Kind kind, String text) {
        this.seq = seq;
        this.turn = turn;
        this.kind = kind;
        this.text = text;
    }
}
