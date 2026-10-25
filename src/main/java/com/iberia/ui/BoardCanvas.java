package com.iberia.ui;

import com.iberia.model.Board;
import com.iberia.model.City;
import com.iberia.model.DiseaseType;
import com.iberia.model.GameState;
import com.iberia.model.Player;
import com.iberia.model.Region;
import com.iberia.model.Route;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * Board renderer: engraved 1848 cartography drawn procedurally on a resizable
 * canvas (zoom with the wheel, pan by dragging, double-click to reset), plus
 * the interactive selection modes used by the action flows.
 */
public final class BoardCanvas extends Pane {
    public enum Mode {NONE, CITY, REGION, ROUTE}

    /** Click on a city while no selection is active (used for the quick-move menu). */
    public interface CityClickHandler {
        void onCityClick(int city, double screenX, double screenY);
    }

    private static final Board B = Board.get();
    private static final Map<String, Character> LABEL_POS = new HashMap<>();
    private static final Map<String, String> LABEL_TEXT = new HashMap<>();

    static {
        String[] spec = {
                "A Coruña:L", "Vigo:L", "Porto:L", "Lisboa:L", "Albufeira:B", "Huelva:B", "Cádiz:L", "Málaga:B",
                "Almería:B", "Palma de Mallorca:B", "Tarragona:B", "Andorra la Vella:A", "Zaragoza:Q", "Gijón:A",
                "Santander:A", "San Sebastián-Donostia:A", "Burgos:A", "Soria:B", "Salamanca:B", "Madrid:A",
                "Toledo:L", "Ciudad Real:B", "Cáceres:B", "Badajoz:L", "Évora:B", "Ourense:Q", "Sevilla:A",
                "Córdoba:L", "Cuenca:A"
        };
        for (String s : spec) {
            int i = s.lastIndexOf(':');
            LABEL_POS.put(s.substring(0, i), s.charAt(i + 1));
        }
        LABEL_TEXT.put("Santiago de Compostela", "Santiago de\nCompostela");
        LABEL_TEXT.put("San Sebastián-Donostia", "San Sebastián-\nDonostia");
        LABEL_TEXT.put("Vitoria-Gasteiz", "Vitoria-\nGasteiz");
        LABEL_TEXT.put("Bilbao-Bilbo", "Bilbao-\nBilbo");
        LABEL_TEXT.put("Palma de Mallorca", "Palma de\nMallorca");
        LABEL_TEXT.put("Andorra la Vella", "Andorra\nla Vella");
        LABEL_TEXT.put("Ciudad Real", "Ciudad\nReal");
    }

    private final Canvas canvas = new Canvas();
    private GameState state;
    private Set<Integer> mySeats = Collections.emptySet();

    private Mode mode = Mode.NONE;
    private Set<Integer> allowed = new HashSet<>();
    private Set<Integer> marked = new HashSet<>();
    private IntConsumer onPick;
    private CityClickHandler cityClickHandler;
    private Set<Integer> moveHints = new HashSet<>();

    private int hoverCity = -1;
    private int hoverRegion = -1;
    private int hoverRoute = -1;
    private double mouseX, mouseY;

    private double zoom = 1, panX, panY;
    private double base, marginX, marginY;
    private double scale, offX, offY;
    private double pressX, pressY, pressPanX, pressPanY;
    private boolean dragging;

    private final Timeline pulse;
    private double phase;
    private final List<double[]> hatch = new ArrayList<>();
    private static Image paper;

    public BoardCanvas() {
        getChildren().add(canvas);
        setMinSize(300, 240);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        widthProperty().addListener((o, a, b) -> redraw());
        heightProperty().addListener((o, a, b) -> redraw());
        buildHatch();
        pulse = new Timeline(new KeyFrame(Duration.millis(40), e -> {
            phase += 0.16;
            if (mode != Mode.NONE || !moveHints.isEmpty()) redraw();
        }));
        pulse.setCycleCount(Animation.INDEFINITE);
        pulse.play();
        sceneProperty().addListener((o, old, sc) -> {
            if (sc == null) pulse.stop();
            else pulse.play();
        });

        canvas.setOnMouseMoved(this::onMove);
        canvas.setOnMouseExited(e -> {
            hoverCity = hoverRegion = hoverRoute = -1;
            redraw();
        });
        canvas.setOnMousePressed(e -> {
            pressX = e.getX();
            pressY = e.getY();
            pressPanX = panX;
            pressPanY = panY;
            dragging = false;
        });
        canvas.setOnMouseDragged(e -> {
            if (Math.hypot(e.getX() - pressX, e.getY() - pressY) > 5) dragging = true;
            if (dragging && zoom > 1.0001) {
                panX = pressPanX + (e.getX() - pressX);
                panY = pressPanY + (e.getY() - pressY);
                clampPan();
                redraw();
            }
        });
        canvas.setOnMouseReleased(e -> {
            if (!dragging && e.getButton() == MouseButton.PRIMARY) {
                if (e.getClickCount() == 2 && mode == Mode.NONE && hitCity(e.getX(), e.getY()) < 0) {
                    zoom = 1;
                    panX = panY = 0;
                    redraw();
                } else {
                    onClick(e);
                }
            }
        });
        canvas.setOnScroll(this::onScroll);
    }

    // ================================================================= public API

    public void setState(GameState s, Set<Integer> mySeats) {
        this.state = s;
        this.mySeats = mySeats;
        redraw();
    }

    public void setCityClickHandler(CityClickHandler h) {
        this.cityClickHandler = h;
    }

    public void selectCity(Set<Integer> options, IntConsumer cb) {
        startSelection(Mode.CITY, options, cb);
    }

    public void selectRegion(Set<Integer> options, IntConsumer cb) {
        startSelection(Mode.REGION, options, cb);
    }

    public void selectRoute(Set<Integer> options, IntConsumer cb) {
        startSelection(Mode.ROUTE, options, cb);
    }

    /** Routes or cities already chosen in a multi-step selection (drawn as confirmed). */
    public void setMarked(Set<Integer> marked) {
        this.marked = new HashSet<>(marked);
        redraw();
    }

