package com.iberia.model;

/** The 15 Event cards. Playing one is never an action; it can be played at any time between card resolutions. */
public enum EventType {
    GOOD_SEASON("Boa Estação",
            "Na próxima etapa de infecção, revele só 1 carta de Infecção, tirada do fundo do baralho."),
    GOVERNMENT_MOBILIZATION("Mobilização do Governo",
            "Cada jogador pode mover seu peão uma vez, de graça, de carruagem, trem ou navio."),
    HOSPITAL_FOUNDING("Fundação de Hospital",
            "Coloque ou mova um hospital para uma cidade da sua cor."),
    MAIL_CORRESPONDENCE("Correspondência Postal",
            "Dois jogadores, em qualquer lugar do tabuleiro, trocam 1 carta entre si."),
    NEW_RAILS("Novos Trilhos",
            "Coloque até 2 ferrovias em qualquer lugar (nunca em linhas tracejadas)."),
    ONE_MORE_DAY("Mais Um Dia",
            "O jogador da vez ganha 2 ações extras neste turno."),
    OVERSEAS_MIGRATION("Migração Ultramarina",
            "Remova até 2 cubos de doença do tabuleiro."),
    PURIFY_WATER("Purificar Água",
            "Coloque até 2 fichas de purificação em uma ou duas regiões diferentes."),
    RING_RAILROADS("Anel Ferroviário",
            "Coloque até 3 ferrovias em linhas que ligam duas cidades portuárias (não tracejadas)."),
    SCIENCE_TRIUMPH("Triunfo da Ciência",
            "Remova até 1 cubo de cada cidade de uma região."),
    SECOND_CHANCE("Segunda Chance",
            "Pegue do descarte de Jogador a carta da cidade onde você está."),
    SHIPS_ARRIVE("Chegada dos Navios",
            "Escolha uma cidade portuária e leve para lá 1 ou mais peões de qualquer lugar (com permissão)."),
    TELEGRAPH_MESSAGE("Mensagem Telegráfica",
            "Um jogador pode dar 1 ou 2 cartas de Cidade a outro jogador, em qualquer lugar."),
    TRAVEL_DAY_AND_NIGHT("Viagem Dia e Noite",
            "Mova um peão para qualquer cidade do tabuleiro (com permissão)."),
    WHEN_THE_PLANS_WERE_GOOD("Quando os Planos Eram Bons",
            "Pegue 1 carta de Evento do descarte de Jogador e jogue-a imediatamente.");

    public final String title;
    public final String text;

    EventType(String title, String text) {
        this.title = title;
        this.text = text;
    }
}
