package com.iberia;

import com.iberia.network.GameServer;
import javafx.application.Application;

/**
 * Plain {@code main} so the shaded jar can start JavaFX from the classpath.
 * <pre>
 *   java -jar pandemic-iberia-1.0.0-all.jar                  (desktop client: host or join)
 *   java -jar pandemic-iberia-1.0.0-all.jar --server [port]  (headless dedicated server)
 * </pre>
 */
public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--server".equals(args[0])) {
            int port = args.length > 1 ? Integer.parseInt(args[1]) : GameServer.DEFAULT_PORT;
            GameServer server = new GameServer(port, "Servidor dedicado", null);
            server.start();
            System.out.println("Servidor Iberia 1848 na porta " + port + ". O primeiro jogador a entrar será o anfitrião. Ctrl+C para sair.");
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
            Thread.currentThread().join();
            return;
        }
        Application.launch(Main.class, args);
    }
}
