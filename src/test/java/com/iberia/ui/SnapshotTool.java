package com.iberia.ui;

import com.iberia.engine.GameEngine;
import com.iberia.engine.RuleValidator;
import com.iberia.model.ActionType;
import com.iberia.model.Board;
import com.iberia.model.DiseaseType;
import com.iberia.model.EventType;
import com.iberia.model.GameAction;
import com.iberia.model.GameSettings;
import com.iberia.model.GameState;
import com.iberia.model.PendingChoice;
import com.iberia.model.Player;
import com.iberia.model.PlayerCard;
import com.iberia.model.Role;
import com.iberia.network.Json;
import com.iberia.network.Packet;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Dev utility: renders the main screens to PNG files without user interaction.
 * Usage: {@code java ... com.iberia.ui.SnapshotTool <output-dir>}
 */
public class SnapshotTool extends Application {
    private static String outDir = ".";

    /** Entry point that does not extend Application (JavaFX on the classpath). */
    public static final class Run {
        public static void main(String[] args) {
            if (args.length > 0) outDir = args[0];
            Application.launch(SnapshotTool.class, args);
        }
    }

    @Override
    public void start(Stage stage) throws Exception {
        Board b = Board.get();
        GameSettings st = new GameSettings();
        st.seed = 99;
        st.epidemics = 5;
        GameEngine e = GameEngine.newGame(Arrays.asList("Ana", "Bruno", "Carla", "Diego"),
                Arrays.asList(Role.NURSE, Role.RAILWAYMAN, Role.AGRONOMIST, Role.SAILOR), st);
        GameState s = e.state();
        for (Player p : s.players) {
            PendingChoice c = s.choices.stream().filter(x -> x.player == p.id && x.type == PendingChoice.Type.START_CITY).findFirst().orElseThrow();
            e.apply(GameAction.of(ActionType.CHOOSE_START_CITY, p.id).city(c.options.get(0)));
        }
        while (RuleValidator.blockingChoice(s) != null) {
            PendingChoice c = RuleValidator.blockingChoice(s);
            e.apply(GameAction.of(ActionType.CHOOSE, c.player).region(c.options.get(0)));
        }
        // decorate the board to show every kind of piece
        String[][] rails = {{"Madrid", "Toledo"}, {"Madrid", "Zaragoza"}, {"Zaragoza", "Barcelona"}, {"Lisboa", "Porto"}, {"Sevilla", "Córdoba"}};
        for (String[] r : rails) {
            s.rails[b.routeBetween(b.byName(r[0]).id, b.byName(r[1]).id).id] = true;
            s.railSupply--;
        }
        s.hospitals[DiseaseType.RED.ordinal()] = b.byName("Gijón").id;
        s.hospitals[DiseaseType.YELLOW.ordinal()] = b.byName("Cuenca").id;
        s.researched[DiseaseType.YELLOW.ordinal()] = true;
        s.purification[b.regionsOf(b.byName("Valencia").id)[0]] = 2;
        s.purification[b.regionsOf(b.byName("Sevilla").id)[1]] = 3;
        s.purificationSupply -= 5;
        s.cubes[b.byName("Madrid").id][DiseaseType.YELLOW.ordinal()] = 1;
        s.outbreaks = 2;
        s.infectionRateIndex = 2;
        s.currentPlayer = 0;
        s.player(0).hand.add(PlayerCard.ofEvent(EventType.ONE_MORE_DAY));
        s.player(0).hand.add(b.byName("Valencia").id);
        s.player(0).hand.add(b.byName("Madrid").id);
        s.player(1).city = s.player(0).city;
        s.syncCounts();

        List<Packet.Seat> seats = new ArrayList<>();
        for (Player p : s.players) {
            Packet.Seat seat = new Packet.Seat();
            seat.seat = p.id;
            seat.name = p.name;
            seat.ownerId = p.id < 2 ? "me" : "other";
            seat.ownerName = p.id < 2 ? "Ana" : "Carla";
            seat.role = p.role;
            seat.connected = p.id != 3;
            seats.add(seat);
        }
        GameState red = Json.redacted(s);

        GameScreen game = new GameScreen("me", a -> {
        }, t -> {
        }, c -> {
        }, () -> {
        });
        snap(stage, game, "game.png", 1600, 960, () -> game.update(red, seats));

        BoardCanvas board = new BoardCanvas();
        java.util.Set<Integer> regs = new java.util.HashSet<>();
        for (int r : b.regionsOf(b.byName("Madrid").id)) regs.add(r);
        snap(stage, board, "board.png", 1400, 1100, () -> {
            board.setState(red, java.util.Set.of(0, 1));
            board.selectRegion(regs, r -> {
            });
        });

        MainMenuScreen menu = new MainMenuScreen(new MainMenuScreen.Actions() {
            @Override
            public void host(String name, int port) {
            }

            @Override
            public void join(String name, String address, int port) {
            }
        });
        snap(stage, menu, "menu.png", 1400, 900, () -> {
        });

        LobbyScreen lobby = new LobbyScreen("me", 8080, c -> {
        }, t -> {
        }, () -> {
        });
        Packet.LobbyUpdate u = new Packet.LobbyUpdate();
        u.seats = seats;
        u.settings = st;
        u.hostId = "me";
        u.serverName = "Mesa de Ana";
        snap(stage, lobby, "lobby.png", 1400, 900, () -> lobby.update(u));

        Platform.exit();
    }

    private void snap(Stage stage, Parent root, String file, int w, int h, Runnable init) throws Exception {
        Scene scene = new Scene(root, w, h);
        scene.getStylesheets().add(getClass().getResource("/com/iberia/iberia.css").toExternalForm());
        stage.setScene(scene);
        stage.show();
        init.run();
        root.applyCss();
        root.layout();
        if (root instanceof BoardCanvas) ((BoardCanvas) root).redraw();
        WritableImage img = scene.snapshot(null);
        // second layout pass so the canvas has its final size
        if (root instanceof GameScreen || root instanceof BoardCanvas) {
            init.run();
            img = scene.snapshot(null);
        }
        BufferedImage out = new BufferedImage((int) img.getWidth(), (int) img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        PixelReader pr = img.getPixelReader();
        for (int x = 0; x < out.getWidth(); x++) for (int y = 0; y < out.getHeight(); y++) out.setRGB(x, y, pr.getArgb(x, y));
        ImageIO.write(out, "png", new File(outDir, file));
        System.out.println("wrote " + new File(outDir, file).getAbsolutePath());
    }
}
