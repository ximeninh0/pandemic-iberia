package com.iberia.engine;

import com.iberia.model.ActionType;
import com.iberia.model.Board;
import com.iberia.model.City;
import com.iberia.model.DiseaseType;
import com.iberia.model.EventType;
import com.iberia.model.GameAction;
import com.iberia.model.GamePhase;
import com.iberia.model.GameState;
import com.iberia.model.PendingChoice;
import com.iberia.model.Player;
import com.iberia.model.PlayerCard;
import com.iberia.model.Region;
import com.iberia.model.Role;
import com.iberia.model.Route;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure rule checks. Works on both the authoritative state and the redacted
 * client copy, so the UI uses the same code to enable buttons that the server
 * uses to accept or reject packets.
 *
 * <p>Field usage per action:
 * <ul>
 * <li>MOVE_CARRIAGE/TRAIN/SHIP: city = destination, passenger (Railwayman/Sailor), cards = [ship card]</li>
 * <li>BUILD_RAILROAD: route, route2 (Railwayman second consecutive token)</li>
 * <li>TREAT_DISEASE: color; Rural Doctor extra cube: city2 + color2</li>
 * <li>SHARE_GIVE/SHARE_TAKE/POLITICIAN_GIVE: target</li>
 * <li>RESEARCH: cards = 5 city cards; PURIFY_WATER: region + cards = [card]; AGRONOMIST_PURIFY: region</li>
 * <li>POLITICIAN_SWAP: cards = [card in hand, card in discard]</li>
 * <li>CHOOSE: region (Nurse/purification), values (Scientist order), accept=false to skip a free move</li>
 * <li>PLAY_EVENT: event (+ subEvent), parameters documented in {@link #validateEventParams}</li>
 * </ul>
 */
public final class RuleValidator {
    private RuleValidator() {
    }

    private static Board board() {
        return Board.get();
    }

    // ================================================================= state queries

    /** First pending choice that blocks the flow of play (hand-limit discards and start cities are handled apart). */
    public static PendingChoice blockingChoice(GameState s) {
        for (PendingChoice c : s.choices) {
            if (c.type != PendingChoice.Type.DISCARD && c.type != PendingChoice.Type.START_CITY) return c;
        }
        return null;
    }

    public static PendingChoice discardChoiceFor(GameState s, int player) {
        for (PendingChoice c : s.choices) if (c.type == PendingChoice.Type.DISCARD && c.player == player) return c;
        return null;
    }

    /** True when nothing (choice, consent request or card resolution) is pending. */
    public static boolean isFree(GameState s) {
        return s.choices.isEmpty() && s.consent == null && s.resolution == null;
    }

    /** True when {@code player} may perform a regular action right now. */
    public static boolean canAct(GameState s, int player) {
        return s.phase == GamePhase.ACTIONS && s.currentPlayer == player && s.actionsLeft > 0 && isFree(s);
    }

    /** True when {@code player} has a Government Mobilization free move to make. */
    public static boolean hasFreeMove(GameState s, int player) {
        PendingChoice c = blockingChoice(s);
        return c != null && c.type == PendingChoice.Type.FREE_MOVE && c.player == player && s.consent == null;
    }

    /** Event cards may be played between card resolutions, also while someone is discarding to the hand limit. */
    public static boolean canPlayEvents(GameState s) {
        if (s.phase == GamePhase.SETUP || s.phase == GamePhase.GAME_OVER) return false;
        if (s.resolution != null || s.consent != null) return false;
        for (PendingChoice c : s.choices) if (c.type != PendingChoice.Type.DISCARD) return false;
        return true;
    }

    public static Set<Integer> carriageDestinations(GameState s, int player) {
        Set<Integer> out = new LinkedHashSet<>();
        for (int c : board().neighbors(s.player(player).city)) out.add(c);
        return out;
    }

    /** Cities reachable from {@code from} along a continuous chain of railroads. */
    public static Set<Integer> railReachable(GameState s, int from) {
        Set<Integer> seen = new LinkedHashSet<>();
        Deque<Integer> q = new ArrayDeque<>();
        q.add(from);
        seen.add(from);
        while (!q.isEmpty()) {
            int c = q.poll();
            for (int rid : board().routesOf(c)) {
                if (!s.rails[rid]) continue;
                int o = board().route(rid).other(c);
                if (seen.add(o)) q.add(o);
            }
        }
        seen.remove(from);
        return seen;
    }

    /** Cards {@code player} may discard to sail to {@code dest} (empty for the Sailor, who sails for free). */
    public static List<Integer> shipCards(GameState s, int player, int dest) {
        List<Integer> out = new ArrayList<>();
        DiseaseType color = board().city(dest).color;
        for (int card : s.player(player).hand) {
            if (PlayerCard.isCity(card) && PlayerCard.color(card) == color) out.add(card);
        }
        return out;
    }

    public static Set<Integer> shipDestinations(GameState s, int player) {
        Set<Integer> out = new LinkedHashSet<>();
        Player p = s.player(player);
        if (!board().city(p.city).port) return out;
        for (City c : board().cities) {
            if (!c.port || c.id == p.city) continue;
            if (p.is(Role.SAILOR) || !shipCards(s, player, c.id).isEmpty()) out.add(c.id);
        }
        return out;
    }

    public static boolean canRail(GameState s, int routeId) {
        Route r = board().route(routeId);
        return !r.dashed && !s.rails[routeId];
    }

    public static Set<Integer> buildableRoutes(GameState s, int city) {
        Set<Integer> out = new LinkedHashSet<>();
        if (s.railSupply <= 0) return out;
        for (int rid : board().routesOf(city)) if (canRail(s, rid)) out.add(rid);
        return out;
    }

    /** Routes that may hold the Railwayman's second token after {@code first} was built from {@code city}. */
    public static Set<Integer> secondRailRoutes(GameState s, int city, int first) {
        Set<Integer> out = new LinkedHashSet<>();
        if (s.railSupply < 2) return out;
        int far = board().route(first).other(city);
        for (int rid : board().routesOf(far)) if (rid != first && canRail(s, rid)) out.add(rid);
        return out;
    }

    public static boolean canUseRailwaymanDouble(GameState s, int player) {
        return s.player(player).is(Role.RAILWAYMAN) && !s.railwaymanDoubleUsed && s.railSupply >= 2;
    }

    /** City cards {@code player} may discard to purify {@code region}. */
    public static List<Integer> purifyCards(GameState s, int player, int region) {
        List<Integer> out = new ArrayList<>();
        Player p = s.player(player);
        Region r = board().region(region);
        Set<DiseaseType> regionColors = new HashSet<>();
        for (int c : r.cities) regionColors.add(board().city(c).color);
        for (int card : p.hand) {
            if (!PlayerCard.isCity(card)) continue;
            DiseaseType col = PlayerCard.color(card);
            if (p.is(Role.ROYAL_ACADEMY_SCIENTIST) || s.researched[col.ordinal()] || regionColors.contains(col)) {
                out.add(card);
            }
        }
        return out;
    }

    public static int[] adjacentRegions(GameState s, int player) {
        return board().regionsOf(s.player(player).city);
    }

    /** Actions needed to remove one cube (Typhus challenge makes crowded red cities cost 2). */
    public static int treatCost(GameState s, int city, DiseaseType color) {
        if (color == DiseaseType.RED && s.settings.historical(DiseaseType.RED) && s.cubesAt(city, color) > 1) return 2;
        return 1;
    }

    public static List<DiseaseType> colorsIn(GameState s, int city) {
        List<DiseaseType> out = new ArrayList<>();
        for (DiseaseType d : DiseaseType.values()) if (s.cubesAt(city, d) > 0) out.add(d);
        return out;
    }

    /** Cities where the Rural Doctor may remove the additional cube (own city included). */
    public static Set<Integer> ruralDoctorCities(GameState s, int player) {
        int city = s.player(player).city;
        Set<Integer> out = new LinkedHashSet<>();
        out.add(city);
        out.addAll(board().citiesInAdjacentRegions(city));
        return out;
    }

    public static List<Integer> playersInCity(GameState s, int city, int except) {
        List<Integer> out = new ArrayList<>();
        for (Player p : s.players) if (p.id != except && p.city == city) out.add(p.id);
        return out;
    }

    public static int researchHospitalColorAt(GameState s, int city) {
        for (DiseaseType d : DiseaseType.values()) if (s.hospitals[d.ordinal()] == city) return d.ordinal();
        return -1;
    }

    public static List<Integer> researchCandidates(GameState s, int player, DiseaseType color) {
        List<Integer> out = new ArrayList<>();
        for (int card : s.player(player).hand) if (PlayerCard.isCity(card) && PlayerCard.color(card) == color) out.add(card);
        return out;
    }

    /** Routes whose two endpoints are ports (Ring Railroads). */
    public static boolean isPortRoute(int routeId) {
        Route r = board().route(routeId);
        return board().city(r.a).port && board().city(r.b).port;
    }

    /** Seats (other than the actor) whose agreement the action needs. */
    public static List<Integer> affectedSeats(GameState s, GameAction a) {
        Set<Integer> out = new LinkedHashSet<>();
        switch (a.type) {
            case SHARE_GIVE:
            case SHARE_TAKE:
                out.add(a.target);
                break;
            case MOVE_TRAIN:
            case MOVE_SHIP:
                if (a.passenger >= 0) out.add(a.passenger);
                break;
            case PLAY_EVENT:
                EventType e = a.effectiveEvent();
                if (e == EventType.MAIL_CORRESPONDENCE) {
                    out.add(a.values.get(0));
                    out.add(a.values.get(2));
                } else if (e == EventType.TELEGRAPH_MESSAGE) {
                    out.add(a.values.get(0));
                    out.add(a.values.get(1));
                } else if (e == EventType.SHIPS_ARRIVE) {
                    out.addAll(a.values);
                } else if (e == EventType.TRAVEL_DAY_AND_NIGHT) {
                    out.add(a.target);
                }
                break;
            default:
                break;
        }
        out.remove(a.player);
        return new ArrayList<>(out);
    }

    // ================================================================= validation

    /** @return {@code null} when the action is legal, otherwise a human readable reason. */
    public static String validate(GameState s, GameAction a) {
        if (a == null || a.type == null) return "Ação inválida.";
        if (s.phase == GamePhase.GAME_OVER) return "O jogo terminou.";
        if (a.player < 0 || a.player >= s.players.size()) return "Jogador inválido.";

        if (s.consent != null && a.type != ActionType.CONSENT_REPLY && a.type != ActionType.CANCEL_REQUEST) {
            return "Aguardando a resposta de outro jogador.";
        }
        Player p = s.player(a.player);

        switch (a.type) {
            case CHOOSE_START_CITY:
                return validateStartCity(s, a);
            case CONSENT_REPLY:
                if (s.consent == null) return "Não há pedido pendente.";
                if (!s.consent.awaiting.contains(a.player)) return "Este pedido não é para você.";
                return null;
            case CANCEL_REQUEST:
                if (s.consent == null) return "Não há pedido pendente.";
                if (s.consent.actor != a.player) return "Só quem fez o pedido pode cancelá-lo.";
                return null;
            case DISCARD_CARD:
                if (discardChoiceFor(s, a.player) == null) return "Você não precisa descartar.";
                if (a.cards.size() != 1 || !p.hand.contains(a.cards.get(0))) return "Escolha uma carta da sua mão.";
                return null;
            case CHOOSE:
                return validateChoice(s, a);
            case PLAY_EVENT:
                return validateEvent(s, a);
            case END_ACTIONS:
                if (s.phase != GamePhase.ACTIONS || s.currentPlayer != a.player) return "Não é sua fase de ações.";
                if (!isFree(s)) return "Resolva as pendências primeiro.";
                return null;
            case DRAW_CARDS:
                if (s.phase != GamePhase.DRAW || s.currentPlayer != a.player) return "Não é hora de comprar cartas.";
                if (!isFree(s)) return "Resolva as pendências primeiro.";
                return null;
            case RESOLVE_EPIDEMIC:
                if (s.phase != GamePhase.EPIDEMIC_PENDING || s.currentPlayer != a.player) return "Não há epidemia pendente.";
                if (!isFree(s)) return "Resolva as pendências primeiro.";
                return null;
            case INFECT_NEXT:
            case INFECT_ALL:
                if (s.phase != GamePhase.INFECTION || s.currentPlayer != a.player) return "Não é a fase de infecção.";
                if (!isFree(s)) return "Resolva as pendências primeiro.";
                return null;
            default:
                break;
        }

        // ---- actions that cost an action (moves may also be free moves)
        boolean free = a.type.isMove() && hasFreeMove(s, a.player);
        if (!free) {
            if (s.phase != GamePhase.ACTIONS || s.currentPlayer != a.player) return "Não é a sua vez de agir.";
            if (!isFree(s)) return "Resolva as pendências primeiro.";
            if (s.actionsLeft <= 0) return "Você não tem mais ações.";
        }
        int city = p.city;

        switch (a.type) {
            case MOVE_CARRIAGE:
                if (!validCity(a.city) || !board().adjacent(city, a.city)) return "Essa cidade não está ligada à sua por uma linha.";
                if (a.passenger >= 0) return "Carruagem não leva passageiros.";
                return null;
            case MOVE_TRAIN:
                if (!railReachable(s, city).contains(a.city)) return "Não há ferrovia contínua até essa cidade.";
                return validatePassenger(s, a, Role.RAILWAYMAN, free);
            case MOVE_SHIP: {
                if (!validCity(a.city)) return "Destino inválido.";
                if (!board().city(city).port) return "Você precisa estar numa cidade portuária.";
                if (!board().city(a.city).port || a.city == city) return "O destino precisa ser outra cidade portuária.";
                if (p.is(Role.SAILOR)) {
                    if (!a.cards.isEmpty()) return "O Marinheiro navega sem gastar cartas.";
                } else {
                    if (a.cards.size() != 1) return "Descarte uma carta da cor do destino.";
                    int card = a.cards.get(0);
                    if (!p.hand.contains(card) || !shipCards(s, a.player, a.city).contains(card)) {
                        return "A carta precisa ser de Cidade da cor do destino.";
                    }
                }
                return validatePassenger(s, a, Role.SAILOR, free);
            }
            case BUILD_RAILROAD: {
                if (s.railSupply <= 0) return "Não há ferrovias na reserva.";
                if (!validRoute(a.route) || !board().route(a.route).touches(city)) return "Escolha uma linha que sai da sua cidade.";
                if (board().route(a.route).dashed) return "Linhas tracejadas não recebem ferrovias.";
                if (s.rails[a.route]) return "Essa linha já tem ferrovia.";
                if (a.route2 >= 0) {
                    if (!canUseRailwaymanDouble(s, a.player)) return "Só o Ferroviário (uma vez por turno, com 2 trilhos na reserva).";
                    if (!secondRailRoutes(s, city, a.route).contains(a.route2)) return "O segundo trilho precisa continuar o primeiro.";
                }
                return null;
            }
            case BUILD_HOSPITAL: {
                if (!p.hand.contains(city)) return "Você precisa da carta da cidade onde está.";
                DiseaseType col = board().city(city).color;
                if (s.hospitals[col.ordinal()] == city) return "O hospital já está aqui.";
                return null;
            }
            case TREAT_DISEASE: {
                if (a.color == null || s.cubesAt(city, a.color) <= 0) return "Não há cubos dessa cor aqui.";
                if (s.actionsLeft < treatCost(s, city, a.color)) return "Tifo: remover este cubo custa 2 ações.";
                if (a.city2 >= 0) {
                    if (!p.is(Role.RURAL_DOCTOR)) return "Só o Médico Rural remove um cubo adicional.";
                    if (a.color2 == null || !ruralDoctorCities(s, a.player).contains(a.city2)) return "Cidade inválida para o cubo adicional.";
                    int available = s.cubesAt(a.city2, a.color2) - (a.city2 == city && a.color2 == a.color ? 1 : 0);
                    if (available <= 0) return "Não há cubo adicional dessa cor lá.";
                }
                return null;
            }
            case SHARE_GIVE:
            case SHARE_TAKE: {
                if (!validPlayer(s, a.target) || a.target == a.player) return "Escolha outro jogador.";
                if (s.player(a.target).city != city) return "O outro jogador precisa estar na sua cidade.";
                Player giver = a.type == ActionType.SHARE_GIVE ? p : s.player(a.target);
                if (!giver.hand.contains(city)) return "Quem dá precisa ter a carta da cidade onde vocês estão.";
                return null;
            }
            case RESEARCH: {
                int hc = researchHospitalColorAt(s, city);
                if (hc < 0) return "Você precisa estar numa cidade com hospital.";
                DiseaseType col = DiseaseType.values()[hc];
                if (s.researched[hc]) return "Essa doença já foi pesquisada.";
                if (a.cards.size() != GameState.RESEARCH_CARDS || new HashSet<>(a.cards).size() != GameState.RESEARCH_CARDS) {
                    return "Descarte exatamente 5 cartas " + col.colorName + "s.";
                }
                for (int c : a.cards) {
                    if (!p.hand.contains(c) || PlayerCard.color(c) != col) return "Todas as cartas precisam ser " + col.colorName + "s.";
                }
                return null;
            }
            case PURIFY_WATER: {
                if (s.purificationSupply <= 0) return "Não há fichas de purificação na reserva.";
                if (!regionAdjacent(city, a.region)) return "Escolha uma região adjacente à sua cidade.";
                if (a.cards.size() != 1 || !purifyCards(s, a.player, a.region).contains(a.cards.get(0))) {
                    return "A carta precisa ser da cor de uma cidade da região (ou de doença pesquisada).";
                }
                return null;
            }
            case AGRONOMIST_PURIFY:
                if (!p.is(Role.AGRONOMIST)) return "Só o Agrônomo.";
                if (s.purificationSupply <= 0) return "Não há fichas de purificação na reserva.";
                if (!regionAdjacent(city, a.region)) return "Escolha uma região adjacente à sua cidade.";
                return null;
            case POLITICIAN_GIVE:
                if (!p.is(Role.POLITICIAN)) return "Só o Político.";
                if (!validPlayer(s, a.target) || a.target == a.player) return "Escolha outro jogador.";
                if (!p.hand.contains(city)) return "Você precisa da carta da cidade onde está.";
                return null;
            case POLITICIAN_SWAP: {
                if (!p.is(Role.POLITICIAN)) return "Só o Político.";
                if (a.cards.size() != 2) return "Escolha uma carta da mão e uma do descarte.";
                int mine = a.cards.get(0), disc = a.cards.get(1);
                if (!PlayerCard.isCity(mine) || !p.hand.contains(mine)) return "A carta da mão precisa ser de Cidade.";
                if (!PlayerCard.isCity(disc) || !s.playerDiscard.contains(disc)) return "A carta do descarte precisa ser de Cidade.";
                if (city != mine && city != disc) return "Você precisa estar na cidade de uma das cartas trocadas.";
                return null;
            }
            case SCIENTIST_PEEK:
                if (!p.is(Role.ROYAL_ACADEMY_SCIENTIST)) return "Só o Cientista da Academia Real.";
                if (s.playerDeckSize() <= 0) return "O baralho de Jogador está vazio.";
                return null;
            default:
                return "Ação desconhecida.";
        }
    }

    private static String validatePassenger(GameState s, GameAction a, Role role, boolean free) {
        if (a.passenger < 0) return null;
        if (free) return "Movimentos gratuitos não levam passageiros.";
        if (!s.player(a.player).is(role)) return "Só o " + role.title + " leva passageiro.";
        if (!validPlayer(s, a.passenger) || a.passenger == a.player) return "Passageiro inválido.";
        if (s.player(a.passenger).city != s.player(a.player).city) return "O passageiro precisa estar na sua cidade.";
        return null;
    }

    private static String validateStartCity(GameState s, GameAction a) {
        if (s.phase != GamePhase.SETUP) return "A preparação já terminou.";
        for (PendingChoice c : s.choices) {
            if (c.type == PendingChoice.Type.START_CITY && c.player == a.player) {
                return c.options.contains(a.city) ? null : "Escolha uma cidade de uma das suas cartas.";
            }
        }
        return "Você já escolheu sua cidade inicial.";
    }

    private static String validateChoice(GameState s, GameAction a) {
        PendingChoice c = blockingChoice(s);
        if (c == null) return "Não há escolha pendente.";
        if (c.player != a.player) return "A escolha pendente é de outro jogador.";
        switch (c.type) {
            case NURSE_TOKEN:
            case PURIFICATION_REMOVAL:
                return c.options.contains(a.region) ? null : "Escolha uma das regiões destacadas.";
            case SCIENTIST_ORDER:
                if (a.values.size() != c.options.size() || !new HashSet<>(a.values).equals(new HashSet<>(c.options))) {
                    return "A nova ordem precisa conter as mesmas cartas.";
                }
                return null;
            case FREE_MOVE:
                return a.accept ? "Use uma ação de movimento para o movimento gratuito." : null;
            default:
                return "Escolha inválida.";
        }
    }

    private static String validateEvent(GameState s, GameAction a) {
        if (a.event == null) return "Evento inválido.";
        Player p = s.player(a.player);
        int card = PlayerCard.ofEvent(a.event);
        if (!p.hand.contains(card)) return "Você não tem essa carta de Evento.";
        if (!canPlayEvents(s)) return "Eventos não podem ser jogados agora.";
        if (a.event == EventType.WHEN_THE_PLANS_WERE_GOOD) {
            if (a.subEvent == null || a.subEvent == EventType.WHEN_THE_PLANS_WERE_GOOD) return "Escolha um Evento do descarte.";
            if (!s.playerDiscard.contains(PlayerCard.ofEvent(a.subEvent))) return "Esse Evento não está no descarte.";
        }
        return validateEventParams(s, a, a.effectiveEvent(), card);
    }

    /**
     * Event parameters:
     * <ul>
     * <li>HOSPITAL_FOUNDING: color, city</li>
     * <li>MAIL_CORRESPONDENCE: values = [playerA, cardA, playerB, cardB]</li>
     * <li>NEW_RAILS: values = 1-2 routes; RING_RAILROADS: values = 1-3 port routes</li>
     * <li>OVERSEAS_MIGRATION: values = 1-2 pairs (city, colour ordinal)</li>
     * <li>PURIFY_WATER: values = 1-2 regions (repeat a region to put both tokens there)</li>
     * <li>SCIENCE_TRIUMPH: region, values = pairs (city, colour ordinal), at most one per city</li>
     * <li>SHIPS_ARRIVE: city (port), values = seats to move</li>
     * <li>TELEGRAPH_MESSAGE: values = [giver, receiver, card1, (card2)]</li>
     * <li>TRAVEL_DAY_AND_NIGHT: target, city</li>
     * </ul>
     */
    static String validateEventParams(GameState s, GameAction a, EventType e, int playedCard) {
        Player p = s.player(a.player);
        List<Integer> v = a.values;
        switch (e) {
            case GOOD_SEASON:
            case GOVERNMENT_MOBILIZATION:
                return null;
            case ONE_MORE_DAY:
                return s.phase == GamePhase.ACTIONS ? null : "Só durante a fase de ações.";
            case HOSPITAL_FOUNDING:
                if (a.color == null || !validCity(a.city)) return "Escolha a cor e a cidade.";
                if (board().city(a.city).color != a.color) return "O hospital precisa ir para uma cidade da sua cor.";
                if (s.hospitals[a.color.ordinal()] == a.city) return "O hospital já está lá.";
                return null;
            case MAIL_CORRESPONDENCE: {
                if (v.size() != 4) return "Escolha dois jogadores e uma carta de cada.";
                int pa = v.get(0), ca = v.get(1), pb = v.get(2), cb = v.get(3);
                if (!validPlayer(s, pa) || !validPlayer(s, pb) || pa == pb) return "Escolha dois jogadores diferentes.";
                if (!s.player(pa).hand.contains(ca) || !s.player(pb).hand.contains(cb)) return "As cartas precisam estar nas mãos.";
                if (ca == playedCard || cb == playedCard) return "A carta jogada não pode ser trocada.";
                return null;
            }
            case NEW_RAILS:
            case RING_RAILROADS: {
                int max = e == EventType.NEW_RAILS ? 2 : 3;
                if (v.isEmpty() || v.size() > max) return "Escolha de 1 a " + max + " linhas.";
                if (new HashSet<>(v).size() != v.size()) return "Linhas repetidas.";
                if (s.railSupply < v.size()) return "Não há ferrovias suficientes na reserva.";
                for (int r : v) {
                    if (!validRoute(r) || !canRail(s, r)) return "Linha inválida (tracejada ou já com ferrovia).";
                    if (e == EventType.RING_RAILROADS && !isPortRoute(r)) return "As linhas precisam ligar duas cidades portuárias.";
                }
                return null;
            }
            case OVERSEAS_MIGRATION: {
                if (v.isEmpty() || v.size() % 2 != 0 || v.size() > 4) return "Escolha até 2 cubos.";
                int[][] need = new int[board().cityCount()][DiseaseType.COUNT];
                for (int i = 0; i < v.size(); i += 2) {
                    if (!validCity(v.get(i)) || !validColor(v.get(i + 1))) return "Cubo inválido.";
                    need[v.get(i)][v.get(i + 1)]++;
                    if (s.cubes[v.get(i)][v.get(i + 1)] < need[v.get(i)][v.get(i + 1)]) return "Não há cubos suficientes lá.";
                }
                return null;
            }
            case PURIFY_WATER: {
                if (v.isEmpty() || v.size() > 2) return "Escolha 1 ou 2 regiões.";
                if (s.purificationSupply <= 0) return "Não há fichas de purificação na reserva.";
                for (int r : v) if (!validRegion(r)) return "Região inválida.";
                return null;
            }
            case SCIENCE_TRIUMPH: {
                if (!validRegion(a.region)) return "Escolha uma região.";
                if (v.size() % 2 != 0) return "Parâmetros inválidos.";
                Set<Integer> seen = new HashSet<>();
                for (int i = 0; i < v.size(); i += 2) {
                    int c = v.get(i);
                    if (!validCity(c) || !board().regionTouches(a.region, c) || !seen.add(c)) return "Só 1 cubo por cidade da região.";
                    if (!validColor(v.get(i + 1)) || s.cubes[c][v.get(i + 1)] <= 0) return "Não há cubo dessa cor lá.";
                }
                return null;
            }
            case SECOND_CHANCE:
                return s.playerDiscard.contains(p.city) ? null : "A carta da sua cidade não está no descarte.";
            case SHIPS_ARRIVE:
                if (!validCity(a.city) || !board().city(a.city).port) return "Escolha uma cidade portuária.";
                if (v.isEmpty()) return "Escolha ao menos um peão.";
                if (new HashSet<>(v).size() != v.size()) return "Peões repetidos.";
                for (int pl : v) if (!validPlayer(s, pl)) return "Jogador inválido.";
                return null;
            case TELEGRAPH_MESSAGE: {
                if (v.size() < 3 || v.size() > 4) return "Escolha quem dá, quem recebe e 1 ou 2 cartas.";
                int giver = v.get(0), receiver = v.get(1);
                if (!validPlayer(s, giver) || !validPlayer(s, receiver) || giver == receiver) return "Escolha dois jogadores diferentes.";
                List<Integer> cards = v.subList(2, v.size());
                if (new HashSet<>(cards).size() != cards.size()) return "Cartas repetidas.";
                for (int c : cards) {
                    if (!PlayerCard.isCity(c) || !s.player(giver).hand.contains(c)) return "Só cartas de Cidade da mão de quem dá.";
                }
                return null;
            }
            case TRAVEL_DAY_AND_NIGHT:
                if (!validPlayer(s, a.target) || !validCity(a.city)) return "Escolha o peão e a cidade.";
                if (s.player(a.target).city == a.city) return "O peão já está lá.";
                return null;
            default:
                return "Evento inválido.";
        }
    }

    // ================================================================= helpers

    static boolean regionAdjacent(int city, int region) {
        return validRegion(region) && board().regionTouches(region, city);
    }

    static boolean validCity(int c) {
        return c >= 0 && c < board().cityCount();
    }

    static boolean validRoute(int r) {
        return r >= 0 && r < board().routes.size();
    }

    static boolean validRegion(int r) {
        return r >= 0 && r < board().regions.size();
    }

    static boolean validColor(int c) {
        return c >= 0 && c < DiseaseType.COUNT;
    }

    static boolean validPlayer(GameState s, int p) {
        return p >= 0 && p < s.players.size();
    }
}
