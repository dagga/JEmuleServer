package org.jemule.network;

import org.jemule.protocol.Tag;
import org.jemule.security.ServerObfuscation;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Verified eD2K UDP server peers and their server-list gossip transactions. */
final class ServerGossip {
    private static final int PROTOCOL = Packet.PROTOCOL_ED2K & 0xFF;
    private static final int OP_GLOBSERVSTATREQ = 0x96;
    private static final int OP_GLOBSERVSTATRES = 0x97;
    private static final int OP_SERVER_LIST_REQ = 0xA0;
    private static final int OP_SERVER_LIST_RES = 0xA1;
    private static final int OP_SERVER_DESC_REQ = 0xA2;
    private static final int OP_SERVER_DESC_RES = 0xA3;
    private static final int OP_SERVER_LIST_REQ2 = 0xA4;
    private static final long PROBE_INTERVAL_MILLIS = 60_000;
    private static final long TRANSACTION_TIMEOUT_MILLIS = 10_000;
    private static final int MAX_PEERS = 512;
    private static final SecureRandom RANDOM = new SecureRandom();

    record Endpoint(InetAddress address, int tcpPort) {
        Endpoint {
            if (!(address instanceof Inet4Address) || tcpPort < 1 || tcpPort > 65531) {
                throw new IllegalArgumentException("Server gossip requires an IPv4 endpoint with room for UDP port +4");
            }
        }

        int udpPort() {
            return tcpPort + 4;
        }
    }

    record VerifiedPeer(Endpoint endpoint, String name, int udpKey, long verifiedAtMillis) {}

    private enum Stage { STATUS, DESCRIPTION, LIST }

    private record Pending(Stage stage, int challenge, long startedAtMillis) {}
    record Status(int udpKey) {}

    private final Map<Endpoint, Pending> pending = new ConcurrentHashMap<>();
    private final Map<Endpoint, VerifiedPeer> verified = new ConcurrentHashMap<>();
    private final Map<Endpoint, Boolean> candidates = new ConcurrentHashMap<>();
    private final Map<Endpoint, Boolean> rejected = new ConcurrentHashMap<>();
    private final Map<Endpoint, Integer> missedStatusProbes = new ConcurrentHashMap<>();
    private final AtomicInteger candidateCursor = new AtomicInteger();
    private volatile long lastProbeAtMillis;

    ServerGossip(String configuredPeers) {
        parseEndpoints(configuredPeers).forEach(endpoint -> candidates.put(endpoint, Boolean.TRUE));
    }

    Collection<VerifiedPeer> verifiedPeers() {
        return List.copyOf(verified.values());
    }

    Collection<Endpoint> candidateEndpoints() {
        return List.copyOf(candidates.keySet());
    }

    void poll(DatagramSocket socket, long now) {
        expirePending(now);
        if (now - lastProbeAtMillis < PROBE_INTERVAL_MILLIS) return;
        lastProbeAtMillis = now;
        List<Endpoint> endpoints = new ArrayList<>(candidates.keySet());
        if (endpoints.isEmpty()) return;
        int first = Math.floorMod(candidateCursor.getAndAdd(32), endpoints.size());
        int attempts = Math.min(32, endpoints.size());
        for (int offset = 0; offset < attempts; offset++) {
            Endpoint endpoint = endpoints.get((first + offset) % endpoints.size());
            if (pending.containsKey(endpoint)) continue;
            int challenge = nextChallenge();
            pending.put(endpoint, new Pending(Stage.STATUS, challenge, now));
            send(socket, endpoint, frame(OP_GLOBSERVSTATREQ, intPayload(challenge)));
        }
    }

