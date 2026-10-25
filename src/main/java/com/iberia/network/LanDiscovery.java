package com.iberia.network;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.function.Supplier;

/** UDP broadcast discovery of games hosted on the local network. */
public final class LanDiscovery {
    public static final int PORT = 47848;
    private static final String QUERY = "IBERIA_DISCOVER_V1";
    private static final String REPLY = "IBERIA_HOST_V1";

    private LanDiscovery() {
    }

    public static final class HostInfo {
        public String address;
        public int port;
        public String name;
        public int players;
        public boolean inGame;

        @Override
        public String toString() {
            return name + " — " + address + ":" + port + " (" + players + " jogador" + (players == 1 ? "" : "es")
                    + (inGame ? ", em andamento" : ", no saguão") + ")";
        }
    }

    /** Server side: answers discovery queries with {@code name;tcpPort;players;inGame}. */
    public static final class Responder implements Closeable {
        private final DatagramSocket socket;
        private volatile boolean running = true;

        private Responder(DatagramSocket socket, Supplier<String> info) {
            this.socket = socket;
            Thread t = new Thread(() -> loop(info), "iberia-discovery");
            t.setDaemon(true);
            t.start();
        }

        private void loop(Supplier<String> info) {
            byte[] buf = new byte[512];
            while (running) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    if (!QUERY.equals(msg.trim())) continue;
                    byte[] reply = (REPLY + ";" + info.get()).getBytes(StandardCharsets.UTF_8);
                    socket.send(new DatagramPacket(reply, reply.length, p.getAddress(), p.getPort()));
                } catch (IOException e) {
                    if (!running) return;
                }
            }
        }

        @Override
        public void close() {
            running = false;
            socket.close();
        }
    }

    public static Responder startResponder(Supplier<String> info) throws SocketException {
        DatagramSocket socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(PORT));
        return new Responder(socket, info);
    }

    /** Broadcasts a query and collects answers for {@code timeoutMs} milliseconds. */
    public static List<HostInfo> discover(int timeoutMs) {
        Map<String, HostInfo> found = new LinkedHashMap<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(150);
            byte[] q = QUERY.getBytes(StandardCharsets.UTF_8);
            for (InetAddress target : broadcastTargets()) {
                try {
                    socket.send(new DatagramPacket(q, q.length, target, PORT));
                } catch (IOException ignored) {
                    // interface may refuse broadcasts
                }
            }
            long end = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[512];
            while (System.currentTimeMillis() < end) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    String[] parts = msg.split(";");
                    if (parts.length < 5 || !REPLY.equals(parts[0])) continue;
                    HostInfo h = new HostInfo();
                    h.address = p.getAddress().getHostAddress();
                    h.name = parts[1];
                    h.port = Integer.parseInt(parts[2]);
                    h.players = Integer.parseInt(parts[3]);
                    h.inGame = Boolean.parseBoolean(parts[4]);
                    found.putIfAbsent(h.address + ":" + h.port, h);
                } catch (SocketTimeoutException ignored) {
                    // keep waiting until the deadline
                } catch (NumberFormatException ignored) {
                    // malformed reply
                }
            }
        } catch (IOException e) {
            return Collections.emptyList();
        }
        return new ArrayList<>(found.values());
    }

    private static Set<InetAddress> broadcastTargets() {
        Set<InetAddress> out = new LinkedHashSet<>();
        try {
            out.add(InetAddress.getByName("255.255.255.255"));
            out.add(InetAddress.getLoopbackAddress());
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) out.add(ia.getBroadcast());
                }
            }
        } catch (IOException ignored) {
            // fall back to whatever was collected
        }
        return out;
    }
}
