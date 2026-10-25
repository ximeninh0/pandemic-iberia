package com.iberia.engine;

import com.iberia.model.ActionType;
import com.iberia.model.Board;
import com.iberia.model.DiseaseType;
import com.iberia.model.EventType;
import com.iberia.model.GameAction;
import com.iberia.model.GamePhase;
import com.iberia.model.GameSettings;
import com.iberia.model.GameState;
import com.iberia.model.LogEntry;
import com.iberia.model.PendingChoice;
import com.iberia.model.PendingConsent;
import com.iberia.model.Player;
import com.iberia.model.PlayerCard;
import com.iberia.model.Role;
import com.iberia.model.Route;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

/**
 * Authoritative, deterministic rules engine. Every command goes through
 * {@link #apply(GameAction)}: validation ({@link RuleValidator}), optional consent
 * of affected players, execution, then turn bookkeeping ({@link TurnManager}).
 */
public final class GameEngine {

    /** Outcome of {@link #apply(GameAction)}. */
    public static final class Result {
        public final boolean ok;
        public final String error;

        private Result(boolean ok, String error) {
            this.ok = ok;
            this.error = error;
        }

        static Result ok() {
            return new Result(true, null);
        }

        static Result fail(String e) {
            return new Result(false, e);
        }
    }

    private final GameState s;
    private final TurnManager turns;
    private final EpidemicResolver epidemics;
    /** True when two seats are controlled by the same human (no consent needed between them). */
    private BiPredicate<Integer, Integer> sameController = Integer::equals;

    public GameEngine(GameState state, Random rng) {
        this.s = state;
        this.turns = new TurnManager(state);
        this.epidemics = new EpidemicResolver(state, rng, turns);
        turns.setEpidemicResolver(epidemics);
    }

    public static GameEngine newGame(List<String> names, List<Role> roles, GameSettings settings) {
        long seed = settings.seed != 0 ? settings.seed : System.nanoTime();
        Random rng = new Random(seed);
        return new GameEngine(GameSetup.create(names, roles, settings, rng), rng);
    }

    public GameState state() {
        return s;
    }

    public void setSameController(BiPredicate<Integer, Integer> sameController) {
        this.sameController = sameController;
    }

    public synchronized Result apply(GameAction a) {
        String err = RuleValidator.validate(s, a);
        if (err != null) return Result.fail(err);
        switch (a.type) {
            case CONSENT_REPLY:
                handleConsentReply(a);
                break;
            case CANCEL_REQUEST:
                s.log(LogEntry.Kind.SYSTEM, s.player(a.player).name + " cancelou o pedido.");
                s.consent = null;
                break;
            default:
                List<Integer> affected = RuleValidator.affectedSeats(s, a).stream()
                        .filter(seat -> !sameController.test(a.player, seat))
                        .collect(Collectors.toList());
                if (!affected.isEmpty()) {
                    s.consent = new PendingConsent(a.player, a, affected, describe(a));
                    s.log(LogEntry.Kind.SYSTEM, "Pedido: " + s.consent.description + " Aguardando "
                            + affected.stream().map(i -> s.player(i).name).collect(Collectors.joining(", ")) + ".");
                } else {
                    execute(a);
                }
        }
        turns.afterCommand();
        refreshDiscardChoices();
        s.version++;
        s.syncCounts();
        return Result.ok();
    }

    private void handleConsentReply(GameAction a) {
        PendingConsent c = s.consent;
        if (!a.accept) {
            s.log(LogEntry.Kind.SYSTEM, s.player(a.player).name + " recusou: " + c.description);
            s.consent = null;
            return;
        }
        c.awaiting.remove(Integer.valueOf(a.player));
        s.log(LogEntry.Kind.SYSTEM, s.player(a.player).name + " aceitou.");
        if (c.awaiting.isEmpty()) {
            s.consent = null;
            String err = RuleValidator.validate(s, c.action);
            if (err != null) {
                s.log(LogEntry.Kind.SYSTEM, "O pedido não é mais válido: " + err);
                return;
            }
            execute(c.action);
        }
    }

    // ================================================================= execution