    int addClientReportedServerList(byte[] data) {
        if (data == null || data.length < 1) return 0;
        int count = data[0] & 0xFF;
        if (data.length != 1 + count * 6) return 0;
        ByteBuffer input = ByteBuffer.wrap(data, 1, data.length - 1).order(ByteOrder.LITTLE_ENDIAN);
        int before = candidates.size();
        for (int i = 0; i < count; i++) {
            byte[] ip = new byte[4];
            input.get(ip);
            int port = Short.toUnsignedInt(input.getShort());
            try {
                InetAddress address = InetAddress.getByAddress(ip);
                if (port > 0 && port <= 65531 && !address.isAnyLocalAddress()
                        && !address.isMulticastAddress() && !address.isLoopbackAddress()) {
                    addCandidate(new Endpoint(address, port));
                }
            } catch (UnknownHostException | IllegalArgumentException ignored) {
                // Invalid endpoints are not candidates; valid entries in the same list remain usable.
            }
        }
        return candidates.size() - before;
    }

    byte[] clientServerList(InetAddress publicIp, int tcpPort) {
        List<Endpoint> endpoints = new ArrayList<>();
        if (publicIp instanceof Inet4Address && !publicIp.isAnyLocalAddress()
                && !publicIp.isLoopbackAddress() && tcpPort > 0 && tcpPort <= 65531) {
            endpoints.add(new Endpoint(publicIp, tcpPort));
        }
        verified.values().stream().map(VerifiedPeer::endpoint)
                .filter(endpoint -> !endpoints.contains(endpoint))
                .sorted(Comparator.comparing((Endpoint endpoint) -> endpoint.address().getHostAddress())
                        .thenComparingInt(Endpoint::tcpPort))
                .limit(255 - endpoints.size())
                .forEach(endpoints::add);
        ByteBuffer result = ByteBuffer.allocate(1 + endpoints.size() * 6).order(ByteOrder.LITTLE_ENDIAN);
        result.put((byte) endpoints.size());
        for (Endpoint endpoint : endpoints) {
            result.put(endpoint.address().getAddress()).putShort((short) endpoint.tcpPort());
        }
        return result.array();
    }

    boolean handle(DatagramSocket socket, InetAddress source, int sourcePort, byte[] packet, boolean wasObfuscated,
                   InetAddress publicIp, int localTcpPort, long now) {
        if (packet.length < 2 || (packet[0] & 0xFF) != PROTOCOL) return false;
        int opcode = packet[1] & 0xFF;
        Endpoint endpoint = endpointForSource(source, sourcePort);

        if (opcode == OP_GLOBSERVSTATREQ) {
            if (packet.length != 6) return true;
            addCandidate(endpoint);
            return true;
        }
        if (opcode == OP_SERVER_LIST_REQ) {
            if (packet.length != 8) return true;
            Endpoint announced = readAnnouncedEndpoint(packet, source, sourcePort);
            addCandidate(announced);
            if (endpoint != null && verified.containsKey(endpoint)) {
                sendList(socket, endpoint, verified.values(), publicIp, localTcpPort, wasObfuscated);
            }
            return true;
        }
        if (opcode == OP_SERVER_LIST_REQ2) {
            if (packet.length == 2) {
                sendList(socket, endpoint, verified.values(), publicIp, localTcpPort, wasObfuscated);
            }
            return true;
        }
        if (endpoint == null) return false;

        Pending transaction = pending.get(endpoint);
        if (transaction == null) return false;
        if (opcode == OP_GLOBSERVSTATRES && transaction.stage() == Stage.STATUS) {
            Status status = validateStatus(packet, transaction.challenge());
            if (status == null) {
                reject(endpoint, "invalid or mismatched server status");
                return true;
            }
            statusKeys.put(endpoint, status.udpKey());
            missedStatusProbes.remove(endpoint);
            int challenge = nextDescriptionChallenge();
            pending.put(endpoint, new Pending(Stage.DESCRIPTION, challenge, now));
            byte[] request = frame(OP_SERVER_DESC_REQ, intPayload(challenge));
            send(socket, endpoint, request);
            return true;
        }
        if (opcode == OP_SERVER_DESC_RES && transaction.stage() == Stage.DESCRIPTION) {
            String name = validateDescription(packet, transaction.challenge());
            if (name == null) {
                reject(endpoint, "invalid, junk, or MLDonkey server description");
                return true;
            }
            int remoteKey = extractStatusKey(endpoint);
            verified.put(endpoint, new VerifiedPeer(endpoint, name, remoteKey, now));
            statusKeys.remove(endpoint);
            pending.remove(endpoint);
            int challenge = nextChallenge();
            pending.put(endpoint, new Pending(Stage.LIST, challenge, now));
            byte[] announcement = announce(localPublicIp(publicIp, source), localTcpPort);
            byte[] request = frame(OP_SERVER_LIST_REQ, announcement);
            send(socket, endpoint, remoteKey == 0 ? request : ServerObfuscation.encode(request, remoteKey));
            return true;
        }
        if (opcode == OP_SERVER_LIST_RES && transaction.stage() == Stage.LIST) {
            List<Endpoint> discovered = decodeServerList(packet);
            if (discovered == null) {
                reject(endpoint, "malformed server-list response");
                return true;
            }
            for (Endpoint discoveredEndpoint : discovered) {
                if (!discoveredEndpoint.equals(endpoint) && candidates.size() < MAX_PEERS) {
                    addCandidate(discoveredEndpoint);
                }
            }
            pending.remove(endpoint);
            return true;
        }
        return false;
    }

