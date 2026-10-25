package com.iberia.model;

/** The seven roles of Pandemic Iberia. Ability texts are paraphrased. */
public enum Role {
    AGRONOMIST("Agrônomo", "#3f8f3a",
            "Ação: coloque 1 ficha de purificação em uma região adjacente.",
            "Ao Purificar Água, coloque 1 ficha extra na região."),
    NURSE("Enfermeira", "#e06aa8",
            "Seu token de prevenção fica numa região adjacente; cidades vizinhas a ele não recebem cubos.",
            "Sempre que seu peão se mover, recoloque o token numa região adjacente."),
    POLITICIAN("Político", "#e8e4dc",
            "Ação: dê a carta da cidade onde está a outro jogador em qualquer cidade.",
            "Ação: troque uma carta de Cidade da mão com uma do descarte (estando numa das duas cidades)."),
    RAILWAYMAN("Ferroviário", "#2a2a2a",
            "Uma vez por turno, Construir Ferrovia coloca 2 trilhos consecutivos a partir da sua cidade.",
            "Ao viajar de trem, pode levar 1 peão da sua cidade."),
    ROYAL_ACADEMY_SCIENTIST("Cientista da Academia Real", "#d9b21c",
            "Ao Purificar Água, pode usar qualquer carta de Cidade.",
            "Ação: olhe as 3 próximas cartas do baralho de Jogador e reordene-as."),
    RURAL_DOCTOR("Médico Rural", "#c0392b",
            "Ao Tratar Doença, remova 1 cubo da sua cidade e mais 1 da sua cidade",
            "ou de outra cidade numa região adjacente."),
    SAILOR("Marinheiro", "#27a3c4",
            "Viajar de navio não custa carta.",
            "Ao viajar de navio, pode levar 1 peão da sua cidade.");

    public final String title;
    public final String pawnHex;
    public final String ability1;
    public final String ability2;

    Role(String title, String pawnHex, String ability1, String ability2) {
        this.title = title;
        this.pawnHex = pawnHex;
        this.ability1 = ability1;
        this.ability2 = ability2;
    }

    public String abilities() {
        return "• " + ability1 + "\n• " + ability2;
    }
}