    private void execute(GameAction a) {
        Player p = s.player(a.player);
        switch (a.type) {
            case CHOOSE_START_CITY:
                p.city = a.city;
                s.choices.removeIf(c -> c.type == PendingChoice.Type.START_CITY && c.player == a.player);
                s.log(LogEntry.Kind.ACTION, p.name + " começa em " + cityName(a.city) + ".");
                turns.checkSetupComplete();
                break;
            case MOVE_CARRIAGE:
            case MOVE_TRAIN:
            case MOVE_SHIP:
                move(a, p);
                break;
            case BUILD_RAILROAD:
                buildRailroad(a, p);
                break;
            case BUILD_HOSPITAL: {
                DiseaseType col = Board.get().city(p.city).color;
                discard(p, p.city);
                int old = s.hospitals[col.ordinal()];
                s.hospitals[col.ordinal()] = p.city;
                s.log(LogEntry.Kind.ACTION, p.name + (old < 0 ? " construiu o hospital " : " transferiu o hospital ")
                        + col.colorName + (old < 0 ? "" : " de " + cityName(old)) + " em " + cityName(p.city) + ".");
                turns.spendActions(1);
                break;
            }
            case TREAT_DISEASE:
                treat(a, p);
                break;
            case SHARE_GIVE:
            case SHARE_TAKE: {
                Player other = s.player(a.target);
                Player giver = a.type == ActionType.SHARE_GIVE ? p : other;
                Player receiver = a.type == ActionType.SHARE_GIVE ? other : p;
                transfer(giver, receiver, p.city);
                s.log(LogEntry.Kind.ACTION, giver.name + " deu a carta " + cityName(p.city) + " para " + receiver.name
                        + " (Compartilhar Conhecimento).");
                turns.spendActions(1);
                break;
            }
            case RESEARCH: {
                DiseaseType col = PlayerCard.color(a.cards.get(0));
                for (int c : a.cards) discard(p, c);
                s.researched[col.ordinal()] = true;
                s.log(LogEntry.Kind.GOOD, p.name + " pesquisou a " + col.diseaseName + " no hospital de "
                        + cityName(p.city) + "! (" + s.researchedCount() + "/4)");
                turns.spendActions(1);
                break;
            }
            case PURIFY_WATER: {
                int card = a.cards.get(0);
                discard(p, card);
                int want = 2 + (p.is(Role.AGRONOMIST) ? 1 : 0);
                int placed = placePurification(a.region, want);
                s.log(LogEntry.Kind.ACTION, p.name + " descartou " + PlayerCard.name(card) + " e purificou a água: "
                        + placed + " fichas em " + Board.get().regionLabel(a.region) + ".");
                turns.spendActions(1);
                break;
            }
            case AGRONOMIST_PURIFY:
                placePurification(a.region, 1);
                s.log(LogEntry.Kind.ACTION, p.name + " (Agrônomo) colocou 1 ficha de purificação em "
                        + Board.get().regionLabel(a.region) + ".");
                turns.spendActions(1);
                break;
            case POLITICIAN_GIVE: {
                Player other = s.player(a.target);
                transfer(p, other, p.city);
                s.log(LogEntry.Kind.ACTION, p.name + " (Político) enviou a carta " + cityName(p.city) + " para "
                        + other.name + ".");
                turns.spendActions(1);
                break;
            }
            case POLITICIAN_SWAP: {
                int mine = a.cards.get(0), disc = a.cards.get(1);
                p.hand.remove(Integer.valueOf(mine));
                s.playerDiscard.remove(Integer.valueOf(disc));
                s.playerDiscard.add(mine);
                p.hand.add(disc);
                s.log(LogEntry.Kind.ACTION, p.name + " (Político) trocou " + PlayerCard.name(mine) + " por "
                        + PlayerCard.name(disc) + " do descarte.");
                turns.spendActions(1);
                break;
            }
            case SCIENTIST_PEEK: {
                turns.spendActions(1);
                int n = Math.min(3, s.playerDeck.size());
                List<Integer> top = new ArrayList<>(s.playerDeck.subList(0, n));
                s.choices.add(0, new PendingChoice(PendingChoice.Type.SCIENTIST_ORDER, p.id, top,
                        "Reordene as próximas cartas do baralho de Jogador (a primeira fica no topo)."));
                s.log(LogEntry.Kind.ACTION, p.name + " (Cientista) olhou as " + n + " cartas do topo do baralho de Jogador.");
                break;
            }
            case END_ACTIONS:
                turns.endActions();
                break;
            case DRAW_CARDS:
                turns.drawCards();
                break;
            case RESOLVE_EPIDEMIC:
                turns.resolveNextEpidemic();
                break;
            case INFECT_NEXT:
                turns.infectNext();
                break;
            case INFECT_ALL: {
                int cur = s.currentPlayer;
                while (s.phase == GamePhase.INFECTION && s.currentPlayer == cur && RuleValidator.isFree(s) && !s.isOver()) {
                    turns.infectNext();
                }
                break;
            }
            case DISCARD_CARD: {
                int card = a.cards.get(0);
                discard(p, card);
                s.log(LogEntry.Kind.CARD, p.name + " descartou " + PlayerCard.name(card) + " (limite de mão).");
                break;
            }
            case CHOOSE:
                choose(a, p);
                break;
            case PLAY_EVENT:
                playEvent(a, p);
                break;
            default:
                throw new IllegalStateException("Unhandled " + a.type);
        }
    }

