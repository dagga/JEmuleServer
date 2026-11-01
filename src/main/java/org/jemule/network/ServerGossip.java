package org.jemule.network;

import org.jemule.protocol.Tag;
import org.jemule.security.IPFilter;
import org.jemule.security.ServerObfuscation;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Verified eD2K UDP server peers and their server-list gossip transactions.
 * <p>
 * A server is verified with OP_GLOBSERVSTATREQ and OP_SERVER_DESC_REQ on its TCP+4, then this server registers with
 * it as Lugdunum and ed2k-rust do: a plain OP_SERVER_LIST_REQ {@code <ip 4><tcp port 2><challenge 4>} to its TCP+4
 * and, when the server advertised a UDP key, the obfuscated handshake Lugdunum requires before listing a server
 * (from this server's TCP+14 socket, a crypt ping to its TCP+12, whose answer gives its key, then OP_SERVER_LIST_REQ
 * and OP_SERVER_LIST_REQ2 obfuscated with that key to its TCP+14). Lugdunum takes obfuscated server traffic only on
 * TCP+14, and only from the source port of the crypt ping, which it keys on that ip:port.
 */
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
    /** A rejected server is probed again after this delay: its answer may have been refused by mistake. */
    static final long REJECT_RETRY_MILLIS = 6 * 60 * 60_000L;
    private static final SecureRandom RANDOM = new SecureRandom();
    /** OP_GLOBSERVSTATRES up to the maximum users, the first field the plausibility check needs. */
    private static final int MIN_STATUS_LENGTH = 2 + 4 * 4;
    /** High word of the status challenge which makes servers answer with the extended fields (eMule's). */
    private static final int EXTENDED_STATUS_CHALLENGE = 0x55AA0000;
    /** Crypt pings received from servers are kept this long, to answer their obfuscated server-list requests. */
    private static final long RECEIVED_PING_TTL_MILLIS = 10 * 60_000L;

    record Endpoint(InetAddress address, int tcpPort) {
        Endpoint {
            if (!(address instanceof Inet4Address) || tcpPort < 1 || tcpPort > 65531) {
                throw new IllegalArgumentException("Server gossip requires an IPv4 endpoint with room for UDP port +4");
            }
        }

        int udpPort() {
            return tcpPort + 4;
        }

        /** Whether the server's crypt-ping (TCP+12) and obfuscated (TCP+14) UDP ports exist. */
        boolean hasObfuscatedPorts() {
            return tcpPort <= 0xFFFF - 14;
        }
    }

    record VerifiedPeer(Endpoint endpoint, String name, int udpKey, long verifiedAtMillis) {
    }

    /** Where a monitored server was discovered. */
    enum ServerSource { CONFIGURED, SERVER_MET, CLIENT_REPORTED, GOSSIP }

    /** Last successful metadata observed for a server (from OP_GLOBSERVSTATRES). */
    private record ServerStatus(int udpKey, long users, long files, long softFiles, long hardFiles,
                                long maxUsers, long lowIdUsers, long observedAtMillis, long latencyMillis) {
    }

    /**
     * Read-only monitoring view of one known eD2K server and its state.
     *
     * @param state one of {@code candidate}, {@code probing}, {@code verified}, {@code rejected}
     */
    public record ServerView(String name, String state, ServerSource source, Endpoint endpoint,
                             Long users, Long files, Long softFiles, Long hardFiles, Long maxUsers,
                             Long lowIdUsers, boolean hasUdpKey, Long latencyMillis, Long lastStatusAtMillis,
                             Long verifiedAtMillis, int missedStatusProbes) {
    }

    private enum Stage {STATUS, DESCRIPTION, LIST}

    private record Pending(Stage stage, int challenge, long startedAtMillis) {
    }

    record Status(int udpKey, long users, long files, long softFiles, long hardFiles, long maxUsers,
                  long lowIdUsers) {
    }

    /**
     * Obfuscated handshake with one server: the crypt ping carrying {@code randomPart}, then, once the server
     * answered it ({@code answered}), its UDP key for this server's address.
     */
    private record ObfuscatedSession(int randomPart, int peerKey, boolean answered, boolean listReceived) {
    }

    /** A crypt ping a server sent to this server's TCP+12. */
    private record ReceivedPing(int randomPart, long receivedAtMillis) {
    }

    /** An obfuscated datagram of a server in an obfuscated handshake, and how to obfuscate the answer to it. */
    record Decoded(byte[] packet, UnaryOperator<byte[]> answerObfuscator) {
    }

    private final IPFilter ipFilter;
    /** Addresses of the configured servers (serverPeers), which the IP filter never blocks. */
    private final java.util.Set<InetAddress> trusted;
    /** Trusted addresses already logged as being in a blocked range. */
    private final java.util.Set<InetAddress> trustedInBlockedRange = ConcurrentHashMap.newKeySet();
    private final Map<Endpoint, Pending> pending = new ConcurrentHashMap<>();
    private final Map<Endpoint, VerifiedPeer> verified = new ConcurrentHashMap<>();
    private final Map<Endpoint, Boolean> candidates = new ConcurrentHashMap<>();
    /** Rejected servers and when they were rejected. */
    private final Map<Endpoint, Long> rejected = new ConcurrentHashMap<>();
    private final Map<Endpoint, Integer> missedStatusProbes = new ConcurrentHashMap<>();
    private final Map<Endpoint, ServerSource> sources = new ConcurrentHashMap<>();
    private final Map<Endpoint, ServerStatus> serverStatuses = new ConcurrentHashMap<>();
    private final Map<Endpoint, Long> probeSentAt = new ConcurrentHashMap<>();
    private final Map<Endpoint, ObfuscatedSession> obfuscatedSessions = new ConcurrentHashMap<>();
    /** Last extended status challenge each server sent, echoed in the obfuscated status sent to its TCP+14. */
    private final Map<Endpoint, Integer> statusChallenges = new ConcurrentHashMap<>();
    /** Crypt pings received from the servers' sockets: their obfuscated requests are answered with that key. */
    private final Map<InetSocketAddress, ReceivedPing> receivedPings = new ConcurrentHashMap<>();
    private final AtomicInteger candidateCursor = new AtomicInteger();
    private volatile long lastProbeAtMillis;
    private volatile Supplier<DatagramSocket> mainSocket = () -> null;
    private volatile Supplier<DatagramSocket> obfuscatedSocket = () -> null;
    /** OP_GLOBSERVSTATRES of this server for a challenge and the address it is sent to. */
    private volatile BiFunction<Integer, InetAddress, byte[]> statusBuilder;

    ServerGossip(String configuredPeers) {
        this(configuredPeers, new IPFilter());
    }

    /**
     * @param configuredPeers the servers of {@code serverPeers}: trusted, kept and heard whatever the IP filter says
     *                        (a blocklist may cover a whole hosting provider, the administrator's own servers included)
     */
    ServerGossip(String configuredPeers, IPFilter ipFilter) {
        this.ipFilter = ipFilter != null ? ipFilter : new IPFilter();
        java.util.Set<InetAddress> configured = new java.util.HashSet<>();
        parseEndpoints(configuredPeers).forEach(endpoint -> {
            candidates.put(endpoint, Boolean.TRUE);
            sources.put(endpoint, ServerSource.CONFIGURED);
            configured.add(endpoint.address());
        });
        trusted = java.util.Set.copyOf(configured);
    }

    /**
     * The sockets the gossip sends from: the main server socket (TCP+4) and the obfuscated one (TCP+14), and this
     * server's status, sent obfuscated to the servers in an obfuscated handshake. Without them, the packets are sent
     * from the socket they answer and no obfuscated handshake is made.
     */
    void useSockets(Supplier<DatagramSocket> main, Supplier<DatagramSocket> obfuscated,
                    BiFunction<Integer, InetAddress, byte[]> status) {
        mainSocket = main != null ? main : () -> null;
        obfuscatedSocket = obfuscated != null ? obfuscated : () -> null;
        statusBuilder = status;
    }

    /**
     * Whether the IP filter blocks a server (its ranges of hosting providers left out): never one of {@link #trusted},
     * which is logged once.
     */
    private boolean blocked(InetAddress address) {
        if (!ipFilter.isServerBlocked(address)) return false;
        if (!trusted.contains(address)) return true;
        if (trustedInBlockedRange.add(address)) {
            System.getLogger(ServerGossip.class.getName()).log(System.Logger.Level.WARNING,
                    "Configured server peer {0} is in a blocked range of the IP filter: kept, because it is configured",
                    address.getHostAddress());
        }
        return false;
    }

    /** Whether an address is an eD2K server: a configured peer, or a verified one. */
    boolean isServer(InetAddress address) {
        if (address == null) return false;
        if (trusted.contains(address)) return true;
        for (Endpoint endpoint : verified.keySet()) {
            if (endpoint.address().equals(address)) return true;
        }
        return false;
    }

    Collection<VerifiedPeer> verifiedPeers() {
        if (ipFilter != null) {
            verified.keySet().removeIf(endpoint -> blocked(endpoint.address()));
        }
        return List.copyOf(verified.values());
    }

    Collection<Endpoint> candidateEndpoints() {
        if (ipFilter != null) {
            candidates.keySet().removeIf(endpoint -> blocked(endpoint.address()));
        }
        return List.copyOf(candidates.keySet());
    }

    /**
     * Snapshot of every known eD2K server (candidate, probing, verified or rejected) with its last known state.
     */
    public List<ServerView> serverViews() {
        if (ipFilter != null) {
            candidates.keySet().removeIf(endpoint -> blocked(endpoint.address()));
            verified.keySet().removeIf(endpoint -> blocked(endpoint.address()));
        }
        java.util.Set<Endpoint> endpoints = new java.util.TreeSet<>(
                Comparator.comparing((Endpoint endpoint) -> endpoint.address().getHostAddress())
                        .thenComparingInt(Endpoint::tcpPort));
        endpoints.addAll(candidates.keySet());
        endpoints.addAll(verified.keySet());
        endpoints.addAll(rejected.keySet());
        List<ServerView> views = new ArrayList<>(endpoints.size());
        for (Endpoint endpoint : endpoints) {
            String name = "";
            String state;
            if (rejected.containsKey(endpoint)) {
                state = "rejected";
            } else if (verified.containsKey(endpoint)) {
                state = "verified";
                name = verified.get(endpoint).name();
            } else if (pending.containsKey(endpoint)) {
                state = "probing";
            } else {
                state = "candidate";
            }
            VerifiedPeer peer = verified.get(endpoint);
            ServerStatus status = serverStatuses.get(endpoint);
            views.add(new ServerView(
                    name,
                    state,
                    sources.getOrDefault(endpoint, ServerSource.GOSSIP),
                    endpoint,
                    status == null ? null : status.users(),
                    status == null ? null : status.files(),
                    status == null ? null : status.softFiles(),
                    status == null ? null : status.hardFiles(),
                    status == null ? null : status.maxUsers(),
                    status == null ? null : status.lowIdUsers(),
                    status != null && status.udpKey() != 0,
                    status == null ? null : status.latencyMillis(),
                    status == null ? null : status.observedAtMillis(),
                    peer == null ? null : peer.verifiedAtMillis(),
                    missedStatusProbes.getOrDefault(endpoint, 0)));
        }
        return views;
    }

    int addServerMetCandidates(Collection<Endpoint> endpoints) {
        int added = 0;
        for (Endpoint endpoint : endpoints) {
            if (addCandidate(endpoint, ServerSource.SERVER_MET)) added++;
        }
        if (added > 0) lastProbeAtMillis = 0;
        return added;
    }

    // poll (maintenance thread) and handle (UDP thread) share the probing state
    synchronized void poll(DatagramSocket socket, long now) {
        expirePending(now);
        rejected.entrySet().removeIf(entry -> {
            if (now - entry.getValue() < REJECT_RETRY_MILLIS) return false;
            if (candidates.size() < MAX_PEERS) candidates.putIfAbsent(entry.getKey(), Boolean.TRUE);
            return true;
        });
        if (ipFilter != null) {
            candidates.keySet().removeIf(endpoint -> blocked(endpoint.address()));
            verified.keySet().removeIf(endpoint -> blocked(endpoint.address()));
        }
        obfuscatedSessions.keySet().removeIf(endpoint -> !isKnown(endpoint));
        statusChallenges.keySet().removeIf(endpoint -> !isKnown(endpoint));
        receivedPings.values().removeIf(ping -> now - ping.receivedAtMillis() > RECEIVED_PING_TTL_MILLIS);
        if (now - lastProbeAtMillis < PROBE_INTERVAL_MILLIS) return;
        lastProbeAtMillis = now;
        List<Endpoint> endpoints = new ArrayList<>(candidates.keySet());
        if (endpoints.isEmpty()) return;
        int first = Math.floorMod(candidateCursor.getAndAdd(32), endpoints.size());
        int attempts = Math.min(32, endpoints.size());
        for (int offset = 0; offset < attempts; offset++) {
            Endpoint endpoint = endpoints.get((first + offset) % endpoints.size());
            if (pending.containsKey(endpoint)) continue;
            probe(socket, endpoint, now);
        }
    }

    private void probe(DatagramSocket socket, Endpoint endpoint, long now) {
        int challenge = nextStatusChallenge();
        pending.put(endpoint, new Pending(Stage.STATUS, challenge, now));
        probeSentAt.put(endpoint, now);
        send(socket, endpoint, frame(OP_GLOBSERVSTATREQ, intPayload(challenge)));
    }

    private boolean isKnown(Endpoint endpoint) {
        return candidates.containsKey(endpoint) || verified.containsKey(endpoint);
    }

    private boolean isKnownAddress(InetAddress address) {
        if (trusted.contains(address)) return true;
        for (Endpoint endpoint : candidates.keySet()) {
            if (endpoint.address().equals(address)) return true;
        }
        return isServer(address);
    }

    /**
     * A crypt ping received on this server's TCP+12. A server's (Lugdunum's) is followed by obfuscated requests from
     * the same socket, which are answered with its random part.
     */
    void cryptPingReceived(InetAddress source, int sourcePort, int randomPart, long now) {
        if (source == null || blocked(source) || !isKnownAddress(source)) return;
        if (receivedPings.size() >= MAX_PEERS) {
            receivedPings.values().removeIf(ping -> now - ping.receivedAtMillis() > RECEIVED_PING_TTL_MILLIS);
            if (receivedPings.size() >= MAX_PEERS) return;
        }
        receivedPings.put(new InetSocketAddress(source, sourcePort), new ReceivedPing(randomPart, now));
    }

    /**
     * An OP_GLOBSERVSTATREQ this server answered. When a server sends it with the extended challenge, Lugdunum only
     * takes this server's UDP key from an obfuscated answer echoing that challenge: it is sent obfuscated to the
     * server's TCP+14 once its key is known, now or at the end of the obfuscated handshake.
     */
    synchronized void statusRequested(InetAddress source, int sourcePort, int challenge) {
        if ((challenge & 0xFFFF0000) != EXTENDED_STATUS_CHALLENGE || source == null || blocked(source)) return;
        Endpoint endpoint = endpointForSource(source, sourcePort);
        if (endpoint == null || !isKnown(endpoint)) return;
        statusChallenges.put(endpoint, challenge);
        ObfuscatedSession session = obfuscatedSessions.get(endpoint);
        if (session != null && session.peerKey() != 0) sendObfuscatedStatus(endpoint, session.peerKey(), challenge);
    }

    /**
     * Decodes an obfuscated datagram of a server in an obfuscated handshake with this server: the answer to the crypt
     * ping is obfuscated with its random part, and the other datagrams with that random part, the server's key or
     * {@code localKey}, this server's key for the address.
     *
     * @return {@code null} when no handshake with a server at that address decodes it
     */
    Decoded decodeObfuscated(InetAddress source, byte[] datagram, int localKey) {
        if (source == null || datagram == null) return null;
        for (Map.Entry<Endpoint, ObfuscatedSession> entry : obfuscatedSessions.entrySet()) {
            if (!entry.getKey().address().equals(source)) continue;
            ObfuscatedSession session = entry.getValue();
            Decoded decoded = decode(datagram, session.randomPart(), ServerObfuscation.ANSWER);
            if (decoded == null && session.peerKey() != 0) {
                decoded = decode(datagram, session.peerKey(), ServerObfuscation.ANSWER);
            }
            if (decoded == null) decoded = decode(datagram, session.randomPart(), ServerObfuscation.REQUEST);
            if (decoded == null && localKey != 0) decoded = decode(datagram, localKey, ServerObfuscation.ANSWER);
            if (decoded != null) return decoded;
        }
        return null;
    }

    private static Decoded decode(byte[] datagram, int key, byte direction) {
        byte[] plain = ServerObfuscation.decode(datagram, key, direction);
        if (plain == null || (plain[0] & 0xFF) != PROTOCOL) return null;
        byte answer = ServerObfuscation.reverse(direction);
        return new Decoded(plain, message -> ServerObfuscation.encode(message, key, answer));
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
                    addCandidate(new Endpoint(address, port), ServerSource.CLIENT_REPORTED);
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
                && !publicIp.isLoopbackAddress() && tcpPort > 0 && tcpPort <= 65531
                && !blocked(publicIp)) {
            endpoints.add(new Endpoint(publicIp, tcpPort));
        }
        verified.values().stream().map(VerifiedPeer::endpoint)
                .filter(endpoint -> !blocked(endpoint.address()))
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

    /**
     * @param answerObfuscator obfuscates the answers to a packet which arrived obfuscated, {@code null} for a plain one
     */
    synchronized boolean handle(DatagramSocket socket, InetAddress source, int sourcePort, byte[] packet,
                                UnaryOperator<byte[]> answerObfuscator, InetAddress publicIp, int localTcpPort,
                                long now) {
        if (blocked(source)) return false;
        if (packet.length < 2 || (packet[0] & 0xFF) != PROTOCOL) return false;
        int opcode = packet[1] & 0xFF;
        Endpoint endpoint = endpointForSource(source, sourcePort);

        if (opcode == OP_GLOBSERVSTATREQ) {
            if (packet.length != 6) return true;
            addCandidate(endpoint);
            return true;
        }
        if (opcode == OP_SERVER_LIST_REQ) {
            // <ip 4><tcp port 2>, followed by a challenge (Lugdunum, ed2k-rust) or more
            if (packet.length < 8) return true;
            Endpoint announced = readAnnouncedEndpoint(packet, source, sourcePort, answerObfuscator != null);
            Endpoint requester = announced != null ? announced : endpoint;
            if (announced != null) {
                addCandidate(announced);
                if (candidates.containsKey(announced) && !verified.containsKey(announced)
                        && !pending.containsKey(announced)
                        && now - probeSentAt.getOrDefault(announced, 0L) >= PROBE_INTERVAL_MILLIS) {
                    probe(main(socket), announced, now); // listed once verified
                }
            }
            if (requester != null && verified.containsKey(requester)) {
                sendList(socket, source, sourcePort, requester, verified.values(), publicIp, localTcpPort,
                        answerObfuscator);
            }
            return true;
        }
        if (opcode == OP_SERVER_LIST_REQ2) {
            if (packet.length == 2) {
                Endpoint requester = answerObfuscator != null ? sessionEndpoint(source) : null;
                sendList(socket, source, sourcePort, requester != null ? requester : endpoint, verified.values(),
                        publicIp, localTcpPort, answerObfuscator);
            }
            return true;
        }
        if (answerObfuscator != null && handleObfuscatedAnswer(source, opcode, packet, publicIp, localTcpPort)) {
            return true;
        }
        if (endpoint == null) return false;

        Pending transaction = pending.get(endpoint);
        if (transaction == null) return false;
        if (opcode == OP_GLOBSERVSTATRES && transaction.stage() == Stage.STATUS) {
            if (!hasChallenge(packet, transaction.challenge())) {
                // The late answer to an earlier probe, e.g. read after a long pause of this server: the answer to
                // the probe in progress may still come
                return true;
            }
            Status status = validateStatus(packet, transaction.challenge());
            if (status == null) {
                reject(endpoint, "invalid server status", now);
                return true;
            }
            long sentAt = probeSentAt.remove(endpoint);
            serverStatuses.put(endpoint, new ServerStatus(status.udpKey(), status.users(), status.files(),
                    status.softFiles(), status.hardFiles(), status.maxUsers(), status.lowIdUsers(), now,
                    sentAt <= 0 ? 0 : Math.max(0, now - sentAt)));
            missedStatusProbes.remove(endpoint);
            int challenge = nextDescriptionChallenge();
            pending.put(endpoint, new Pending(Stage.DESCRIPTION, challenge, now));
            byte[] request = frame(OP_SERVER_DESC_REQ, intPayload(challenge));
            send(socket, endpoint, request);
            return true;
        }
        if (opcode == OP_SERVER_DESC_RES && transaction.stage() == Stage.DESCRIPTION) {
            if (!hasChallenge(packet, transaction.challenge())) return true; // late, as above
            String name = validateDescription(packet, transaction.challenge());
            if (name == null) {
                reject(endpoint, "invalid, junk, or MLDonkey server description", now);
                return true;
            }
            int remoteKey = extractStatusKey(endpoint);
            verified.put(endpoint, new VerifiedPeer(endpoint, name, remoteKey, now));
            pending.remove(endpoint);
            int challenge = nextChallenge();
            pending.put(endpoint, new Pending(Stage.LIST, challenge, now));
            // Registration, in clear to the main port; then the obfuscated one, which Lugdunum requires
            send(main(socket), endpoint, frame(OP_SERVER_LIST_REQ,
                    registration(localPublicIp(publicIp, source), localTcpPort)));
            if (remoteKey != 0) startObfuscatedHandshake(endpoint);
            return true;
        }
        if (opcode == OP_SERVER_LIST_RES && transaction.stage() == Stage.LIST) {
            List<Endpoint> discovered = decodeServerList(packet);
            if (discovered == null) {
                reject(endpoint, "malformed server-list response", now);
                return true;
            }
            for (Endpoint discoveredEndpoint : discovered) {
                if (!discoveredEndpoint.equals(endpoint) && candidates.size() < MAX_PEERS) {
                    addCandidate(discoveredEndpoint, ServerSource.GOSSIP);
                }
            }
            pending.remove(endpoint);
            return true;
        }
        return false;
    }

    private int extractStatusKey(Endpoint endpoint) {
        ServerStatus status = serverStatuses.get(endpoint);
        return status == null ? 0 : status.udpKey();
    }

    /** Crypt ping from this server's obfuscated socket to the server's TCP+12. */
    private void startObfuscatedHandshake(Endpoint endpoint) {
        DatagramSocket socket = obfuscatedSocket.get();
        if (socket == null || !endpoint.hasObfuscatedPorts()) return;
        int randomPart = ServerObfuscation.cryptPingRandomPart();
        ObfuscatedSession previous = obfuscatedSessions.get(endpoint);
        // The key of the previous handshake stays usable until the server answers this one
        obfuscatedSessions.put(endpoint, new ObfuscatedSession(randomPart,
                previous == null ? 0 : previous.peerKey(), false, false));
        send(socket, endpoint.address(), endpoint.tcpPort() + 12, ServerObfuscation.cryptPing(randomPart));
    }

    /**
     * The obfuscated packets of a server in an obfuscated handshake: the answer to the crypt ping, which gives the
     * server's key and is followed by the obfuscated registration and server-list request, then the server list.
     */
    private boolean handleObfuscatedAnswer(InetAddress source, int opcode, byte[] packet, InetAddress publicIp,
                                           int localTcpPort) {
        if (opcode == OP_GLOBSERVSTATRES) {
            for (Map.Entry<Endpoint, ObfuscatedSession> entry : obfuscatedSessions.entrySet()) {
                Endpoint endpoint = entry.getKey();
                ObfuscatedSession session = entry.getValue();
                if (!endpoint.address().equals(source) || session.answered()
                        || !hasChallenge(packet, session.randomPart())) continue;
                Status status = validateStatus(packet, session.randomPart());
                if (status == null || status.udpKey() == 0) {
                    obfuscatedSessions.remove(endpoint);
                    return true;
                }
                obfuscatedSessions.put(endpoint, new ObfuscatedSession(session.randomPart(), status.udpKey(),
                        true, false));
                DatagramSocket socket = obfuscatedSocket.get();
                if (socket == null) return true;
                byte[] registration = frame(OP_SERVER_LIST_REQ,
                        registration(localPublicIp(publicIp, source), localTcpPort));
                sendObfuscated(socket, endpoint, registration, status.udpKey());
                sendObfuscated(socket, endpoint, frame(OP_SERVER_LIST_REQ2, new byte[0]), status.udpKey());
                Integer challenge = statusChallenges.get(endpoint);
                if (challenge != null) sendObfuscatedStatus(endpoint, status.udpKey(), challenge);
                return true;
            }
            return false;
        }
        if (opcode == OP_SERVER_LIST_RES) {
            Endpoint endpoint = sessionEndpoint(source);
            if (endpoint == null) return false;
            ObfuscatedSession session = obfuscatedSessions.get(endpoint);
            if (session == null || !session.answered() || session.listReceived()) return false;
            List<Endpoint> discovered = decodeServerList(packet);
            if (discovered == null) return true;
            for (Endpoint discoveredEndpoint : discovered) {
                if (!discoveredEndpoint.equals(endpoint) && candidates.size() < MAX_PEERS) {
                    addCandidate(discoveredEndpoint, ServerSource.GOSSIP);
                }
            }
            obfuscatedSessions.put(endpoint, new ObfuscatedSession(session.randomPart(), session.peerKey(),
                    true, true));
            return true;
        }
        return false;
    }

    /** The server at {@code source} in an obfuscated handshake which the server answered. */
    private Endpoint sessionEndpoint(InetAddress source) {
        for (Map.Entry<Endpoint, ObfuscatedSession> entry : obfuscatedSessions.entrySet()) {
            if (entry.getKey().address().equals(source) && entry.getValue().answered()) return entry.getKey();
        }
        return null;
    }

    private void sendObfuscatedStatus(Endpoint endpoint, int peerKey, int challenge) {
        BiFunction<Integer, InetAddress, byte[]> builder = statusBuilder;
        DatagramSocket socket = obfuscatedSocket.get();
        if (builder == null || socket == null || !endpoint.hasObfuscatedPorts()) return;
        sendObfuscated(socket, endpoint, builder.apply(challenge, endpoint.address()), peerKey);
    }

    /** To the server's TCP+14, obfuscated with its key as a request. */
    private void sendObfuscated(DatagramSocket socket, Endpoint endpoint, byte[] message, int peerKey) {
        send(socket, endpoint.address(), endpoint.tcpPort() + 14,
                ServerObfuscation.encode(message, peerKey, ServerObfuscation.REQUEST));
    }

    private DatagramSocket main(DatagramSocket fallback) {
        DatagramSocket socket = mainSocket.get();
        return socket != null ? socket : fallback;
    }

    private void reject(Endpoint endpoint, String reason, long now) {
        pending.remove(endpoint);
        obfuscatedSessions.remove(endpoint);
        statusChallenges.remove(endpoint);
        verified.remove(endpoint);
        serverStatuses.remove(endpoint);
        probeSentAt.remove(endpoint);
        candidates.remove(endpoint);
        if (rejected.size() >= MAX_PEERS) rejected.clear();
        rejected.put(endpoint, now);
        System.getLogger(ServerGossip.class.getName()).log(System.Logger.Level.WARNING,
                "Rejected server peer {0}:{1}: {2}", endpoint.address().getHostAddress(), endpoint.tcpPort(), reason);
    }

    /**
     * OP_SERVER_LIST_RES to the socket the request came from, obfuscated as the request was: with the random part of
     * the crypt ping that socket sent (Lugdunum's), otherwise with {@code answerObfuscator}.
     *
     * @param endpoint the requesting server, left out of the list
     */
    private void sendList(DatagramSocket socket, InetAddress to, int toPort, Endpoint endpoint,
                          Collection<VerifiedPeer> peers, InetAddress publicIp, int localTcpPort,
                          UnaryOperator<byte[]> answerObfuscator) {
        if (endpoint == null) return;
        if (blocked(endpoint.address()) || blocked(to)) return;
        List<Endpoint> entries = new ArrayList<>();
        if (publicIp instanceof Inet4Address && !publicIp.isAnyLocalAddress() && !publicIp.isLoopbackAddress()
                && !blocked(publicIp)) {
            entries.add(new Endpoint(publicIp, localTcpPort));
        }
        peers.stream().map(VerifiedPeer::endpoint).filter(peer -> !peer.equals(endpoint))
                .filter(peer -> !blocked(peer.address()))
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
        if (answerObfuscator != null) {
            ReceivedPing ping = receivedPings.get(new InetSocketAddress(to, toPort));
            data = ping != null
                    ? ServerObfuscation.encode(data, ping.randomPart(), ServerObfuscation.ANSWER)
                    : answerObfuscator.apply(data);
        }
        send(socket, to, toPort, data);
    }

    /** True when the answer starts with {@code challenge}, the one of the request in progress. */
    private static boolean hasChallenge(byte[] packet, int challenge) {
        return packet.length >= 6
                && ByteBuffer.wrap(packet, 2, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() == challenge;
    }

    /**
     * Parses OP_GLOBSERVSTATRES like eMule's CUDPSocket: {@code <challenge 4><users 4><files 4>}, then, depending on
     * the length, {@code <maxUsers 4>}, {@code <softFiles 4><hardFiles 4>}, {@code <udpFlags 4>},
     * {@code <lowIdUsers 4>} and {@code <udpObfuscationPort 2><tcpObfuscationPort 2><udpKey 4>}. Servers append
     * further data (e.g. their IP), and only send the extended fields to a {@code 0x55AA} challenge.
     * The soft and hard file limits apply to the files shared by one client, not to the server total.
     */
    static Status validateStatus(byte[] packet, int challenge) {
        if (packet.length < MIN_STATUS_LENGTH || (packet[0] & 0xFF) != PROTOCOL
                || (packet[1] & 0xFF) != OP_GLOBSERVSTATRES)
            return null;
        ByteBuffer data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        data.position(2);
        if (data.getInt() != challenge) return null;
        long users = Integer.toUnsignedLong(data.getInt());
        long files = Integer.toUnsignedLong(data.getInt());
        long maxUsers = Integer.toUnsignedLong(data.getInt());
        long softFiles = 0;
        long hardFiles = 0;
        long lowIdUsers = 0;
        int udpKey = 0;
        if (data.remaining() >= 8) {
            softFiles = Integer.toUnsignedLong(data.getInt());
            hardFiles = Integer.toUnsignedLong(data.getInt());
        }
        if (data.remaining() >= 4) data.getInt(); // UDP flags
        if (data.remaining() >= 4) lowIdUsers = Integer.toUnsignedLong(data.getInt());
        if (data.remaining() >= 8) {
            data.getShort(); // UDP obfuscation port
            data.getShort(); // TCP obfuscation port
            udpKey = data.getInt();
        }
        if (maxUsers == 0 || maxUsers > 100_000_000L || users > maxUsers || lowIdUsers > users) return null;
        return new Status(udpKey, users, files, softFiles, hardFiles, maxUsers, lowIdUsers);
    }

    static String validateDescription(byte[] packet, int challenge) {
        if (packet.length < 6 || (packet[0] & 0xFF) != PROTOCOL || (packet[1] & 0xFF) != OP_SERVER_DESC_RES)
            return null;
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
        if (packet.length < 3 || (packet[0] & 0xFF) != PROTOCOL || (packet[1] & 0xFF) != OP_SERVER_LIST_RES)
            return null;
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
        return parseEndpoints(configuredPeers, null);
    }

    static Collection<Endpoint> parseEndpoints(String configuredPeers, IPFilter ipFilter) {
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
                if (ipFilter == null || !ipFilter.isServerBlocked(address)) {
                    endpoints.put(new Endpoint(address, port), Boolean.TRUE);
                }
                if (endpoints.size() > MAX_PEERS) {
                    throw new IllegalArgumentException("serverPeers exceeds the maximum of " + MAX_PEERS + " entries");
                }
            } catch (UnknownHostException | NumberFormatException e) {
                throw new IllegalArgumentException("Invalid serverPeers endpoint: " + entry, e);
            }
        }
        return endpoints.keySet();
    }

    /**
     * The server announced by an OP_SERVER_LIST_REQ: the sender's own address, from its main UDP port (TCP+4) when
     * the request is plain. An obfuscated one comes from the socket of the server's crypt ping (TCP+14 for Lugdunum).
     */
    private Endpoint readAnnouncedEndpoint(byte[] packet, InetAddress source, int sourcePort, boolean obfuscated) {
        byte[] ip = {packet[2], packet[3], packet[4], packet[5]};
        int tcpPort = Short.toUnsignedInt(ByteBuffer.wrap(packet, 6, 2).order(ByteOrder.LITTLE_ENDIAN).getShort());
        if (!java.util.Arrays.equals(ip, source.getAddress()) || tcpPort < 1 || tcpPort > 65531
                || (!obfuscated && sourcePort != tcpPort + 4)) {
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

    /** OP_SERVER_LIST_REQ payload as Lugdunum sends it: {@code <ip 4><tcp port 2><challenge 4>}. */
    private static byte[] registration(InetAddress publicIp, int tcpPort) {
        return ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
                .put(publicIp.getAddress()).putShort((short) tcpPort).putInt(nextChallenge()).array();
    }

    private static InetAddress localPublicIp(InetAddress configuredIp, InetAddress source) {
        return configuredIp instanceof Inet4Address && !configuredIp.isAnyLocalAddress()
                ? configuredIp
                : source;
    }

    private void send(DatagramSocket socket, Endpoint endpoint, byte[] packet) {
        send(socket, endpoint.address(), endpoint.udpPort(), packet);
    }

    private void send(DatagramSocket socket, InetAddress address, int port, byte[] packet) {
        if (socket == null) return;
        try {
            socket.send(new DatagramPacket(packet, packet.length, address, port));
        } catch (java.io.IOException e) {
            System.getLogger(ServerGossip.class.getName()).log(System.Logger.Level.DEBUG,
                    "Could not send server gossip packet to {0}:{1}: {2}",
                    address.getHostAddress(), port, e.getMessage());
        }
    }

    private synchronized boolean addCandidate(Endpoint endpoint) {
        return addCandidate(endpoint, ServerSource.GOSSIP);
    }

    private synchronized boolean addCandidate(Endpoint endpoint, ServerSource source) {
        if (endpoint == null || rejected.containsKey(endpoint) || candidates.size() >= MAX_PEERS) return false;
        if (blocked(endpoint.address())) return false;
        if (candidates.putIfAbsent(endpoint, Boolean.TRUE) != null) return false;
        sources.put(endpoint, source);
        return true;
    }

    private void expirePending(long now) {
        pending.entrySet().removeIf(entry -> {
            if (now - entry.getValue().startedAtMillis() <= TRANSACTION_TIMEOUT_MILLIS) return false;
            Endpoint endpoint = entry.getKey();
            Pending transaction = entry.getValue();
            // Only unanswered status probes count: most servers never answer OP_SERVER_LIST_REQ, and the status
            // they returned just before stays valid
            if (transaction.stage() == Stage.STATUS) {
                int misses = missedStatusProbes.merge(endpoint, 1, Integer::sum);
                if (misses >= 3) {
                    verified.remove(endpoint);
                    serverStatuses.remove(endpoint);
                }
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

    private static int nextStatusChallenge() {
        return EXTENDED_STATUS_CHALLENGE | (RANDOM.nextInt() & 0xFFFF);
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
