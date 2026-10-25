package com.iberia.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.EnumMap;
import java.util.Map;

/** Action bar. Buttons are enabled/disabled by the game screen from {@link com.iberia.engine.RuleValidator}. */
public final class ActionControlsView extends VBox {
    public enum Key {
        CARRIAGE("Carruagem / Barco", "Mover para uma cidade ligada por uma linha (1 ação)."),
        TRAIN("Trem", "Mover por uma ferrovia contínua, qualquer distância (1 ação)."),
        SHIP("Navio", "De um porto a outro, descartando uma carta da cor do destino (1 ação)."),
        RAIL("Construir ferrovia", "Colocar um trilho numa linha que sai da sua cidade (1 ação)."),
        HOSPITAL("Construir hospital", "Descartar a carta da sua cidade para pôr o hospital dessa cor aqui (1 ação)."),
        TREAT("Tratar doença", "Remover 1 cubo da sua cidade (1 ação)."),
        PURIFY("Purificar água", "Descartar uma carta para pôr 2 fichas numa região adjacente (1 ação)."),
        SHARE("Compartilhar", "Dar ou pegar a carta da cidade onde vocês estão (1 ação)."),
        RESEARCH("Pesquisar doença", "No hospital, descartar 5 cartas da cor dele (1 ação)."),
        ROLE1("Habilidade", ""),
        ROLE2("Habilidade", ""),
        END_ACTIONS("Encerrar ações", "Abrir mão das ações restantes."),
        DRAW("Comprar 2 cartas", "Comprar as 2 cartas do baralho de Jogador."),
        EPIDEMIC("Resolver epidemia", "Resolver a segunda Epidemia comprada."),
        INFECT("Infectar cidade", "Revelar a próxima carta de Infecção."),
        INFECT_ALL("Infectar todas", "Revelar todas as cartas de Infecção restantes.");

        public final String label;
        public final String help;

        Key(String label, String help) {
            this.label = label;
            this.help = help;
        }
    }

    private final Map<Key, Button> buttons = new EnumMap<>(Key.class);

    public ActionControlsView() {
        getStyleClass().add("action-bar");
        setSpacing(4);
        setPadding(new Insets(6, 10, 6, 10));
        Label title = new Label("Ações");
        title.getStyleClass().add("panel-title");
        GridPane grid = new GridPane();
        grid.setHgap(5);
        grid.setVgap(5);
        Key[][] layout = {
                {Key.CARRIAGE, Key.TRAIN, Key.SHIP, Key.RAIL},
                {Key.TREAT, Key.PURIFY, Key.HOSPITAL, Key.RESEARCH},
                {Key.SHARE, Key.ROLE1, Key.ROLE2, Key.END_ACTIONS},
                {Key.DRAW, Key.EPIDEMIC, Key.INFECT, Key.INFECT_ALL},
        };
        for (int r = 0; r < layout.length; r++) {
            for (int c = 0; c < layout[r].length; c++) {
                Key k = layout[r][c];
                Button b = new Button(k.label);
                b.setMaxWidth(Double.MAX_VALUE);
                b.setPrefWidth(132);
                b.getStyleClass().add("action-button");
                if (r == 3 || k == Key.END_ACTIONS) b.getStyleClass().add("flow-button");
                if (!k.help.isEmpty()) b.setTooltip(new Tooltip(k.help));
                b.setDisable(true);
                buttons.put(k, b);
                grid.add(b, c, r);
            }
        }
        getChildren().addAll(title, grid);
    }

    public void setOnAction(Key k, Runnable r) {
        buttons.get(k).setOnAction(e -> r.run());
    }

    public void setEnabled(Key k, boolean enabled) {
        buttons.get(k).setDisable(!enabled);
    }

    public void configureRole(Key k, String label, String help) {
        Button b = buttons.get(k);
        b.setVisible(label != null);
        b.setManaged(true);
        if (label != null) {
            b.setText(label);
            b.setTooltip(new Tooltip(help));
        }
    }

    public void disableAll() {
        buttons.values().forEach(b -> b.setDisable(true));
    }
}