    private final Map<Endpoint, Integer> statusKeys = new ConcurrentHashMap<>();

    private int extractStatusKey(Endpoint endpoint) {
        return statusKeys.getOrDefault(endpoint, 0);
    }

    private void reject(Endpoint endpoint, String reason) {
        pending.remove(endpoint);
        verified.remove(endpoint);
        statusKeys.remove(endpoint);
        candidates.remove(endpoint);
        if (rejected.size() >= MAX_PEERS) rejected.clear();
        rejected.put(endpoint, Boolean.TRUE);
        System.getLogger(ServerGossip.class.getName()).log(System.Logger.Level.WARNING,
                "Rejected server peer {0}:{1}: {2}", endpoint.address().getHostAddress(), endpoint.tcpPort(), reason);
    }

    private void sendList(DatagramSocket socket, Endpoint endpoint, Collection<VerifiedPeer> peers,
                          InetAddress publicIp, int localTcpPort, boolean obfuscated) {
        if (endpoint == null) return;
        List<Endpoint> entries = new ArrayList<>();
        if (publicIp instanceof Inet4Address && !publicIp.isAnyLocalAddress() && !publicIp.isLoopbackAddress()) {
            entries.add(new Endpoint(publicIp, localTcpPort));
        }
        peers.stream().map(VerifiedPeer::endpoint).filter(peer -> !peer.equals(endpoint))
                .sorted(Comparator.comparing((Endpoint peer) -> peer.address().getHostAddress())
                        .thenComparingInt(Endpoint::tcpPort))
                .forEach(peer -> {
                    if (entries.size() < 255) entries.add(peer);
                });
        ByteBuffer response = ByteBuffer.allocate(3 + entries.size() * 6).order(ByteOrder.LITTLE_ENDIAN);
        response.put((byte) PROTOCOL).put((byte) OP_SERVER_LIST_RES).put((byte) entries.size());
        for (Endpoint entry : entries) {
            response.put(entry.address().getAddress()).putShort((short) entry.tcpPort());
        }
        byte[] data = response.array();
        VerifiedPeer peer = verified.get(endpoint);
        if (obfuscated && peer != null && peer.udpKey() != 0) {
            data = ServerObfuscation.encode(data, peer.udpKey());
        }
        send(socket, endpoint, data);
    }