    private void move(GameAction a, Player p) {
        boolean free = RuleValidator.hasFreeMove(s, a.player);
        int from = p.city;
        String how;
        switch (a.type) {
            case MOVE_CARRIAGE:
                how = Board.get().routeBetween(from, a.city).dashed ? "de barco" : "de carruagem";
                break;
            case MOVE_TRAIN:
                how = "de trem";
                break;
            default:
                how = "de navio";
                if (!p.is(Role.SAILOR)) {
                    int card = a.cards.get(0);
                    discard(p, card);
                    how += " (descartou " + PlayerCard.name(card) + ")";
                }
        }
        if (free) s.choices.remove(RuleValidator.blockingChoice(s));
        else turns.spendActions(1);
        String passenger = "";
        if (a.passenger >= 0) {
            passenger = " levando " + s.player(a.passenger).name;
            relocate(a.passenger, a.city);
        }
        relocate(p.id, a.city);
        s.log(LogEntry.Kind.ACTION, p.name + " viajou " + how + " de " + cityName(from) + " para " + cityName(a.city)
                + passenger + (free ? " (movimento gratuito)" : "") + ".");
    }

    /** Moves a pawn; the Nurse's prevention token must then follow her. */
    private void relocate(int player, int city) {
        Player q = s.player(player);
        int old = q.city;
        q.city = city;
        if (q.is(Role.NURSE) && old != city) {
            turns.requestNurseToken(player, q.name + " (Enfermeira): recoloque o token de prevenção numa região adjacente a "
                    + cityName(city) + ".");
        }
    }

    private void buildRailroad(GameAction a, Player p) {
        s.rails[a.route] = true;
        s.railSupply--;
        Route r = Board.get().route(a.route);
        String text = p.name + " construiu ferrovia " + cityName(r.a) + "–" + cityName(r.b);
        if (a.route2 >= 0) {
            s.rails[a.route2] = true;
            s.railSupply--;
            s.railwaymanDoubleUsed = true;
            Route r2 = Board.get().route(a.route2);
            text += " e " + cityName(r2.a) + "–" + cityName(r2.b);
        }
        s.log(LogEntry.Kind.ACTION, text + ". (Reserva: " + s.railSupply + ")");
        turns.spendActions(1);
    }

    private void treat(GameAction a, Player p) {
        int cost = RuleValidator.treatCost(s, p.city, a.color);
        removeCube(p.city, a.color);
        String text = p.name + " tratou " + a.color.diseaseName + " em " + cityName(p.city);
        if (a.city2 >= 0) {
            removeCube(a.city2, a.color2);
            text += " e removeu 1 cubo " + a.color2.colorName + " de " + cityName(a.city2);
        }
        s.log(LogEntry.Kind.ACTION, text + (cost > 1 ? " (Tifo: 2 ações)." : "."));
        turns.spendActions(cost);
    }

    private void choose(GameAction a, Player p) {
        PendingChoice c = RuleValidator.blockingChoice(s);
        switch (c.type) {
            case NURSE_TOKEN:
                s.choices.remove(c);
                s.nurseRegion = a.region;
                s.log(LogEntry.Kind.ACTION, p.name + " colocou o token de prevenção em "
                        + Board.get().regionLabel(a.region) + ".");
                if (s.phase == GamePhase.SETUP) turns.checkSetupComplete();
                break;
            case PURIFICATION_REMOVAL:
                s.log(LogEntry.Kind.GOOD, "Ficha de purificação retirada de " + Board.get().regionLabel(a.region) + ".");
                epidemics.resolvePurificationChoice(a.region);
                break;
            case SCIENTIST_ORDER: {
                s.choices.remove(c);
                for (int i = 0; i < a.values.size(); i++) s.playerDeck.set(i, a.values.get(i));
                s.log(LogEntry.Kind.ACTION, p.name + " reordenou o topo do baralho de Jogador.");
                break;
            }
            case FREE_MOVE:
                s.choices.remove(c);
                s.log(LogEntry.Kind.ACTION, p.name + " abriu mão do movimento gratuito.");
                break;
            default:
                break;
        }
    }

