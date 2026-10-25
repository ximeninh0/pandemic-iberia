package com.iberia.model;

/** Every command a seat can send to the authoritative engine. */
public enum ActionType {
    // setup
    CHOOSE_START_CITY(false),
    // movement
    MOVE_CARRIAGE(true),
    MOVE_TRAIN(true),
    MOVE_SHIP(true),
    // other actions
    BUILD_RAILROAD(true),
    BUILD_HOSPITAL(true),
    TREAT_DISEASE(true),
    SHARE_GIVE(true),
    SHARE_TAKE(true),
    RESEARCH(true),
    PURIFY_WATER(true),
    // role actions
    AGRONOMIST_PURIFY(true),
    POLITICIAN_GIVE(true),
    POLITICIAN_SWAP(true),
    SCIENTIST_PEEK(true),
    // turn flow
    END_ACTIONS(false),
    DRAW_CARDS(false),
    RESOLVE_EPIDEMIC(false),
    INFECT_NEXT(false),
    INFECT_ALL(false),
    // anytime / responses
    PLAY_EVENT(false),
    DISCARD_CARD(false),
    CHOOSE(false),
    CONSENT_REPLY(false),
    CANCEL_REQUEST(false);

    /** True for the actions that cost one of the 4 actions of a turn. */
    public final boolean costsAction;

    ActionType(boolean costsAction) {
        this.costsAction = costsAction;
    }

    public boolean isMove() {
        return this == MOVE_CARRIAGE || this == MOVE_TRAIN || this == MOVE_SHIP;
    }
}
