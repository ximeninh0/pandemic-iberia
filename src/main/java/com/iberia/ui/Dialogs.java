package com.iberia.ui;

import com.iberia.model.DiseaseType;
import com.iberia.model.PlayerCard;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Small modal dialogs used by the action flows, all in the sepia theme. */
public final class Dialogs {
    private Dialogs() {
    }

    private static Window owner;

    public static void setOwner(Window w) {
        owner = w;
    }

    private static <T> Dialog<T> base(String title, String header) {
        Dialog<T> d = new Dialog<>();
        if (owner != null) d.initOwner(owner);
        d.setTitle(title);
        d.setHeaderText(header);
        d.getDialogPane().getStylesheets().add(Dialogs.class.getResource("/com/iberia/iberia.css").toExternalForm());
        d.getDialogPane().getStyleClass().add("iberia-dialog");
        return d;
    }

    public static boolean confirm(String title, String text) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL);
        if (owner != null) a.initOwner(owner);
        a.setTitle(title);
        a.setHeaderText(title);
        a.getDialogPane().getStylesheets().add(Dialogs.class.getResource("/com/iberia/iberia.css").toExternalForm());
        return a.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    public static void info(String title, String text) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, text, ButtonType.OK);
        if (owner != null) a.initOwner(owner);
        a.setTitle(title);
        a.setHeaderText(title);
        a.getDialogPane().getStylesheets().add(Dialogs.class.getResource("/com/iberia/iberia.css").toExternalForm());
        a.showAndWait();
    }

    /** Pick one item from a list. */
    public static <T> Optional<T> chooseOne(String title, String header, List<T> items, Function<T, String> label) {
        if (items.isEmpty()) return Optional.empty();
        Dialog<T> d = base(title, header);
        ChoiceBox<T> box = new ChoiceBox<>();
        box.getItems().addAll(items);
        box.getSelectionModel().select(0);
        box.setConverter(converter(label));
        box.setMaxWidth(Double.MAX_VALUE);
        VBox content = new VBox(8, box);
        content.setPadding(new Insets(10));
        content.setPrefWidth(380);
        d.getDialogPane().setContent(content);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.setResultConverter(b -> b == ButtonType.OK ? box.getValue() : null);
        return d.showAndWait();
    }

    /** Pick one item by clicking a button (good for 2–6 options). */
    public static <T> Optional<T> chooseButtons(String title, String header, List<T> items, Function<T, String> label) {
        if (items.isEmpty()) return Optional.empty();
        Dialog<T> d = base(title, header);
        VBox box = new VBox(6);
        box.setPadding(new Insets(10));
        box.setPrefWidth(360);
        final Object[] result = new Object[1];
        for (T it : items) {
            Button b = new Button(label.apply(it));
            b.setMaxWidth(Double.MAX_VALUE);
            b.setOnAction(e -> {
                result[0] = it;
                d.setResult(it);
                d.close();
            });
            box.getChildren().add(b);
        }
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        d.setResultConverter(b -> null);
        @SuppressWarnings("unchecked")
        Optional<T> r = d.showAndWait().or(() -> Optional.ofNullable((T) result[0]));
        return r;
    }

    public static Optional<DiseaseType> chooseColor(String title, String header, List<DiseaseType> colors) {
        if (colors.size() == 1) return Optional.of(colors.get(0));
        return chooseButtons(title, header, colors, c -> c.diseaseName + " (" + c.colorName + ")");
    }

    /**
     * Choose between {@code min} and {@code max} cards by clicking them.
     */
    public static Optional<List<Integer>> chooseCards(String title, String header, List<Integer> cards, int min, int max) {
        Dialog<List<Integer>> d = base(title, header);
        FlowPane flow = new FlowPane(8, 10);
        flow.setPadding(new Insets(14, 8, 8, 8));
        flow.setPrefWrapLength(Math.min(6, Math.max(3, cards.size())) * (CardView.W + 10));
        List<CardView> views = new ArrayList<>();
        Label count = new Label();
        ButtonType ok = new ButtonType("Confirmar", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        Node okButton = d.getDialogPane().lookupButton(ok);
        Runnable refresh = () -> {
            long n = views.stream().filter(CardView::isSelected).count();
            count.setText(n + " selecionada(s) — escolha " + (min == max ? String.valueOf(min) : min + " a " + max));
            okButton.setDisable(n < min || n > max);
        };
        for (int c : cards) {
            CardView v = new CardView(c);
            v.setOnMouseClicked(e -> {
                long n = views.stream().filter(CardView::isSelected).count();
                if (!v.isSelected() && n >= max && max == 1) views.forEach(o -> o.setSelected(false));
                else if (!v.isSelected() && n >= max) return;
                v.setSelected(!v.isSelected());
                refresh.run();
            });
            views.add(v);
            flow.getChildren().add(v);
        }
        if (cards.size() == min && min == max) views.forEach(v -> v.setSelected(true));
        refresh.run();
        ScrollPane sp = new ScrollPane(flow);
        sp.setFitToWidth(true);
        sp.setPrefViewportHeight(Math.min(2, (cards.size() + 5) / 6) * (CardView.H + 18) + 10);
        VBox content = new VBox(6, sp, count);
        d.getDialogPane().setContent(content);
        d.setResultConverter(b -> {
            if (b != ok) return null;
            List<Integer> out = new ArrayList<>();
            for (CardView v : views) if (v.isSelected()) out.add(v.card());
            return out;
        });
        return d.showAndWait();
    }

    public static Optional<Integer> chooseCard(String title, String header, List<Integer> cards) {
        if (cards.size() == 1) return Optional.of(cards.get(0));
        return chooseCards(title, header, cards, 1, 1).map(l -> l.get(0));
    }

    /** Reorder cards (index 0 = top of the deck). */
    public static Optional<List<Integer>> reorder(String title, String header, List<Integer> cards) {
        Dialog<List<Integer>> d = base(title, header);
        List<Integer> order = new ArrayList<>(cards);
        HBox row = new HBox(10);
        row.setPadding(new Insets(10));
        row.setAlignment(Pos.CENTER);
        Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            row.getChildren().clear();
            for (int i = 0; i < order.size(); i++) {
                final int idx = i;
                Label pos = new Label(i == 0 ? "Topo" : (i + 1) + "ª");
                Button left = new Button("<");
                Button right = new Button(">");
                left.setDisable(i == 0);
                right.setDisable(i == order.size() - 1);
                left.setOnAction(e -> {
                    java.util.Collections.swap(order, idx, idx - 1);
                    rebuild[0].run();
                });
                right.setOnAction(e -> {
                    java.util.Collections.swap(order, idx, idx + 1);
                    rebuild[0].run();
                });
                HBox arrows = new HBox(4, left, right);
                arrows.setAlignment(Pos.CENTER);
                VBox col = new VBox(6, pos, new CardView(order.get(i)), arrows);
                col.setAlignment(Pos.CENTER);
                row.getChildren().add(col);
            }
        };
        rebuild[0].run();
        d.getDialogPane().setContent(row);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK);
        d.setResultConverter(b -> new ArrayList<>(order));
        return d.showAndWait();
    }

    /** Multiple choice with checkboxes. */
    public static <T> Optional<List<T>> chooseMany(String title, String header, List<T> items, Function<T, String> label,
                                                   List<T> preselected) {
        Dialog<List<T>> d = base(title, header);
        VBox box = new VBox(6);
        box.setPadding(new Insets(10));
        List<CheckBox> checks = new ArrayList<>();
        for (T it : items) {
            CheckBox cb = new CheckBox(label.apply(it));
            cb.setSelected(preselected.contains(it));
            checks.add(cb);
            box.getChildren().add(cb);
        }
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.setResultConverter(b -> {
            if (b != ButtonType.OK) return null;
            List<T> out = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) if (checks.get(i).isSelected()) out.add(items.get(i));
            return out;
        });
        return d.showAndWait();
    }

    /** Form with labelled choice boxes; returns the chosen index per row. */
    public static Optional<int[]> form(String title, String header, List<String> labels, List<List<String>> options) {
        Dialog<int[]> d = base(title, header);
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        List<ChoiceBox<String>> boxes = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            ChoiceBox<String> cb = new ChoiceBox<>();
            cb.getItems().addAll(options.get(i));
            cb.getSelectionModel().select(0);
            cb.setPrefWidth(260);
            boxes.add(cb);
            grid.addRow(i, new Label(labels.get(i)), cb);
        }
        d.getDialogPane().setContent(grid);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.setResultConverter(b -> {
            if (b != ButtonType.OK) return null;
            int[] out = new int[boxes.size()];
            for (int i = 0; i < out.length; i++) out[i] = boxes.get(i).getSelectionModel().getSelectedIndex();
            return out;
        });
        return d.showAndWait();
    }

    public static String cardLabel(int card) {
        if (PlayerCard.isCity(card)) return PlayerCard.name(card) + " (" + PlayerCard.color(card).colorName + ")";
        return PlayerCard.name(card) + " (Evento)";
    }

    private static <T> StringConverter<T> converter(Function<T, String> label) {
        return new StringConverter<>() {
            @Override
            public String toString(T t) {
                return t == null ? "" : label.apply(t);
            }

            @Override
            public T fromString(String s) {
                return null;
            }
        };
    }
}
