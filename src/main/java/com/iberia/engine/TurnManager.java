package com.iberia.engine;

import com.iberia.model.Board;
import com.iberia.model.GamePhase;
import com.iberia.model.GameState;
import com.iberia.model.GameState.Resolution;
import com.iberia.model.LogEntry;
import com.iberia.model.PendingChoice;
import com.iberia.model.Player;
import com.iberia.model.PlayerCard;
import com.iberia.model.Role;

import java.util.ArrayList;
import java.util.List;

/**
 * Turn flow: setup → (actions → draw 2 → epidemics → infections) per player,
 * hand-limit enforcement and the win/loss conditions.
 */
public final class TurnManager {
    private final GameState s;
    private EpidemicResolver epidemics;

    TurnManager(GameState s) {
        this.s = s;
    }

    void setEpidemicResolver(EpidemicResolver epidemics) {
        this.epidemics = epidemics;
    }

    // ------------------------------------------------------------------ setup

    /** Called after every setup choice; starts the first turn once everyone is placed. */
    void checkSetupComplete() {
        if (s.phase != GamePhase.SETUP || !s.choices.isEmpty()) return;
        int nurse = s.playerWithRole(Role.NURSE);
        if (nurse >= 0 && s.nurseRegion < 0) {
            requestNurseToken(nurse, "Enfermeira: coloque o token de prevenção numa região adjacente.");
            if (!s.choices.isEmpty()) return;
        }
        int first = 0;
        int best = Integer.MAX_VALUE;
        for (Player p : s.players) {
            for (int card : p.hand) {
                if (PlayerCard.isCity(card) && PlayerCard.city(card).founded < best) {
                    best = PlayerCard.city(card).founded;
                    first = p.id;
                }
            }
        }
        s.log(LogEntry.Kind.SYSTEM, s.player(first).name + " tem a cidade fundada há mais tempo e começa o jogo.");
        startTurn(first, true);
    }

    /** Asks the Nurse's seat to place the prevention token (placed automatically if only one region fits). */
    void requestNurseToken(int nurse, String prompt) {
        int city = s.player(nurse).city;
        int[] regions = Board.get().regionsOf(city);
        if (regions.length == 1) {
            s.nurseRegion = regions[0];
            s.log(LogEntry.Kind.ACTION, "Token de prevenção colocado em " + Board.get().regionLabel(regions[0]) + ".");
            return;
        }
        s.nurseRegion = -1;
        List<Integer> opts = new ArrayList<>();
        for (int r : regions) opts.add(r);
        s.choices.add(0, new PendingChoice(PendingChoice.Type.NURSE_TOKEN, nurse, opts, prompt));
    }

    // ------------------------------------------------------------------ turn flow

    void startTurn(int player, boolean firstTurn) {
        s.currentPlayer = player;
        s.phase = GamePhase.ACTIONS;
        s.actionsLeft = GameState.ACTIONS_PER_TURN;
        s.railwaymanDoubleUsed = false;
        s.turn++;
        s.lastDrawn.clear();
        s.log(LogEntry.Kind.SYSTEM, (firstTurn ? "Início do jogo. " : "") + "Turno " + s.turn + ": vez de "
                + s.player(player).label() + ".");
    }

    void spendActions(int n) {
        s.actionsLeft = Math.max(0, s.actionsLeft - n);
    }

    void endActions() {
        s.actionsLeft = 0;
        enterDrawPhase();
    }

    private void enterDrawPhase() {
        s.phase = GamePhase.DRAW;
        s.log(LogEntry.Kind.SYSTEM, s.current().name + " terminou as ações e deve comprar 2 cartas.");
    }