    static Status validateStatus(byte[] packet, int challenge) {
        if (packet.length != 42 || (packet[0] & 0xFF) != PROTOCOL || (packet[1] & 0xFF) != OP_GLOBSERVSTATRES) return null;
        ByteBuffer data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        data.position(2);
        if (data.getInt() != challenge) return null;
        long users = Integer.toUnsignedLong(data.getInt());
        long files = Integer.toUnsignedLong(data.getInt());
        long maxUsers = Integer.toUnsignedLong(data.getInt());
        data.getInt(); // soft files
        long hardFiles = Integer.toUnsignedLong(data.getInt());
        data.getInt(); // UDP flags
        long lowIdUsers = Integer.toUnsignedLong(data.getInt());
        data.getShort(); // UDP obfuscation port
        data.getShort(); // TCP obfuscation port
        int udpKey = data.getInt();
        if (maxUsers == 0 || maxUsers > 100_000_000L || users > maxUsers || lowIdUsers > users
                || (hardFiles != 0 && files > hardFiles)) return null;
        return new Status(udpKey);
    }

    static String validateDescription(byte[] packet, int challenge) {
        if (packet.length < 6 || (packet[0] & 0xFF) != PROTOCOL || (packet[1] & 0xFF) != OP_SERVER_DESC_RES) return null;
        ByteBuffer data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        data.position(2);
        if (data.getInt() != challenge || (challenge & 0xFFFF) != 0xF0FF) return null;
        List<Tag> tags;
        try {
            tags = Tag.readList(data);
        } catch (RuntimeException e) {
            return null;
        }
        if (data.hasRemaining() || tags.isEmpty()) return null;
        String name = null;
        String description = "";
        for (Tag tag : tags) {
            if (tag.name().equals(Tag.NAME_SERVERNAME)) name = String.valueOf(tag.value()).trim();
            if (tag.name().equals(Tag.NAME_DESCRIPTION)) description = String.valueOf(tag.value()).trim();
        }
        if (name == null || name.isBlank()) return null;
        String identity = (name + " " + description).toLowerCase(Locale.ROOT);
        if (identity.contains("mldonkey") || identity.contains("ml donkey")) return null;
        return name;
    }

