package com.iberia.engine;

import com.iberia.model.Board;
import com.iberia.model.City;
import com.iberia.model.DiseaseType;
import com.iberia.model.GameState;
import com.iberia.model.GameState.CubeTask;
import com.iberia.model.GameState.Resolution;
import com.iberia.model.InfectionCard;
import com.iberia.model.LogEntry;
import com.iberia.model.PendingChoice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Places disease cubes for Infection cards, Epidemics and Outbreaks.
 *
 * <p>Every cube placement is a {@link CubeTask} in a FIFO queue stored inside the
 * game state, so resolution can pause when the current player must choose which
 * adjacent region loses a purification token, and resume afterwards.
 * Rules applied per cube: Nurse prevention token (no cube, nothing spent), then
 * one purification token absorbs one cube, then a 4th cube becomes an outbreak.
 */
public final class EpidemicResolver {
    private final GameState s;
    private final Random rng;
    private final TurnManager turns;

    private enum Step {DONE, PAUSED}

    EpidemicResolver(GameState s, Random rng, TurnManager turns) {
        this.s = s;
        this.rng = rng;
        this.turns = turns;
    }

    private static Board board() {
        return Board.get();
    }

    // ------------------------------------------------------------------ entry points

    /** Infect the city of one Infection card with one cube. */
    void startInfection(int card) {
        City c = InfectionCard.city(card);
        s.lastInfectedCity = c.id;
        s.log(LogEntry.Kind.INFECTION, "Carta de Infecção: " + c.name + " (" + c.color.diseaseName + ").");
        Resolution r = new Resolution();
        r.sourceCity = c.id;
        r.queue.add(new CubeTask(c.id, c.color, 1));
        s.resolution = r;
        process();
    }

    /** Increase, Infect (bottom card, 3 cubes) and then Intensify once cubes are resolved. */
    void startEpidemic() {
        s.infectionRateIndex = Math.min(s.infectionRateIndex + 1, GameState.INFECTION_RATE_TRACK.length - 1);
        if (s.infectionDeck.isEmpty()) reshuffleInfectionDiscard();
        int card = s.infectionDeck.remove(s.infectionDeck.size() - 1);
        s.infectionDiscard.add(card);
        City c = InfectionCard.city(card);
        s.lastInfectedCity = c.id;
        s.log(LogEntry.Kind.EPIDEMIC, "EPIDEMIA! Taxa de infecção sobe para " + s.infectionRate()
                + ". Carta do fundo: " + c.name + " recebe 3 cubos (" + c.color.diseaseName + ").");
        Resolution r = new Resolution();
        r.epidemic = true;
        r.sourceCity = c.id;
        r.queue.add(new CubeTask(c.id, c.color, 3));
        s.resolution = r;
        process();
    }

    /** The current player picked the region that loses a purification token. */
    void resolvePurificationChoice(int region) {
        PendingChoice c = RuleValidator.blockingChoice(s);
        s.choices.remove(c);
        CubeTask t = s.resolution.queue.get(0);
        removePurification(region, t);
        process();
    }

    void reshuffleInfectionDiscard() {
        List<Integer> d = new ArrayList<>(s.infectionDiscard);
        Collections.shuffle(d, rng);
        s.infectionDiscard.clear();
        s.infectionDeck.addAll(d);
        s.log(LogEntry.Kind.SYSTEM, "O baralho de Infecção acabou: o descarte foi embaralhado.");
    }

    // ------------------------------------------------------------------ queue processing

    private void process() {
        Resolution r = s.resolution;
        while (r != null && !r.queue.isEmpty()) {
            if (s.isOver()) {
                s.resolution = null;
                return;
            }
            CubeTask t = r.queue.get(0);
            if (step(r, t) == Step.PAUSED) return;
            r.queue.remove(0);
            logTask(t);
            spillYellowFever(r, t);
        }
        if (s.isOver()) {
            s.resolution = null;
            return;
        }
        if (r != null && r.epidemic) intensify();
        s.resolution = null;
        turns.onResolutionComplete(r);
    }