    /** Subtle highlight of the cities the acting player could move to. */
    public void setMoveHints(Set<Integer> hints) {
        this.moveHints = new HashSet<>(hints);
        redraw();
    }

    public void clearSelection() {
        mode = Mode.NONE;
        allowed = new HashSet<>();
        marked = new HashSet<>();
        onPick = null;
        redraw();
    }

    public Mode mode() {
        return mode;
    }

    private void startSelection(Mode m, Set<Integer> options, IntConsumer cb) {
        mode = m;
        allowed = new HashSet<>(options);
        onPick = cb;
        redraw();
    }

    // ================================================================= transform

    /** Visible board rectangle, widened (within the drawn cartography) to fill the canvas aspect ratio. */
    private double viewX0, viewY0, viewX1, viewY1;

    private void computeTransform() {
        double w = getWidth(), h = getHeight();
        viewX0 = MapGeometry.VIEW_X0;
        viewX1 = MapGeometry.VIEW_X1;
        viewY0 = MapGeometry.VIEW_Y0;
        viewY1 = MapGeometry.VIEW_Y1;
        double aspect = w / h, view = (viewX1 - viewX0) / (viewY1 - viewY0);
        if (aspect > view) {
            double extra = (viewY1 - viewY0) * aspect - (viewX1 - viewX0);
            viewX0 -= extra / 2;
            viewX1 += extra / 2;
            if (viewX1 > MapGeometry.MAX_X) {
                viewX0 -= viewX1 - MapGeometry.MAX_X;
                viewX1 = MapGeometry.MAX_X;
            }
            viewX0 = Math.max(MapGeometry.MIN_X, viewX0);
        } else {
            double extra = (viewX1 - viewX0) / aspect - (viewY1 - viewY0);
            viewY0 = Math.max(MapGeometry.MIN_Y, viewY0 - extra / 2);
            viewY1 = Math.min(MapGeometry.MAX_Y, viewY1 + extra / 2);
        }
        double vw = viewX1 - viewX0, vh = viewY1 - viewY0;
        base = Math.min(w / vw, h / vh);
        marginX = (w - vw * base) / 2;
        marginY = (h - vh * base) / 2;
        scale = base * zoom;
        offX = (marginX - viewX0 * base) * zoom + panX;
        offY = (marginY - viewY0 * base) * zoom + panY;
    }

    private double sx(double bx) {
        return bx * scale + offX;
    }

    private double sy(double by) {
        return by * scale + offY;
    }

    private double bx(double x) {
        return (x - offX) / scale;
    }

    private double by(double y) {
        return (y - offY) / scale;
    }

    /** Size in board units converted to pixels, never smaller than {@code min}. */
    private double px(double boardUnits, double min) {
        return Math.max(min, boardUnits * scale);
    }

    private void onScroll(ScrollEvent e) {
        double factor = e.getDeltaY() > 0 ? 1.15 : 1 / 1.15;
        double nz = Math.max(1, Math.min(4, zoom * factor));
        double px = (e.getX() - panX) / zoom;
        double py = (e.getY() - panY) / zoom;
        panX = e.getX() - px * nz;
        panY = e.getY() - py * nz;
        zoom = nz;
        clampPan();
        redraw();
    }

    private void clampPan() {
        if (zoom <= 1.0001) {
            panX = panY = 0;
            return;
        }
        double w = getWidth(), h = getHeight();
        panX = Math.min(0, Math.max(w - w * zoom, panX));
        panY = Math.min(0, Math.max(h - h * zoom, panY));
    }

    // ================================================================= hit testing

    private int hitCity(double x, double y) {
        int best = -1;
        double bestD = Math.max(13, 11 * scale);
        for (City c : B.cities) {
            double d = Math.hypot(sx(c.x) - x, sy(c.y) - y);
            if (d < bestD) {
                bestD = d;
                best = c.id;
            }
        }
        return best;
    }

    private int hitRegion(double x, double y) {
        double bxv = bx(x), byv = by(y);
        for (Region r : B.regions) if (insideRegion(r, bxv, byv)) return r.id;
        return -1;
    }

