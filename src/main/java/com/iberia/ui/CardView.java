package com.iberia.ui;

import com.iberia.model.City;
import com.iberia.model.EventType;
import com.iberia.model.PlayerCard;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

/**
 * A Player card: City cards show a colour banner and a small engraved map of the
 * peninsula locating the city; Event cards show their effect text.
 */
public final class CardView extends StackPane {
    public static final double W = 104, H = 144;
    private final int card;
    private boolean selected;

    public CardView(int card) {
        this(card, 1.0);
    }

    public CardView(int card, double scale) {
        this.card = card;
        double w = W * scale, h = H * scale;
        setPrefSize(w, h);
        setMinSize(w, h);
        setMaxSize(w, h);
        getStyleClass().add("card");
        switch (PlayerCard.kind(card)) {
            case CITY:
                getChildren().add(cityCanvas(PlayerCard.city(card), w, h, scale));
                Tooltip.install(this, new Tooltip(PlayerCard.city(card).name + " — carta " + PlayerCard.color(card).colorName
                        + (PlayerCard.city(card).port ? " (porto)" : "")));
                break;
            case EVENT:
                getChildren().add(eventBox(PlayerCard.event(card), w, h, scale));
                Tooltip.install(this, new Tooltip(PlayerCard.event(card).title + ": " + PlayerCard.event(card).text));
                break;
            default:
                getChildren().add(epidemicBox(w, h, scale));
        }
        setEffect(new DropShadow(4, 1, 2, Color.web("#000000", 0.35)));
    }

    public int card() {
        return card;
    }

    public void setSelected(boolean sel) {
        this.selected = sel;
        setTranslateY(sel ? -10 : 0);
        setEffect(new DropShadow(sel ? 14 : 4, 1, 2, sel ? Color.web("#ffcf33") : Color.web("#000000", 0.35)));
    }

    public boolean isSelected() {
        return selected;
    }

    /** Dim cards that cannot be used for the current choice. */
    public void setUsable(boolean usable) {
        setOpacity(usable ? 1 : 0.45);
    }

    // ------------------------------------------------------------------ rendering

    private static Canvas cityCanvas(City c, double w, double h, double s) {
        Canvas cv = new Canvas(w, h);
        GraphicsContext g = cv.getGraphicsContext2D();
        double arc = 12 * s;
        Color paper = Color.web("#f4ead3");
        Color col = Theme.color(c.color);
        // mini map first (no clip(): margins are painted over it afterwards)
        double mx = 7 * s, my = 38 * s, mw = w - 14 * s, mh = 70 * s;
        g.setFill(Theme.SEA);
        g.fillRect(mx, my, mw, mh);
        double bx0 = 88, by0 = 112, bx1 = 900, by1 = 752;
        double sc = Math.min(mw / (bx1 - bx0), mh / (by1 - by0));
        double ox = mx + (mw - (bx1 - bx0) * sc) / 2 - bx0 * sc, oy = my + (mh - (by1 - by0) * sc) / 2 - by0 * sc;
        g.setFill(Theme.LAND);
        g.setStroke(Color.web("#6b5236"));
        g.setLineWidth(0.7);
        for (double[] p : MapGeometry.coast()) {
            double[] xs = new double[p.length / 2], ys = new double[p.length / 2];
            for (int i = 0; i < xs.length; i++) {
                xs[i] = p[2 * i] * sc + ox;
                ys[i] = p[2 * i + 1] * sc + oy;
            }
            g.fillPolygon(xs, ys, xs.length);
            g.strokePolygon(xs, ys, xs.length);
        }
        g.setFill(paper);
        g.fillRect(0, 0, w, my);
        g.fillRect(0, my + mh, w, h - my - mh);
        g.fillRect(0, my, mx, mh);
        g.fillRect(mx + mw, my, w - mx - mw, mh);
        javafx.scene.shape.Rectangle round = new javafx.scene.shape.Rectangle(w, h);
        round.setArcWidth(arc);
        round.setArcHeight(arc);
        cv.setClip(round);
        // banner
        g.setFill(col);
        g.fillRoundRect(3 * s, 3 * s, w - 6 * s, 30 * s, arc * 0.7, arc * 0.7);
        g.setFill(Theme.text(c.color));
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        double fs = 13 * s;
        Text probe = new Text(c.name);
        probe.setFont(Theme.serifBold(fs));
        while (probe.getLayoutBounds().getWidth() > w - 12 * s && fs > 7) {
            fs -= 0.5;
            probe.setFont(Theme.serifBold(fs));
        }
        g.setFont(Theme.serifBold(fs));
        g.fillText(c.name, w / 2, 18 * s);
        double cx = c.x * sc + ox, cy = c.y * sc + oy, r = 4.2 * s;
        g.setFill(Color.web("#000000", 0.3));
        g.fillOval(cx - r + 1, cy - r + 1, 2 * r, 2 * r);
        g.setFill(col);
        g.fillOval(cx - r, cy - r, 2 * r, 2 * r);
        g.setStroke(Color.web("#b3261e"));
        g.setLineWidth(1.6 * s);
        g.strokeOval(cx - r - 3 * s, cy - r - 3 * s, 2 * r + 6 * s, 2 * r + 6 * s);
        g.setStroke(Color.web("#3b2a1a"));
        g.setLineWidth(1);
        g.strokeRect(mx, my, mw, mh);
        // footer
        g.setFill(Theme.INK);
        g.setFont(Theme.serifItalic(9 * s));
        g.fillText("Fundada em " + c.foundedLabel(), w / 2, my + mh + 11 * s);
        g.setFont(Theme.serifBold(9.5 * s));
        g.setFill(col.deriveColor(0, 1, 0.8, 1));
        g.fillText(c.color.diseaseName + (c.port ? "  ·  porto" : ""), w / 2, my + mh + 25 * s);
        // border
        g.setStroke(Color.web("#3b2a1a"));
        g.setLineWidth(2 * s);
        g.strokeRoundRect(1, 1, w - 2, h - 2, arc, arc);
        return cv;
    }