    // ================================================================= events

    private void playEvent(GameAction a, Player p) {
        int card = PlayerCard.ofEvent(a.event);
        p.hand.remove(Integer.valueOf(card));
        s.playerDiscard.add(card);
        s.log(LogEntry.Kind.EVENT, p.name + " jogou o Evento \"" + a.event.title + "\".");
        if (a.event == EventType.WHEN_THE_PLANS_WERE_GOOD) {
            int sub = PlayerCard.ofEvent(a.subEvent);
            s.playerDiscard.remove(Integer.valueOf(sub));
            s.log(LogEntry.Kind.EVENT, "Do descarte, joga \"" + a.subEvent.title + "\".");
            applyEvent(a, p, a.subEvent);
            s.playerDiscard.add(sub);
        } else {
            applyEvent(a, p, a.event);
        }
    }

    private void applyEvent(GameAction a, Player p, EventType e) {
        List<Integer> v = a.values;
        switch (e) {
            case GOOD_SEASON:
                s.goodSeasonQueued = true;
                s.log(LogEntry.Kind.EVENT, "A próxima etapa de infecção revela só 1 carta, do fundo do baralho.");
                break;
            case GOVERNMENT_MOBILIZATION: {
                int n = s.players.size();
                for (int i = 0; i < n; i++) {
                    int seat = (s.currentPlayer + i) % n;
                    s.choices.add(i, new PendingChoice(PendingChoice.Type.FREE_MOVE, seat, new ArrayList<>(),
                            s.player(seat).name + ": faça um movimento gratuito (carruagem, trem ou navio) ou passe."));
                }
                break;
            }
            case HOSPITAL_FOUNDING:
                s.hospitals[a.color.ordinal()] = a.city;
                s.log(LogEntry.Kind.EVENT, "Hospital " + a.color.colorName + " agora em " + cityName(a.city) + ".");
                break;
            case MAIL_CORRESPONDENCE: {
                Player pa = s.player(v.get(0)), pb = s.player(v.get(2));
                int ca = v.get(1), cb = v.get(3);
                pa.hand.remove(Integer.valueOf(ca));
                pb.hand.remove(Integer.valueOf(cb));
                pa.hand.add(cb);
                pb.hand.add(ca);
                s.log(LogEntry.Kind.EVENT, pa.name + " e " + pb.name + " trocaram " + PlayerCard.name(ca) + " por "
                        + PlayerCard.name(cb) + ".");
                break;
            }
            case NEW_RAILS:
            case RING_RAILROADS: {
                List<String> names = new ArrayList<>();
                for (int r : v) {
                    s.rails[r] = true;
                    s.railSupply--;
                    Route route = Board.get().route(r);
                    names.add(cityName(route.a) + "–" + cityName(route.b));
                }
                s.log(LogEntry.Kind.EVENT, "Novas ferrovias: " + String.join(", ", names) + ".");
                break;
            }
            case ONE_MORE_DAY:
                s.actionsLeft += 2;
                s.log(LogEntry.Kind.EVENT, s.current().name + " ganhou 2 ações extras.");
                break;
            case OVERSEAS_MIGRATION:
            case SCIENCE_TRIUMPH: {
                List<String> removed = new ArrayList<>();
                for (int i = 0; i < v.size(); i += 2) {
                    DiseaseType col = DiseaseType.values()[v.get(i + 1)];
                    removeCube(v.get(i), col);
                    removed.add(col.colorName + " de " + cityName(v.get(i)));
                }
                s.log(LogEntry.Kind.EVENT, removed.isEmpty() ? "Nenhum cubo removido."
                        : "Cubos removidos: " + String.join(", ", removed) + ".");
                break;
            }
            case PURIFY_WATER: {
                List<String> where = new ArrayList<>();
                for (int r : v) if (placePurification(r, 1) > 0) where.add(Board.get().regionLabel(r));
                s.log(LogEntry.Kind.EVENT, "Fichas de purificação em: " + String.join(" | ", where) + ".");
                break;
            }
            case SECOND_CHANCE:
                s.playerDiscard.remove(Integer.valueOf(p.city));
                p.hand.add(p.city);
                s.log(LogEntry.Kind.EVENT, p.name + " recuperou a carta " + cityName(p.city) + " do descarte.");
                break;
            case SHIPS_ARRIVE: {
                List<String> names = new ArrayList<>();
                for (int pl : v) {
                    relocate(pl, a.city);
                    names.add(s.player(pl).name);
                }
                s.log(LogEntry.Kind.EVENT, String.join(", ", names) + " desembarcaram em " + cityName(a.city) + ".");
                break;
            }
            case TELEGRAPH_MESSAGE: {
                Player giver = s.player(v.get(0)), receiver = s.player(v.get(1));
                List<String> names = new ArrayList<>();
                for (int c : v.subList(2, v.size())) {
                    transfer(giver, receiver, c);
                    names.add(PlayerCard.name(c));
                }
                s.log(LogEntry.Kind.EVENT, giver.name + " telegrafou " + String.join(" e ", names) + " para "
                        + receiver.name + ".");
                break;
            }
            case TRAVEL_DAY_AND_NIGHT:
                relocate(a.target, a.city);
                s.log(LogEntry.Kind.EVENT, s.player(a.target).name + " viajou dia e noite até " + cityName(a.city) + ".");
                break;
            default:
                break;
        }
    }