    private static boolean insideRegion(Region r, double x, double y) {
        boolean in = false;
        int[] f = r.cities;
        for (int i = 0, j = f.length - 1; i < f.length; j = i++) {
            City a = B.city(f[i]), b = B.city(f[j]);
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) in = !in;
        }
        return in;
    }

    private int hitRoute(double x, double y) {
        int best = -1;
        double bestD = 9;
        for (Route r : B.routes) {
            if (mode == Mode.ROUTE && !allowed.contains(r.id)) continue;
            City a = B.city(r.a), b = B.city(r.b);
            double d = segDist(x, y, sx(a.x), sy(a.y), sx(b.x), sy(b.y));
            if (d < bestD) {
                bestD = d;
                best = r.id;
            }
        }
        return best;
    }

    private static double segDist(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy);
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    private void onMove(MouseEvent e) {
        mouseX = e.getX();
        mouseY = e.getY();
        int hc = hitCity(mouseX, mouseY);
        int hr = mode == Mode.REGION ? hitRegion(mouseX, mouseY) : -1;
        int ht = mode == Mode.ROUTE ? hitRoute(mouseX, mouseY) : -1;
        boolean changed = hc != hoverCity || hr != hoverRegion || ht != hoverRoute;
        hoverCity = hc;
        hoverRegion = hr;
        hoverRoute = ht;
        boolean clickable = (mode == Mode.CITY && allowed.contains(hc)) || (mode == Mode.REGION && allowed.contains(hr))
                || (mode == Mode.ROUTE && allowed.contains(ht)) || (mode == Mode.NONE && hc >= 0);
        canvas.setCursor(clickable ? javafx.scene.Cursor.HAND : javafx.scene.Cursor.DEFAULT);
        if (changed || hc >= 0) redraw();
    }

    private void onClick(MouseEvent e) {
        double x = e.getX(), y = e.getY();
        switch (mode) {
            case CITY: {
                int c = hitCity(x, y);
                if (allowed.contains(c)) pick(c);
                break;
            }
            case REGION: {
                int r = hitRegion(x, y);
                if (allowed.contains(r)) pick(r);
                break;
            }
            case ROUTE: {
                int r = hitRoute(x, y);
                if (allowed.contains(r)) pick(r);
                break;
            }
            default: {
                int c = hitCity(x, y);
                if (c >= 0 && cityClickHandler != null) cityClickHandler.onCityClick(c, e.getScreenX(), e.getScreenY());
            }
        }
    }

    private void pick(int id) {
        IntConsumer cb = onPick;
        mode = Mode.NONE;
        allowed = new HashSet<>();
        onPick = null;
        hoverRegion = hoverRoute = -1;
        if (cb != null) cb.accept(id);
        redraw();
    }

    // ================================================================= drawing

    public void redraw() {
        double w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        computeTransform();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineJoin(StrokeLineJoin.ROUND);
        g.setTextBaseline(VPos.BASELINE);

        g.setFill(Theme.PARCHMENT_DARK);
        g.fillRect(0, 0, w, h);

        // No outer clip(): on large canvases JavaFX renders clipped fills semi-transparently,
        // so the map is drawn freely and the margins are painted over it afterwards.
        double mx0 = sx(viewX0), my0 = sy(viewY0);
        double mx1 = sx(viewX1), my1 = sy(viewY1);
        drawSea(g, mx0, my0, mx1, my1);
        drawLand(g, mx0, my0, mx1, my1);
        drawDecorations(g);
        if (state != null) {
            drawRegionHighlights(g);
            drawRoutes(g);
            drawRegionTokens(g);
            drawCities(g);
            drawPieces(g);
        } else {
            drawRoutes(g);
            drawCities(g);
        }
        drawMargins(g, w, h, mx0, my0, mx1, my1);
        drawFrame(g, mx0, my0, mx1, my1);
        if (hoverCity >= 0 && state != null) drawTooltip(g, hoverCity, w, h);
    }

    private void drawPaper(GraphicsContext g, double w, double h, double alpha) {
        paperRect(g, 0, 0, w, h, alpha);
    }

    /** Tiles the paper grain inside a rectangle without using clip(). */
    private void paperRect(GraphicsContext g, double x0, double y0, double rw, double rh, double alpha) {
        if (rw <= 0 || rh <= 0) return;
        if (paper == null) paper = makePaper();
        double tw = paper.getWidth(), th = paper.getHeight();
        g.setGlobalAlpha(alpha);
        for (double x = Math.floor(x0 / tw) * tw; x < x0 + rw; x += tw) {
            for (double y = Math.floor(y0 / th) * th; y < y0 + rh; y += th) {
                double ix = Math.max(x, x0), iy = Math.max(y, y0);
                double iw = Math.min(x + tw, x0 + rw) - ix, ih = Math.min(y + th, y0 + rh) - iy;
                if (iw > 0 && ih > 0) g.drawImage(paper, ix - x, iy - y, iw, ih, ix, iy, iw, ih);
            }
        }
        g.setGlobalAlpha(1);
    }

    private void drawMargins(GraphicsContext g, double w, double h, double x0, double y0, double x1, double y1) {
        double[][] rects = {
                {0, 0, w, y0}, {0, y1, w, h - y1},
                {0, Math.max(0, y0), x0, Math.min(h, y1) - Math.max(0, y0)},
                {x1, Math.max(0, y0), w - x1, Math.min(h, y1) - Math.max(0, y0)}};
        for (double[] r : rects) {
            if (r[2] <= 0 || r[3] <= 0) continue;
            g.setFill(Theme.PARCHMENT_DARK);
            g.fillRect(r[0], r[1], r[2], r[3]);
            paperRect(g, r[0], r[1], r[2], r[3], 0.5);
        }
    }

    private static Image makePaper() {
        int n = 256;
        WritableImage img = new WritableImage(n, n);
        PixelWriter pw = img.getPixelWriter();
        Random r = new Random(1848);
        for (int x = 0; x < n; x++) {
            for (int y = 0; y < n; y++) {
                double v = r.nextDouble();
                double fiber = Math.sin((x * 0.21 + y * 0.03) + r.nextDouble() * 0.6) * 0.5 + 0.5;
                double a = 0.05 + v * 0.07 + fiber * 0.03;
                pw.setColor(x, y, Color.color(0.45, 0.32, 0.16, Math.min(1, a)));
            }
        }
        return img;
    }

    private void buildHatch() {
        for (double y = MapGeometry.MIN_Y; y <= MapGeometry.MAX_Y; y += 6.5) {
            List<Double> pts = new ArrayList<>();
            for (double x = MapGeometry.MIN_X; x <= MapGeometry.MAX_X + 10; x += 9) {
                pts.add(x);
                pts.add(y + Math.sin(x * 0.045 + y * 0.31) * 1.3);
            }
            double[] arr = new double[pts.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = pts.get(i);
            hatch.add(arr);
        }
    }

    private void tracePolygon(GraphicsContext g, double[] p) {
        g.moveTo(sx(p[0]), sy(p[1]));
        for (int i = 2; i < p.length; i += 2) g.lineTo(sx(p[i]), sy(p[i + 1]));
        g.closePath();
    }

    private void traceLand(GraphicsContext g) {
        for (double[] p : MapGeometry.coast()) tracePolygon(g, p);
    }

    private void drawSea(GraphicsContext g, double x0, double y0, double x1, double y1) {
        g.setFill(new LinearGradient(0, y0, 0, y1, false, CycleMethod.NO_CYCLE,
                new Stop(0, Theme.SEA.brighter()), new Stop(1, Theme.SEA)));
        g.fillRect(x0, y0, x1 - x0, y1 - y0);
        drawPaper(g, getWidth(), getHeight(), 0.35);
        g.setStroke(Theme.SEA_DEEP.deriveColor(0, 1, 0.9, 0.45));
        g.setLineWidth(Math.max(0.5, 0.55 * scale));
        double vx0 = bx(Math.max(x0, 0)), vx1 = bx(Math.min(x1, getWidth()));
        double vy0 = by(Math.max(y0, 0)), vy1 = by(Math.min(y1, getHeight()));
        int i0 = Math.max(0, (int) ((vx0 - MapGeometry.MIN_X) / 9) - 1) * 2;
        for (double[] line : hatch) {
            if (line[1] < vy0 - 4 || line[1] > vy1 + 4) continue;
            int i1 = Math.min(line.length, ((int) ((vx1 - MapGeometry.MIN_X) / 9) + 3) * 2);
            if (i1 - i0 < 4) continue;
            g.beginPath();
            g.moveTo(sx(line[i0]), sy(line[i0 + 1]));
            for (int i = i0 + 2; i < i1; i += 2) g.lineTo(sx(line[i]), sy(line[i + 1]));
            g.stroke();
        }
        // engraved ripples following the coast (clipped to the sea)
        g.save();
        g.beginPath();
        g.rect(x0, y0, x1 - x0, y1 - y0);
        traceLand(g);
        g.setFillRule(FillRule.EVEN_ODD);
        g.clip();
        double[] widths = {26, 17, 10, 4.5};
        double[] alphas = {0.10, 0.14, 0.2, 0.35};
        for (int k = 0; k < widths.length; k++) {
            g.setStroke(Theme.SEA_DEEP.deriveColor(0, 1, 0.75, alphas[k]));
            g.setLineWidth(widths[k] * scale);
            g.beginPath();
            traceLand(g);
            g.stroke();
        }
        g.restore();
        g.setFillRule(FillRule.NON_ZERO);
    }

    private void drawLand(GraphicsContext g, double x0, double y0, double x1, double y1) {
        g.beginPath();
        traceLand(g);
        g.setFill(new RadialGradient(0, 0, sx(460), sy(420), 520 * scale, false, CycleMethod.NO_CYCLE,
                new Stop(0, Theme.LAND.brighter()), new Stop(0.75, Theme.LAND), new Stop(1, Theme.LAND_SHADE)));
        g.fill();

        g.save();
        g.beginPath();
        traceLand(g);
        g.clip();
        // Portugal tint
        g.setFill(Theme.PORTUGAL.deriveColor(0, 1, 1, 0.55));
        g.beginPath();
        tracePolygon(g, MapGeometry.PORTUGAL_AREA);
        g.fill();
        drawPaper(g, getWidth(), getHeight(), 0.55);
        // inner coastal shading
        for (int k = 0; k < 3; k++) {
            g.setStroke(Theme.LAND_SHADE.deriveColor(0, 1, 0.95, 0.22 - k * 0.06));
            g.setLineWidth((14 - k * 4) * scale);
            g.beginPath();
            traceLand(g);
            g.stroke();
        }
        // rivers
        g.setStroke(Color.web("#6f8f9a", 0.7));
        g.setLineWidth(px(1.1, 0.8));
        for (double[] r : MapGeometry.RIVERS) {
            g.beginPath();
            g.moveTo(sx(r[0]), sy(r[1]));
            for (int i = 2; i + 3 < r.length; i += 2) {
                double mxv = (r[i] + r[i + 2]) / 2, myv = (r[i + 1] + r[i + 3]) / 2;
                g.quadraticCurveTo(sx(r[i]), sy(r[i + 1]), sx(mxv), sy(myv));
            }
            g.lineTo(sx(r[r.length - 2]), sy(r[r.length - 1]));
            g.stroke();
        }
        drawMountains(g);
        g.restore();

        // border with Portugal
        g.setStroke(Color.web("#8a4b2a", 0.75));
        g.setLineWidth(px(1.4, 1));
        g.setLineDashes(px(5, 3), px(3, 2));
        g.beginPath();
        double[] pb = MapGeometry.PORTUGAL_BORDER;
        g.moveTo(sx(pb[0]), sy(pb[1]));
        for (int i = 2; i < pb.length; i += 2) g.lineTo(sx(pb[i]), sy(pb[i + 1]));
        g.stroke();
        g.setLineDashes(null);

        // coastline
        g.setStroke(Color.web("#5a4027"));
        g.setLineWidth(px(1.5, 1));
        g.beginPath();
        traceLand(g);
        g.stroke();
    }

    private void drawMountains(GraphicsContext g) {
        Random r = new Random(7);
        for (double[] m : MapGeometry.MOUNTAINS) {
            int n = (int) m[3];
            for (int i = 0; i < n; i++) {
                double cx = m[0] - m[2] / 2 + (i + 0.5) * m[2] / n + (r.nextDouble() - 0.5) * 6;
                double cy = m[1] + (r.nextDouble() - 0.5) * 10;
                double hgt = 7 + r.nextDouble() * 5, wid = 7 + r.nextDouble() * 4;
                double x = sx(cx), y = sy(cy), hh = hgt * scale, ww = wid * scale;
                g.setFill(Color.web("#c9ad7a", 0.55));
                g.fillPolygon(new double[]{x - ww, x, x + ww}, new double[]{y, y - hh, y}, 3);
                g.setStroke(Color.web("#6d5032", 0.75));
                g.setLineWidth(px(0.8, 0.6));
                g.strokePolyline(new double[]{x - ww, x, x + ww}, new double[]{y, y - hh, y}, 3);
                // hatching on the shaded side
                g.setLineWidth(px(0.5, 0.4));
                for (int k = 1; k <= 3; k++) {
                    double t = k / 4.0;
                    g.strokeLine(x + ww * t * 0.9, y - hh * (1 - t) * 0.95, x + ww * t * 0.5, y);
                }
            }
        }
    }

    private void drawDecorations(GraphicsContext g) {
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        for (Object[] l : MapGeometry.LABELS) {
            String text = (String) l[0];
            double x = sx((Double) l[1]), y = sy((Double) l[2]), size = (Double) l[3], rot = (Double) l[4];
            g.save();
            g.translate(x, y);
            g.rotate(rot);
            g.setFont(Theme.serifItalic(Math.max(8, size * scale)));
            g.setFill(Color.web("#5b4630", text.equals("ESPAÑA") ? 0.22 : 0.5));
            g.fillText(text, 0, 0);
            g.restore();
        }
        drawCompass(g, sx(706), sy(612), 32 * scale);
        drawCartouche(g, sx(812), sy(714));
        g.setTextBaseline(VPos.BASELINE);
        g.setTextAlign(TextAlignment.LEFT);
    }

    private void drawCompass(GraphicsContext g, double x, double y, double r) {
        g.setStroke(Color.web("#5a4027", 0.8));
        g.setLineWidth(Math.max(0.8, r / 30));
        g.strokeOval(x - r, y - r, 2 * r, 2 * r);
        g.strokeOval(x - r * 0.8, y - r * 0.8, 1.6 * r, 1.6 * r);
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8;
            double len = i % 4 == 0 ? r * 1.05 : i % 2 == 0 ? r * 0.7 : r * 0.45;
            double wv = r * (i % 4 == 0 ? 0.16 : 0.1);
            double ax = Math.cos(a), ay = Math.sin(a);
            double[] xs = {x + ax * len, x - ay * wv, x + ay * wv};
            double[] ys = {y + ay * len, y + ax * wv, y - ax * wv};
            g.setFill(i % 2 == 0 ? Color.web("#5a4027", 0.85) : Color.web("#b08a52", 0.85));
            g.fillPolygon(xs, ys, 3);
        }
        g.setFill(Color.web("#5a4027"));
        g.setFont(Theme.serifBold(Math.max(9, r * 0.45)));
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText("N", x, y - r * 1.25);
    }

    private void drawCartouche(GraphicsContext g, double x, double y) {
        double w = 205 * scale, h = 46 * scale;
        g.setFill(Color.web("#f3e7cb", 0.85));
        g.fillRoundRect(x - w / 2, y - h / 2, w, h, 10 * scale, 10 * scale);
        g.setStroke(Color.web("#5a4027"));
        g.setLineWidth(px(1.4, 1));
        g.strokeRoundRect(x - w / 2, y - h / 2, w, h, 10 * scale, 10 * scale);
        g.setLineWidth(px(0.6, 0.5));
        g.strokeRoundRect(x - w / 2 + 3 * scale, y - h / 2 + 3 * scale, w - 6 * scale, h - 6 * scale, 8 * scale, 8 * scale);
        g.setFill(Color.web("#4a3220"));
        g.setTextAlign(TextAlignment.CENTER);
        g.setFont(Theme.serifBold(Math.max(10, 19 * scale)));
        g.fillText("IBERIA  ·  1848", x, y - 3 * scale);
        g.setFont(Theme.serifItalic(Math.max(7, 8.5 * scale)));
        g.fillText("Mapa de la Real Expedición Filantrópica", x, y + 12 * scale);
    }

    private void drawFrame(GraphicsContext g, double x0, double y0, double x1, double y1) {
        g.setStroke(Color.web("#4a3220"));
        g.setLineWidth(2.2);
        g.strokeRect(x0, y0, x1 - x0, y1 - y0);
        g.setLineWidth(0.8);
        g.strokeRect(x0 - 4, y0 - 4, x1 - x0 + 8, y1 - y0 + 8);
    }

    // ---------------------------------------------------------------- game layers

    private double[] regionPoly(Region r, boolean xs) {
        double[] out = new double[r.cities.length];
        for (int i = 0; i < out.length; i++) {
            City c = B.city(r.cities[i]);
            out[i] = xs ? sx(c.x) : sy(c.y);
        }
        return out;
    }

    private void drawRegionHighlights(GraphicsContext g) {
        if (mode == Mode.REGION) {
            double a = 0.16 + 0.07 * Math.sin(phase);
            for (int id : allowed) {
                Region r = B.region(id);
                g.setFill(Theme.HIGHLIGHT.deriveColor(0, 1, 1, id == hoverRegion ? 0.42 : a));
                g.fillPolygon(regionPoly(r, true), regionPoly(r, false), r.cities.length);
            }
        }
        if (state.nurseRegion >= 0) {
            Region r = B.region(state.nurseRegion);
            g.setFill(Color.web("#e06aa8", 0.10));
            g.fillPolygon(regionPoly(r, true), regionPoly(r, false), r.cities.length);
        }
        for (Region r : B.regions) {
            if (state.purification[r.id] <= 0) continue;
            g.setFill(Theme.WATER.deriveColor(0, 1, 1, 0.07));
            g.fillPolygon(regionPoly(r, true), regionPoly(r, false), r.cities.length);
        }
    }

    private void drawRoutes(GraphicsContext g) {
        for (Route r : B.routes) {
            City a = B.city(r.a), b = B.city(r.b);
            double x1 = sx(a.x), y1 = sy(a.y), x2 = sx(b.x), y2 = sy(b.y);
            boolean railed = state != null && state.rails[r.id];
            boolean selectable = mode == Mode.ROUTE && allowed.contains(r.id);
            if (selectable || marked.contains(r.id) && mode == Mode.ROUTE) {
                double pw = r.id == hoverRoute ? 9 : 6 + Math.sin(phase) * 1.5;
                g.setStroke(Theme.HIGHLIGHT.deriveColor(0, 1, 1, r.id == hoverRoute ? 0.9 : 0.55));
                g.setLineWidth(px(pw, pw * 0.8));
                g.strokeLine(x1, y1, x2, y2);
            }
            if (marked.contains(r.id)) {
                g.setStroke(Color.web("#2f7d32", 0.8));
                g.setLineWidth(px(6, 5));
                g.strokeLine(x1, y1, x2, y2);
            }
            g.setStroke(Theme.ROUTE);
            g.setLineWidth(px(1.6, 1.2));
            if (r.dashed) g.setLineDashes(px(6, 4), px(4, 3));
            g.strokeLine(x1, y1, x2, y2);
            g.setLineDashes(null);
            if (railed) drawRail(g, x1, y1, x2, y2);
        }
    }

    private void drawRail(GraphicsContext g, double x1, double y1, double x2, double y2) {
        double len = Math.hypot(x2 - x1, y2 - y1);
        double ux = (x2 - x1) / len, uy = (y2 - y1) / len;
        double nx = -uy, ny = ux;
        double half = px(2.6, 2.2);
        double start = px(9, 7), end = len - start;
        g.setStroke(Color.web("#6b4a2b"));
        g.setLineWidth(px(1.6, 1.3));
        for (double t = start; t <= end; t += px(5.5, 4.5)) {
            double cx = x1 + ux * t, cy = y1 + uy * t;
            g.strokeLine(cx + nx * half * 1.6, cy + ny * half * 1.6, cx - nx * half * 1.6, cy - ny * half * 1.6);
        }
        g.setStroke(Theme.RAIL);
        g.setLineWidth(px(1.2, 1));
        for (int s = -1; s <= 1; s += 2) {
            g.strokeLine(x1 + ux * start + nx * half * s, y1 + uy * start + ny * half * s,
                    x1 + ux * end + nx * half * s, y1 + uy * end + ny * half * s);
        }
    }

    private void drawRegionTokens(GraphicsContext g) {
        for (Region r : B.regions) {
            double x = sx(r.cx), y = sy(r.cy);
            int n = state.purification[r.id];
            boolean nurse = state.nurseRegion == r.id;
            if (mode == Mode.REGION && allowed.contains(r.id)) {
                double rad = px(9, 7) + Math.sin(phase) * 1.5;
                g.setStroke(Theme.HIGHLIGHT.darker());
                g.setLineWidth(2);
                g.strokeOval(x - rad, y - rad, rad * 2, rad * 2);
            }
            if (n > 0) {
                double dx = nurse ? -px(8, 7) : 0;
                drawDroplet(g, x + dx, y, px(8.5, 7));
                g.setFont(Theme.serifBold(Math.max(9, 9.5 * scale)));
                g.setTextAlign(TextAlignment.CENTER);
                outlinedText(g, "×" + n, x + dx + px(10, 9), y + px(9, 8), Color.web("#14466e"));
            }
            if (nurse) {
                double rr = px(7, 6), cx = x + (n > 0 ? px(9, 8) : 0);
                g.setFill(Color.web("#c62828"));
                g.fillOval(cx - rr, y - rr, rr * 2, rr * 2);
                g.setStroke(Color.WHITE);
                g.setLineWidth(px(1.2, 1));
                g.strokeOval(cx - rr * 0.75, y - rr * 0.75, rr * 1.5, rr * 1.5);
                g.setStroke(Color.WHITE);
                g.setLineWidth(px(2, 1.6));
                g.strokeLine(cx - rr * 0.45, y, cx + rr * 0.45, y);
                g.strokeLine(cx, y - rr * 0.45, cx, y + rr * 0.45);
            }
        }
        g.setTextAlign(TextAlignment.LEFT);
    }

    private void drawDroplet(GraphicsContext g, double x, double y, double s) {
        g.setFill(new LinearGradient(0, y - s, 0, y + s, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#8fd0f5")), new Stop(1, Color.web("#2a6fa8"))));
        g.beginPath();
        g.moveTo(x, y - s);
        g.bezierCurveTo(x + s * 0.2, y - s * 0.5, x + s * 0.75, y - s * 0.05, x + s * 0.75, y + s * 0.35);
        g.bezierCurveTo(x + s * 0.75, y + s * 0.85, x - s * 0.75, y + s * 0.85, x - s * 0.75, y + s * 0.35);
        g.bezierCurveTo(x - s * 0.75, y - s * 0.05, x - s * 0.2, y - s * 0.5, x, y - s);
        g.closePath();
        g.fill();
        g.setStroke(Color.web("#123b5c"));
        g.setLineWidth(1);
        g.stroke();
        g.setFill(Color.web("#ffffff", 0.7));
        g.fillOval(x - s * 0.35, y, s * 0.25, s * 0.35);
    }

    private void drawCities(GraphicsContext g) {
        double r = px(7.5, 6);
        for (City c : B.cities) {
            double x = sx(c.x), y = sy(c.y);
            boolean selectable = mode == Mode.CITY && allowed.contains(c.id);
            if (selectable) {
                double rad = r + px(5, 4) + Math.sin(phase) * 2;
                g.setFill(Theme.HIGHLIGHT.deriveColor(0, 1, 1, c.id == hoverCity ? 0.75 : 0.4));
                g.fillOval(x - rad, y - rad, rad * 2, rad * 2);
            } else if (mode == Mode.NONE && moveHints.contains(c.id)) {
                double rad = r + px(3.5, 3);
                g.setStroke(Theme.HIGHLIGHT.deriveColor(0, 1, 0.85, 0.55 + 0.25 * Math.sin(phase)));
                g.setLineWidth(px(2, 1.6));
                g.strokeOval(x - rad, y - rad, rad * 2, rad * 2);
            }
            if (marked.contains(c.id) && mode == Mode.CITY) {
                double rad = r + px(5, 4);
                g.setStroke(Color.web("#2f7d32"));
                g.setLineWidth(2.5);
                g.strokeOval(x - rad, y - rad, rad * 2, rad * 2);
            }
            if (state != null && state.lastInfectedCity == c.id) {
                double rad = r + px(4, 3);
                g.setStroke(Color.web("#b71c1c", 0.85));
                g.setLineWidth(px(1.8, 1.5));
                g.setLineDashes(3, 3);
                g.strokeOval(x - rad, y - rad, rad * 2, rad * 2);
                g.setLineDashes(null);
            }
            Color col = Theme.color(c.color);
            g.setFill(Color.web("#000000", 0.25));
            g.fillOval(x - r + 1.2, y - r + 1.8, r * 2, r * 2);
            g.setFill(new RadialGradient(0, 0, x - r * 0.35, y - r * 0.35, r * 1.3, false, CycleMethod.NO_CYCLE,
                    new Stop(0, col.interpolate(Color.WHITE, 0.3)), new Stop(0.6, col), new Stop(1, col.darker())));
            g.fillOval(x - r, y - r, r * 2, r * 2);
            g.setStroke(Color.web("#f4ead2"));
            g.setLineWidth(px(1.4, 1.1));
            g.strokeOval(x - r * 0.72, y - r * 0.72, r * 1.44, r * 1.44);
            g.setStroke(Color.web("#2a1d12"));
            g.setLineWidth(px(1.1, 0.9));
            g.strokeOval(x - r, y - r, r * 2, r * 2);
            if (c.id == hoverCity) {
                g.setStroke(Theme.HIGHLIGHT);
                g.setLineWidth(2);
                g.strokeOval(x - r - 2, y - r - 2, r * 2 + 4, r * 2 + 4);
            }
            if (c.port) drawAnchor(g, x - r * 1.05, y + r * 1.05, r * 0.85);
            drawLabel(g, c, x, y, r);
        }
    }

    private void drawLabel(GraphicsContext g, City c, double x, double y, double r) {
        char pos = LABEL_POS.getOrDefault(c.name, 'R');
        String text = LABEL_TEXT.getOrDefault(c.name, c.name);
        double size = Math.max(8.5, 8.6 * scale);
        g.setFont(Theme.serifBold(size));
        int lines = text.split("\n").length;
        double lineH = size * 1.05;
        double tx, ty;
        switch (pos) {
            case 'L':
                g.setTextAlign(TextAlignment.RIGHT);
                tx = x - r - 3;
                ty = y + size * 0.35 - (lines - 1) * lineH / 2;
                break;
            case 'A':
                g.setTextAlign(TextAlignment.CENTER);
                tx = x;
                ty = y - r - 4 - (lines - 1) * lineH;
                break;
            case 'B':
                g.setTextAlign(TextAlignment.CENTER);
                tx = x;
                ty = y + r + size;
                break;
            case 'Q':
                g.setTextAlign(TextAlignment.LEFT);
                tx = x + r * 0.6;
                ty = y + r + size * 0.9;
                break;
            default:
                g.setTextAlign(TextAlignment.LEFT);
                tx = x + r + 3;
                ty = y + size * 0.35 - (lines - 1) * lineH / 2;
        }
        outlinedText(g, text, tx, ty, Color.web("#2e1f12"));
        g.setTextAlign(TextAlignment.LEFT);
    }

    /** Small engraved anchor marking a port city. */
    private void drawAnchor(GraphicsContext g, double x, double y, double s) {
        g.setFill(Color.web("#f6eed9", 0.9));
        g.fillOval(x - s * 0.95, y - s * 0.95, s * 1.9, s * 1.9);
        g.setStroke(Color.web("#2a3f5c"));
        g.setLineWidth(Math.max(1, s * 0.16));
        g.strokeOval(x - s * 0.18, y - s * 0.78, s * 0.36, s * 0.36);
        g.strokeLine(x, y - s * 0.42, x, y + s * 0.7);
        g.strokeLine(x - s * 0.35, y - s * 0.22, x + s * 0.35, y - s * 0.22);
        g.strokeArc(x - s * 0.6, y - s * 0.1, s * 1.2, s * 0.85, 190, 160, javafx.scene.shape.ArcType.OPEN);
    }

    private void outlinedText(GraphicsContext g, String text, double x, double y, Color fill) {
        g.setStroke(Color.web("#f6eed9", 0.9));
        g.setLineWidth(3);
        g.strokeText(text, x, y);
        g.setFill(fill);
        g.fillText(text, x, y);
    }

    private void drawPieces(GraphicsContext g) {
        double r = px(7.5, 6);
        double cs = px(6.2, 5.2);
        // hospitals
        for (DiseaseType d : DiseaseType.values()) {
            int city = state.hospitals[d.ordinal()];
            if (city < 0) continue;
            City c = B.city(city);
            char pos = LABEL_POS.getOrDefault(c.name, 'R');
            boolean left = pos != 'L' && pos != 'A';
            drawHospital(g, sx(c.x) + (left ? -r * 1.9 : r * 1.9), sy(c.y) - r * 1.3, px(8, 7), Theme.color(d));
        }
        // cubes
        for (City c : B.cities) {
            int[] cubes = state.cubes[c.id];
            int rows = 0;
            for (int v : cubes) if (v > 0) rows++;
            if (rows == 0) continue;
            boolean below = LABEL_POS.getOrDefault(c.name, 'R') == 'A';
            double x = sx(c.x), y = sy(c.y);
            int row = 0;
            for (DiseaseType d : DiseaseType.values()) {
                int n = cubes[d.ordinal()];
                if (n <= 0) continue;
                double rowY = below ? y + r + 3 + row * (cs + 1.5) : y - r - cs - 3 - row * (cs + 1.5);
                double startX = x - (n * (cs + 1.5)) / 2;
                for (int i = 0; i < n; i++) drawCube(g, startX + i * (cs + 1.5), rowY, cs, Theme.color(d));
                row++;
            }
        }
        // pawns
        Map<Integer, List<Player>> byCity = new HashMap<>();
        for (Player p : state.players) if (p.city >= 0) byCity.computeIfAbsent(p.city, k -> new ArrayList<>()).add(p);
        for (Map.Entry<Integer, List<Player>> e : byCity.entrySet()) {
            City c = B.city(e.getKey());
            boolean right = LABEL_POS.getOrDefault(c.name, 'R') == 'L';
            List<Player> ps = e.getValue();
            for (int i = 0; i < ps.size(); i++) {
                Player p = ps.get(i);
                double step = px(8.5, 7.5);
                double x = right ? sx(c.x) + r + px(5, 4) + i * step : sx(c.x) - r - px(5, 4) - i * step;
                double y = sy(c.y) + px(4, 3);
                boolean current = state.currentPlayer == p.id && state.phase != com.iberia.model.GamePhase.SETUP;
                drawPawn(g, x, y, px(6.5, 5.5), Theme.pawn(p.role), current, mySeats.contains(p.id));
            }
        }
    }

    private void drawCube(GraphicsContext g, double x, double y, double s, Color col) {
        double d = s * 0.32;
        g.setFill(col.deriveColor(0, 1, 0.55, 1));
        g.fillPolygon(new double[]{x + s, x + s + d, x + s + d, x + s}, new double[]{y, y - d, y + s - d, y + s}, 4);
        g.setFill(col.deriveColor(0, 1, 1.25, 1));
        g.fillPolygon(new double[]{x, x + d, x + s + d, x + s}, new double[]{y, y - d, y - d, y}, 4);
        g.setFill(col);
        g.fillRect(x, y, s, s);
        g.setStroke(Color.web("#1a120b", 0.85));
        g.setLineWidth(0.7);
        g.strokeRect(x, y, s, s);
        g.strokePolyline(new double[]{x, x + d, x + s + d, x + s + d, x + s}, new double[]{y, y - d, y - d, y + s - d, y + s}, 5);
        g.strokeLine(x + s, y, x + s + d, y - d);
    }

    private void drawHospital(GraphicsContext g, double x, double y, double s, Color col) {
        double w = s * 1.5, h = s * 1.05, roof = s * 0.75;
        double x0 = x - w / 2, y0 = y - h / 2 + roof / 2;
        g.setFill(Color.web("#000000", 0.25));
        g.fillRect(x0 + 2, y0 + 2, w, h);
        g.setFill(col.deriveColor(0, 1, 0.7, 1));
        g.fillPolygon(new double[]{x0 - 1, x, x0 + w + 1}, new double[]{y0, y0 - roof, y0}, 3);
        g.setFill(col);
        g.fillRect(x0, y0, w, h);
        g.setStroke(Color.web("#1a120b"));
        g.setLineWidth(0.9);
        g.strokeRect(x0, y0, w, h);
        g.strokePolygon(new double[]{x0 - 1, x, x0 + w + 1}, new double[]{y0, y0 - roof, y0}, 3);
        Color crossCol = col.getBrightness() > 0.7 && col.getSaturation() > 0.5 ? Color.web("#b3261e") : Color.WHITE;
        g.setFill(crossCol);
        double cw = s * 0.22, cl = s * 0.7, cx = x, cy = y0 + h / 2;
        g.fillRect(cx - cw / 2, cy - cl / 2, cw, cl);
        g.fillRect(cx - cl / 2, cy - cw / 2, cl, cw);
    }

    private void drawPawn(GraphicsContext g, double x, double y, double s, Color col, boolean current, boolean mine) {
        if (current) {
            g.setFill(Theme.HIGHLIGHT.deriveColor(0, 1, 1, 0.55 + 0.2 * Math.sin(phase)));
            g.fillOval(x - s * 1.3, y - s * 1.6, s * 2.6, s * 3.2);
        }
        g.setFill(Color.web("#000000", 0.3));
        g.fillOval(x - s * 0.9 + 1.5, y + s * 0.9, s * 1.8 + 1, s * 0.6);
        // body
        g.setFill(new LinearGradient(x - s, 0, x + s, 0, false, CycleMethod.NO_CYCLE,
                new Stop(0, col.interpolate(Color.WHITE, 0.35)), new Stop(1, col.deriveColor(0, 1, 0.75, 1))));
        g.beginPath();
        g.moveTo(x - s * 0.85, y + s * 1.1);
        g.quadraticCurveTo(x - s * 0.6, y - s * 0.1, x - s * 0.25, y - s * 0.35);
        g.lineTo(x + s * 0.25, y - s * 0.35);
        g.quadraticCurveTo(x + s * 0.6, y - s * 0.1, x + s * 0.85, y + s * 1.1);
        g.closePath();
        g.fill();
        g.setStroke(Color.web("#1a120b"));
        g.setLineWidth(mine ? 1.6 : 1);
        g.stroke();
        // head
        g.fillOval(x - s * 0.5, y - s * 1.25, s, s);
        g.strokeOval(x - s * 0.5, y - s * 1.25, s, s);
    }

    private void drawTooltip(GraphicsContext g, int cityId, double w, double h) {
        City c = B.city(cityId);
        List<String> lines = new ArrayList<>();
        lines.add(c.name + (c.port ? "  · cidade portuária" : ""));
        lines.add("Cidade " + c.color.colorName + " — " + c.color.diseaseName);
        lines.add("Fundada em " + c.foundedLabel());
        StringBuilder cubes = new StringBuilder();
        for (DiseaseType d : DiseaseType.values()) {
            int n = state.cubesAt(cityId, d);
            if (n > 0) cubes.append(cubes.length() > 0 ? ", " : "").append(n).append(' ').append(d.colorName);
        }
        lines.add("Cubos: " + (cubes.length() == 0 ? "nenhum" : cubes));
        for (DiseaseType d : DiseaseType.values()) {
            if (state.hospitals[d.ordinal()] == cityId) lines.add("Hospital " + d.colorName + (state.researched[d.ordinal()] ? " (pesquisada)" : ""));
        }
        StringBuilder pawns = new StringBuilder();
        for (Player p : state.players) {
            if (p.city == cityId) pawns.append(pawns.length() > 0 ? ", " : "").append(p.name).append(" (").append(p.role.title).append(')');
        }
        if (pawns.length() > 0) lines.add("Peões: " + pawns);
        Font f = Theme.serif(12);
        Font fb = Theme.serifBold(13);
        double lh = 16, tw = 0;
        for (String l : lines) tw = Math.max(tw, l.length() * 6.4);
        double bw = Math.min(330, tw + 20), bh = lines.size() * lh + 12;
        double x = mouseX + 16, y = mouseY + 16;
        if (x + bw > w - 4) x = mouseX - bw - 12;
        if (y + bh > h - 4) y = mouseY - bh - 12;
        g.setFill(Color.web("#000000", 0.25));
        g.fillRoundRect(x + 3, y + 3, bw, bh, 8, 8);
        g.setFill(Color.web("#f7efdc", 0.97));
        g.fillRoundRect(x, y, bw, bh, 8, 8);
        g.setStroke(Theme.color(c.color));
        g.setLineWidth(2);
        g.strokeRoundRect(x, y, bw, bh, 8, 8);
        g.setTextAlign(TextAlignment.LEFT);
        for (int i = 0; i < lines.size(); i++) {
            g.setFont(i == 0 ? fb : f);
            g.setFill(Theme.INK);
            g.fillText(lines.get(i), x + 10, y + 18 + i * lh);
        }
    }
}