    private Step step(Resolution r, CubeTask t) {
        if (r.outbroken.contains(t.city)) {
            t.amount = 0;
            return Step.DONE;
        }
        if (!t.adjusted) {
            t.adjusted = true;
            if (t.onlyIfEmpty && s.totalCubes(t.city) > 0) {
                t.amount = 0;
                return Step.DONE;
            }
            if (t.amount == 1 && t.color == DiseaseType.BLACK && s.settings.historical(DiseaseType.BLACK)
                    && s.cubesAt(t.city, DiseaseType.BLACK) == 0) {
                t.amount = 2;
            }
            if (nurseProtects(t.city)) {
                s.log(LogEntry.Kind.GOOD, "A Enfermeira protege " + name(t.city) + ": nenhum cubo é colocado.");
                t.amount = 0;
                return Step.DONE;
            }
        }
        while (t.amount > 0) {
            List<Integer> regions = regionsWithTokens(t.city);
            if (regions.size() == 1) {
                removePurification(regions.get(0), t);
                continue;
            }
            if (regions.size() > 1) {
                s.choices.add(0, new PendingChoice(PendingChoice.Type.PURIFICATION_REMOVAL, s.currentPlayer, regions,
                        "Escolha de qual região adjacente a " + name(t.city) + " sai a ficha de purificação."));
                return Step.PAUSED;
            }
            if (s.cubesAt(t.city, t.color) >= 3) {
                t.amount = 0;
                outbreak(r, t.city, t.color);
                return Step.DONE;
            }
            if (s.cubeSupply[t.color.ordinal()] <= 0) {
                turns.lose("Acabaram os cubos de " + t.color.diseaseName + ": a doença se espalhou demais.");
                return Step.DONE;
            }
            s.cubes[t.city][t.color.ordinal()]++;
            s.cubeSupply[t.color.ordinal()]--;
            t.amount--;
            t.placed++;
        }
        return Step.DONE;
    }

    /**
     * Yellow Fever challenge: a placement of yellow cubes in a port city also puts one
     * yellow cube in every adjacent port. Spill-over cubes do not spread again.
     */
    private void spillYellowFever(Resolution r, CubeTask t) {
        if (t.placed == 0 || t.color != DiseaseType.YELLOW || t.secondary || !board().city(t.city).port
                || !s.settings.historical(DiseaseType.YELLOW) || s.isOver()) {
            return;
        }
        for (int n : board().neighbors(t.city)) {
            if (!board().city(n).port) continue;
            CubeTask spill = new CubeTask(n, DiseaseType.YELLOW, 1);
            spill.secondary = true;
            r.queue.add(spill);
        }
    }

    private void outbreak(Resolution r, int city, DiseaseType color) {
        r.outbroken.add(city);
        s.outbreaks++;
        s.log(LogEntry.Kind.OUTBREAK, "SURTO de " + color.diseaseName + " em " + name(city)
                + "! Marcador de surtos: " + s.outbreaks + "/" + GameState.MAX_OUTBREAKS + ".");
        if (s.outbreaks >= GameState.MAX_OUTBREAKS) {
            turns.lose("O marcador de surtos chegou a " + GameState.MAX_OUTBREAKS + ": pânico generalizado.");
            return;
        }
        for (int n : board().neighbors(city)) {
            if (r.outbroken.contains(n)) continue;
            CubeTask t = new CubeTask(n, color, 1);
            t.fromOutbreak = true;
            r.queue.add(t);
        }
        if (color == DiseaseType.BLUE && s.settings.historical(DiseaseType.BLUE)) {
            Set<Integer> far = board().citiesAtDistanceTwo(city);
            for (int n : far) {
                CubeTask t = new CubeTask(n, color, 1);
                t.onlyIfEmpty = true;
                t.fromOutbreak = true;
                r.queue.add(t);
            }
        }
    }

    private void intensify() {
        int n = s.infectionDiscard.size();
        List<Integer> d = new ArrayList<>(s.infectionDiscard);
        Collections.shuffle(d, rng);
        s.infectionDiscard.clear();
        s.infectionDeck.addAll(0, d);
        s.log(LogEntry.Kind.EPIDEMIC, "Intensificar: " + n + " cartas do descarte de Infecção voltam ao topo do baralho.");
    }

    // ------------------------------------------------------------------ helpers

    private boolean nurseProtects(int city) {
        return s.nurseRegion >= 0 && board().regionTouches(s.nurseRegion, city);
    }

    private List<Integer> regionsWithTokens(int city) {
        List<Integer> out = new ArrayList<>();
        for (int r : board().regionsOf(city)) if (s.purification[r] > 0) out.add(r);
        return out;
    }

    private void removePurification(int region, CubeTask t) {
        s.purification[region]--;
        s.purificationSupply++;
        t.amount--;
        t.prevented++;
    }

    private void logTask(CubeTask t) {
        if (t.placed == 0 && t.prevented == 0) return;
        StringBuilder sb = new StringBuilder();
        if (t.placed > 0) {
            sb.append(name(t.city)).append(" recebe ").append(t.placed).append(t.placed == 1 ? " cubo " : " cubos ")
                    .append(t.color.colorName).append(t.fromOutbreak ? " (surto)" : t.secondary ? " (porto vizinho)" : "")
                    .append(" → ").append(s.cubesAt(t.city, t.color)).append("/3.");
        }
        if (t.prevented > 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(t.prevented).append(t.prevented == 1 ? " ficha" : " fichas")
                    .append(" de purificação impede").append(t.prevented == 1 ? "" : "m")
                    .append(" cubo(s) em ").append(name(t.city)).append('.');
        }
        s.log(t.placed > 0 ? LogEntry.Kind.INFECTION : LogEntry.Kind.GOOD, sb.toString());
    }

    private static String name(int city) {
        return board().city(city).name;
    }
}