    // ================================================================= helpers

    private void discard(Player p, int card) {
        p.hand.remove(Integer.valueOf(card));
        s.playerDiscard.add(card);
    }

    private void transfer(Player from, Player to, int card) {
        from.hand.remove(Integer.valueOf(card));
        to.hand.add(card);
    }

    private void removeCube(int city, DiseaseType color) {
        s.cubes[city][color.ordinal()]--;
        s.cubeSupply[color.ordinal()]++;
    }

    private int placePurification(int region, int want) {
        int n = Math.min(want, s.purificationSupply);
        s.purification[region] += n;
        s.purificationSupply -= n;
        return n;
    }

    /** Drops satisfied hand-limit choices and refreshes the card options of the others. */
    private void refreshDiscardChoices() {
        s.choices.removeIf(c -> c.type == PendingChoice.Type.DISCARD && s.player(c.player).hand.size() <= GameState.HAND_LIMIT);
        for (PendingChoice c : s.choices) {
            if (c.type == PendingChoice.Type.DISCARD) {
                Player p = s.player(c.player);
                c.options = new ArrayList<>(p.hand);
                c.prompt = p.name + " tem " + p.hand.size() + " cartas: descarte ou jogue Eventos até ficar com 7.";
            }
        }
    }

    private static String cityName(int c) {
        return Board.get().city(c).name;
    }

    private String describe(GameAction a) {
        String actor = s.player(a.player).name;
        switch (a.type) {
            case SHARE_GIVE:
                return actor + " quer dar a carta " + cityName(s.player(a.player).city) + " para " + s.player(a.target).name + ".";
            case SHARE_TAKE:
                return actor + " quer pegar a carta " + cityName(s.player(a.player).city) + " de " + s.player(a.target).name + ".";
            case MOVE_TRAIN:
            case MOVE_SHIP:
                return actor + " quer levar " + s.player(a.passenger).name + (a.type == ActionType.MOVE_TRAIN ? " de trem" : " de navio")
                        + " até " + cityName(a.city) + ".";
            case PLAY_EVENT: {
                EventType e = a.effectiveEvent();
                List<Integer> v = a.values;
                switch (e) {
                    case MAIL_CORRESPONDENCE:
                        return actor + " jogou Correspondência Postal: " + s.player(v.get(0)).name + " dá "
                                + PlayerCard.name(v.get(1)) + " e " + s.player(v.get(2)).name + " dá " + PlayerCard.name(v.get(3)) + ".";
                    case TELEGRAPH_MESSAGE:
                        return actor + " jogou Mensagem Telegráfica: " + s.player(v.get(0)).name + " dá "
                                + v.subList(2, v.size()).stream().map(PlayerCard::name).collect(Collectors.joining(" e "))
                                + " para " + s.player(v.get(1)).name + ".";
                    case SHIPS_ARRIVE:
                        return actor + " jogou Chegada dos Navios: levar "
                                + v.stream().map(i -> s.player(i).name).collect(Collectors.joining(", "))
                                + " até " + cityName(a.city) + ".";
                    case TRAVEL_DAY_AND_NIGHT:
                        return actor + " jogou Viagem Dia e Noite: levar " + s.player(a.target).name + " até " + cityName(a.city) + ".";
                    default:
                        return actor + " jogou " + e.title + ".";
                }
            }
            default:
                return actor + ": " + a.type;
        }
    }
}