    private static VBox eventBox(EventType e, double w, double h, double s) {
        Label tag = new Label("EVENTO");
        tag.setFont(Theme.serifBold(9 * s));
        tag.setTextFill(Color.web("#f7e7b0"));
        tag.setMaxWidth(Double.MAX_VALUE);
        tag.setAlignment(Pos.CENTER);
        tag.setStyle("-fx-background-color: #7a4e1d; -fx-background-radius: 6;");
        Label title = new Label(e.title);
        title.setFont(Theme.serifBold(11.5 * s));
        title.setWrapText(true);
        title.setTextAlignment(TextAlignment.CENTER);
        title.setAlignment(Pos.CENTER);
        title.setMaxWidth(w - 10 * s);
        title.setTextFill(Theme.INK);
        Label text = new Label(e.text);
        text.setFont(Theme.serif(8.6 * s));
        text.setWrapText(true);
        text.setTextAlignment(TextAlignment.CENTER);
        text.setMaxWidth(w - 12 * s);
        text.setTextFill(Theme.INK_SOFT);
        Label foot = new Label("Jogue a qualquer momento.\nNão é uma ação.");
        foot.setFont(Theme.serifItalic(7.5 * s));
        foot.setTextAlignment(TextAlignment.CENTER);
        foot.setTextFill(Theme.INK_SOFT);
        VBox box = new VBox(4 * s, tag, title, text, foot);
        box.setAlignment(Pos.TOP_CENTER);
        box.setPadding(new Insets(4 * s));
        box.setPrefSize(w, h);
        box.setStyle("-fx-background-color: linear-gradient(#fbf1d2, #ead39a); -fx-background-radius: 7;"
                + " -fx-border-color: #7a4e1d; -fx-border-width: 2; -fx-border-radius: 7;");
        return box;
    }

    private static VBox epidemicBox(double w, double h, double s) {
        Label t = new Label("EPIDEMIA");
        t.setFont(Theme.serifBold(14 * s));
        t.setTextFill(Color.web("#f3f0d0"));
        Label steps = new Label("1. Aumentar\n2. Infectar\n3. Intensificar");
        steps.setFont(Theme.serif(10 * s));
        steps.setTextFill(Color.web("#f3f0d0"));
        VBox box = new VBox(10 * s, t, steps);
        box.setAlignment(Pos.CENTER);
        box.setPrefSize(w, h);
        box.setStyle("-fx-background-color: linear-gradient(#3e6b35, #1f3d1a); -fx-background-radius: 7;"
                + " -fx-border-color: #11240e; -fx-border-width: 2; -fx-border-radius: 7;");
        return box;
    }
}
