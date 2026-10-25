package com.iberia.model;

import java.util.ArrayList;
import java.util.List;

/** A seat at the table: name, role, pawn location and hand (hands are public in Pandemic). */
public final class Player {
    public int id;
    public String name;
    public Role role;
    /** Current city of the pawn, -1 before the start city is chosen. */
    public int city = -1;
    public List<Integer> hand = new ArrayList<>();

    public Player() {
    }

    public Player(int id, String name, Role role) {
        this.id = id;
        this.name = name;
        this.role = role;
    }

    public boolean is(Role r) {
        return role == r;
    }

    public long cityCardCount() {
        return hand.stream().filter(PlayerCard::isCity).count();
    }

    public String label() {
        return name + " (" + role.title + ")";
    }
}