    void drawCards() {
        if (s.playerDeck.size() < 2) {
            lose("Não há 2 cartas no baralho de Jogador: o tempo da expedição acabou.");
            return;
        }
        Player p = s.current();
        s.lastDrawn.clear();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int card = s.playerDeck.remove(0);
            s.lastDrawn.add(card);
            if (PlayerCard.isEpidemic(card)) {
                s.epidemicsPending++;
                s.removedCards.add(card);
                names.add("EPIDEMIA");
            } else {
                p.hand.add(card);
                names.add(PlayerCard.name(card));
            }
        }
        s.log(LogEntry.Kind.CARD, p.name + " comprou: " + String.join(" e ", names) + ".");
        if (s.epidemicsPending > 0) resolveNextEpidemic();
        else finishDraw();
    }

    void resolveNextEpidemic() {
        s.epidemicsPending--;
        epidemics.startEpidemic();
    }

    private void finishDraw() {
        s.phase = GamePhase.INFECTION;
        if (s.goodSeasonQueued) {
            s.goodSeasonQueued = false;
            s.infectFromBottom = true;
            s.infectionsLeft = 1;
            s.log(LogEntry.Kind.EVENT, "Boa Estação: só 1 carta de Infecção, tirada do fundo do baralho.");
        } else {
            s.infectFromBottom = false;
            s.infectionsLeft = s.infectionRate();
        }
    }

    void infectNext() {
        if (s.infectionDeck.isEmpty()) epidemics.reshuffleInfectionDiscard();
        int card = s.infectFromBottom ? s.infectionDeck.remove(s.infectionDeck.size() - 1) : s.infectionDeck.remove(0);
        s.infectionDiscard.add(card);
        epidemics.startInfection(card);
    }

    /** Called by the resolver when an Infection or Epidemic card is fully resolved. */
    void onResolutionComplete(Resolution r) {
        if (s.isOver()) return;
        if (r.epidemic) {
            if (s.epidemicsPending > 0) {
                s.phase = GamePhase.EPIDEMIC_PENDING;
                s.log(LogEntry.Kind.EPIDEMIC, "Uma segunda Epidemia aguarda: Eventos podem ser jogados antes de resolvê-la.");
            } else {
                finishDraw();
            }
        } else {
            s.infectionsLeft--;
            if (s.infectionsLeft <= 0) endTurn();
        }
    }

    private void endTurn() {
        s.infectFromBottom = false;
        startTurn((s.currentPlayer + 1) % s.players.size(), false);
    }

    /** Runs after every successfully applied command. */
    void afterCommand() {
        if (s.isOver()) return;
        checkVictory();
        if (s.isOver()) return;
        if (s.phase == GamePhase.ACTIONS && s.actionsLeft <= 0 && RuleValidator.isFree(s)) enterDrawPhase();
        enforceHandLimits();
    }

    void enforceHandLimits() {
        if (s.phase == GamePhase.SETUP || s.phase == GamePhase.EPIDEMIC_PENDING) return;
        if (s.resolution != null && s.resolution.epidemic) return;
        for (Player p : s.players) {
            if (p.hand.size() > GameState.HAND_LIMIT && RuleValidator.discardChoiceFor(s, p.id) == null) {
                List<Integer> opts = new ArrayList<>(p.hand);
                s.choices.add(new PendingChoice(PendingChoice.Type.DISCARD, p.id, opts,
                        p.name + " tem " + p.hand.size() + " cartas: descarte ou jogue Eventos até ficar com 7."));
            }
        }
    }

    // ------------------------------------------------------------------ end of game

    void checkVictory() {
        if (s.researchedCount() == 4) {
            s.phase = GamePhase.GAME_OVER;
            s.won = true;
            s.gameOverReason = "As 4 doenças foram pesquisadas. A Expedição Filantrópica salvou a Península Ibérica!";
            s.choices.clear();
            s.consent = null;
            s.resolution = null;
            s.log(LogEntry.Kind.GOOD, "VITÓRIA! " + s.gameOverReason);
        }
    }

    void lose(String reason) {
        if (s.isOver()) return;
        s.phase = GamePhase.GAME_OVER;
        s.won = false;
        s.gameOverReason = reason;
        s.choices.clear();
        s.consent = null;
        s.log(LogEntry.Kind.BAD, "DERROTA! " + reason);
    }
}
