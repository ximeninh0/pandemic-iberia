package com.iberia.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A command sent by a seat. Only the fields relevant to {@link #type} are used;
 * the meaning of each field per action/event is documented in
 * {@link com.iberia.engine.RuleValidator}.
 */
public final class GameAction {
    public ActionType type;
    /** Seat performing the action (for events: the seat holding the Event card). */
    public int player = -1;
    public int city = -1;
    public int city2 = -1;
    public int target = -1;
    public int region = -1;
    public int route = -1;
    public int route2 = -1;
    public int passenger = -1;
    public DiseaseType color;
    public DiseaseType color2;
    public List<Integer> cards = new ArrayList<>();
    public List<Integer> values = new ArrayList<>();
    public EventType event;
    /** Event taken from the discard pile by "When the Plans Were Good". */
    public EventType subEvent;
    public boolean accept;

    public GameAction() {
    }

    public GameAction(ActionType type, int player) {
        this.type = type;
        this.player = player;
    }

    public static GameAction of(ActionType type, int player) {
        return new GameAction(type, player);
    }

    public GameAction city(int c) {
        this.city = c;
        return this;
    }

    public GameAction city2(int c) {
        this.city2 = c;
        return this;
    }

    public GameAction target(int t) {
        this.target = t;
        return this;
    }

    public GameAction region(int r) {
        this.region = r;
        return this;
    }

    public GameAction route(int r) {
        this.route = r;
        return this;
    }

    public GameAction route2(int r) {
        this.route2 = r;
        return this;
    }

    public GameAction passenger(int p) {
        this.passenger = p;
        return this;
    }

    public GameAction color(DiseaseType c) {
        this.color = c;
        return this;
    }

    public GameAction color2(DiseaseType c) {
        this.color2 = c;
        return this;
    }

    public GameAction cards(Integer... c) {
        this.cards = new ArrayList<>(Arrays.asList(c));
        return this;
    }

    public GameAction cards(List<Integer> c) {
        this.cards = new ArrayList<>(c);
        return this;
    }

    public GameAction values(Integer... v) {
        this.values = new ArrayList<>(Arrays.asList(v));
        return this;
    }

    public GameAction values(List<Integer> v) {
        this.values = new ArrayList<>(v);
        return this;
    }

    public GameAction event(EventType e) {
        this.event = e;
        return this;
    }

    public GameAction subEvent(EventType e) {
        this.subEvent = e;
        return this;
    }

    public GameAction accept(boolean a) {
        this.accept = a;
        return this;
    }

    /** The event whose effect actually applies (the sub-event for "When the Plans Were Good"). */
    public EventType effectiveEvent() {
        return event == EventType.WHEN_THE_PLANS_WERE_GOOD ? subEvent : event;
    }
}