    static List<Endpoint> decodeServerList(byte[] packet) {
        if (packet.length < 3 || (packet[0] & 0xFF) != PROTOCOL || (packet[1] & 0xFF) != OP_SERVER_LIST_RES) return null;
        int count = packet[2] & 0xFF;
        if (packet.length != 3 + count * 6) return null;
        ByteBuffer data = ByteBuffer.wrap(packet, 3, packet.length - 3).order(ByteOrder.LITTLE_ENDIAN);
        List<Endpoint> endpoints = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] ip = new byte[4];
            data.get(ip);
            int port = Short.toUnsignedInt(data.getShort());
            try {
                InetAddress address = InetAddress.getByAddress(ip);
                if (port > 0 && port <= 65531 && !address.isAnyLocalAddress() && !address.isMulticastAddress()) {
                    endpoints.add(new Endpoint(address, port));
                }
            } catch (UnknownHostException | IllegalArgumentException ignored) {
                // Invalid entries are filtered; the enclosing packet length and count remain verified.
            }
        }
        return endpoints;
    }

    static Collection<Endpoint> parseEndpoints(String configuredPeers) {
        Map<Endpoint, Boolean> endpoints = new ConcurrentHashMap<>();
        if (configuredPeers == null || configuredPeers.isBlank()) return endpoints.keySet();
        for (String value : configuredPeers.split(",")) {
            String entry = value.trim();
            if (entry.isEmpty()) continue;
            int colon = entry.lastIndexOf(':');
            if (colon <= 0 || colon == entry.length() - 1) {
                throw new IllegalArgumentException("Invalid serverPeers endpoint: " + entry + " (expected IPv4-host:TCP-port)");
            }
            try {
                InetAddress address = InetAddress.getByName(entry.substring(0, colon).trim());
                int port = Integer.parseInt(entry.substring(colon + 1).trim());
                endpoints.put(new Endpoint(address, port), Boolean.TRUE);
                if (endpoints.size() > MAX_PEERS) {
                    throw new IllegalArgumentException("serverPeers exceeds the maximum of " + MAX_PEERS + " entries");
                }
            } catch (UnknownHostException | NumberFormatException e) {
                throw new IllegalArgumentException("Invalid serverPeers endpoint: " + entry, e);
            }
        }
        return endpoints.keySet();
    }

    private Endpoint readAnnouncedEndpoint(byte[] packet, InetAddress source, int sourcePort) {
        byte[] ip = {packet[2], packet[3], packet[4], packet[5]};
        int tcpPort = Short.toUnsignedInt(ByteBuffer.wrap(packet, 6, 2).order(ByteOrder.LITTLE_ENDIAN).getShort());
        if (!java.util.Arrays.equals(ip, source.getAddress()) || sourcePort != tcpPort + 4 || tcpPort < 1 || tcpPort > 65531) {
            return null;
        }
        return new Endpoint(source, tcpPort);
    }

    private Endpoint endpointForSource(InetAddress source, int sourcePort) {
        int tcpPort = sourcePort - 4;
        if (tcpPort < 1) return null;
        try {
            return new Endpoint(source, tcpPort);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private byte[] announce(InetAddress publicIp, int tcpPort) {
        return ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                .put(publicIp.getAddress()).putShort((short) tcpPort).array();
    }

    private static InetAddress localPublicIp(InetAddress configuredIp, InetAddress source) {
        return configuredIp instanceof Inet4Address && !configuredIp.isAnyLocalAddress()
                ? configuredIp
                : source;
    }

    private void send(DatagramSocket socket, Endpoint endpoint, byte[] packet) {
        try {
            socket.send(new DatagramPacket(packet, packet.length, endpoint.address(), endpoint.udpPort()));
        } catch (java.io.IOException e) {
            System.getLogger(ServerGossip.class.getName()).log(System.Logger.Level.DEBUG,
                    "Could not send server gossip packet to {0}:{1}: {2}",
                    endpoint.address().getHostAddress(), endpoint.udpPort(), e.getMessage());
        }

    }

    int keyFor(InetAddress address, int sourcePort) {
        Endpoint endpoint = endpointForSource(address, sourcePort);
        VerifiedPeer peer = endpoint == null ? null : verified.get(endpoint);
        return peer == null ? 0 : peer.udpKey();
    }

    private void addCandidate(Endpoint endpoint) {
        if (endpoint != null && !rejected.containsKey(endpoint) && candidates.size() < MAX_PEERS) {
            candidates.putIfAbsent(endpoint, Boolean.TRUE);
        }
    }

    private void expirePending(long now) {
        pending.entrySet().removeIf(entry -> {
            if (now - entry.getValue().startedAtMillis() <= TRANSACTION_TIMEOUT_MILLIS) return false;
            Endpoint endpoint = entry.getKey();
            Pending transaction = entry.getValue();
            if (transaction.stage() == Stage.STATUS) {
                int misses = missedStatusProbes.merge(endpoint, 1, Integer::sum);
                if (misses >= 3) {
                    verified.remove(endpoint);
                    statusKeys.remove(endpoint);
                }
            } else {
                statusKeys.remove(endpoint);
            }
            return true;
        });
    }

    private static int nextChallenge() {
        int challenge;
        do {
            challenge = RANDOM.nextInt();
        } while (challenge == 0);
        return challenge;
    }

    private static int nextDescriptionChallenge() {
        return (RANDOM.nextInt() & 0xFFFF0000) | 0xF0FF;
    }

    private static byte[] intPayload(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private static byte[] frame(int opcode, byte[] payload) {
        ByteBuffer packet = ByteBuffer.allocate(2 + payload.length);
        packet.put((byte) PROTOCOL).put((byte) opcode).put(payload);
        return packet.array();
    }
}
