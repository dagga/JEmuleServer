/*
 * JEmuleServer - An eMule server.
 * Copyright (C) 2026 Nicolas Hernandez (hernicatgmail.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */


package org.jemule.network;

import org.jemule.Main;
import org.jemule.config.ServerConfig;
import org.jemule.core.*;
import org.jemule.protocol.Tag;
import org.jemule.core.event.ClientEvent;
import org.jemule.core.event.EventManager;
import org.jemule.core.event.FileEvent;
import org.jemule.core.event.EventStatistics;
import org.jemule.kad.KadNode;
import org.jemule.kad.KadSearchBridge;
import org.jemule.security.ChildAbuseFilter;
import org.jemule.security.FakeFileDetector;
import org.jemule.security.FloodProtector;
import org.jemule.security.IPFilter;
import org.jemule.security.IPFilterUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main server class that coordinates networking, security, and data management.
 * Uses Java 21 Virtual Threads to handle massive concurrency.
 */
public class Server {
    private static final Logger log = LoggerFactory.getLogger(Server.class);
    private final ServerConfig config;
    private final ServerGossip serverGossip;
    private final ServerSearchBridge serverSearchBridge;
    private final ClientRegistry registry = new ClientRegistry();
    private final FileIndex fileIndex;
    private final FloodProtector floodProtector;
    private final IPFilter ipFilter;
    private final IPFilterUpdater ipFilterUpdater;
    private final FakeFileDetector fakeFileDetector;
    private final ExecutorService executor;
    private final DatabaseManager db;
    private final byte[] serverHash = new byte[16];

    // Map of UDP sockets bound by startUdpResponder so other components can send via the same source port
    private static final java.util.concurrent.ConcurrentHashMap<Integer, java.net.DatagramSocket> boundUdpSockets = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Send a UDP packet using a previously-bound DatagramSocket for the given local port.
     * Returns true if the packet was sent, false if no bound socket was available or send failed.
     */
    public static boolean sendUdpFromBoundPort(int localPort, byte[] data, InetAddress destAddr, int destPort) {
        java.net.DatagramSocket ds = boundUdpSockets.get(localPort);
        if (ds == null) {
            log.debug("No bound UDP socket for local port {}", localPort);
            return false;
        }
        try {
            ds.send(new java.net.DatagramPacket(data, data.length, destAddr, destPort));
            return true;
        } catch (IOException e) {
            log.debug("Failed to send UDP from bound port {} to {}:{} - {}", localPort, destAddr, destPort, e.getMessage());
            return false;
        }
    }

    private final EventManager eventManager;
    private final EventStatistics eventStatistics = new EventStatistics();
    private final ClientFactory clientFactory;
    private final AdminInterface admin;
    private final AdminWebPanel adminWebPanel;
    private final InetAddress publicIp;
    private final KadNode kadNode;
    private volatile KadSearchBridge kadSearchBridge;
    private volatile boolean running = true;

    public byte[] getServerHash() {
        return serverHash;
    }

    public KadSearchBridge getKadSearchBridge() {
        return kadSearchBridge;
    }

    public void setKadSearchBridge(KadSearchBridge kadSearchBridge) {
        this.kadSearchBridge = kadSearchBridge;
    }

    /**
     * Active bridge for keyword searches against verified server peers.
     */
    public ServerSearchBridge getServerSearchBridge() {
        return serverSearchBridge;
    }

    /**
     * Adds server endpoints explicitly reported by a connected client for normal gossip verification.
     */
    public int onClientReportedServerList(byte[] data) {
        return serverGossip.addClientReportedServerList(data);
    }

    public byte[] getClientServerList() {
        return serverGossip.clientServerList(publicIp, config.port());
    }

    /**
     * The embedded Kademlia node, or null when {@code kadEnabled=false}.
     */
    public KadNode getKadNode() {
        return kadNode;
    }

    /**
     * Called once a client has logged in: HighID clients bootstrap the Kad routing table.
     */
    public void onClientLoggedIn(ClientState client) {
        KadNode node = kadNode;
        if (node != null) {
            node.onClientConnected(client);
        }
    }

    /**
     * Initializes the server with configuration and core components.
     *
     * @param config        The server configuration.
     * @param clientFactory The factory for creating client states.
     */
    public Server(ServerConfig config, ClientFactory clientFactory) {
        this.config = config;
        this.serverGossip = new ServerGossip(config.serverPeers());
        this.clientFactory = clientFactory;
        this.eventManager = new EventManager();
        setupDefaultListeners();
        this.floodProtector = new FloodProtector(config.floodMaxRequestsPerSecond());
        this.ipFilter = new IPFilter();
        if (config.ipFilterPath() != null && !config.ipFilterPath().isBlank()) {
            this.ipFilter.loadFromFile(config.ipFilterPath());
        }
        this.ipFilterUpdater = createIpFilterUpdater(config.ipFilterPath());
        this.fakeFileDetector = new FakeFileDetector();
        this.fakeFileDetector.setEnabled(config.fakeFileDetectionEnabled());
        this.executor = Executors.newVirtualThreadPerTaskExecutor();

        DatabaseManager dbMgr = null;
        try {
            dbMgr = new DatabaseManager(
                    config.databasePath(),
                    config.cbFailureRateThreshold(),
                    config.cbMinimumNumberOfCalls(),
                    config.cbWaitDurationInSeconds()
            );
            this.fakeFileDetector.setDatabaseManager(dbMgr);
            // Bans apply even when the fake file heuristics are disabled
            List<String> banned = dbMgr.loadBannedHashes();
            banned.forEach(this.fakeFileDetector::addBannedHash);
            log.info("Loaded {} banned hashes from database", banned.size());
        } catch (SQLException e) {
            log.error("Failed to initialize database: {}", e.getMessage());
        }

        this.db = dbMgr;
        ChildAbuseFilter abuseFilter = ChildAbuseFilter.load(config.childAbuseKeywordsPath());
        this.fileIndex = new FileIndex(db, eventManager, abuseFilter);
        this.serverSearchBridge = new ServerSearchBridge(serverGossip::verifiedPeers,
                () -> boundUdpSockets.get(config.port() + 4), fileIndex, fakeFileDetector);
        this.kadNode = config.kad().enabled()
                ? new KadNode(config.kad(), config.port(), db, fileIndex, abuseFilter, fakeFileDetector)
                : null;
        this.admin = new AdminInterface(this, registry, fileIndex, ipFilter, fakeFileDetector);
        this.adminWebPanel = new AdminWebPanel(this);
        this.publicIp = resolvePublicIp(config.publicIp());

        // Generate a stable server hash based on public IP and port
        byte[] ipPort = (publicIp.getHostAddress() + ":" + config.port()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] md5;
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            md5 = digest.digest(ipPort);
        } catch (java.security.NoSuchAlgorithmException e) {
            md5 = new byte[16];
        }
        System.arraycopy(md5, 0, serverHash, 0, 16);
    }

    private IPFilterUpdater createIpFilterUpdater(String filterPath) {
        if (filterPath == null || filterPath.isBlank()) return null;
        try {
            IPFilterUpdater updater = new IPFilterUpdater(ipFilter, Path.of(filterPath),
                    IPFilterUpdater.bundledSources());
            updater.start();
            return updater;
        } catch (IOException | IllegalArgumentException e) {
            log.error("Could not start IP filter updates for {}: {}", filterPath, e.getMessage());
            return null;
        }
    }

    /**
     * The IPv4 address announced to the clients (OP_SERVERIDENT, server lists): ed2k carries only 4-byte addresses,
     * so an IPv6 address is never returned.
     */
    static InetAddress resolvePublicIp(String configIp) {
        if (configIp != null && !configIp.isBlank()) {
            try {
                InetAddress configured = InetAddress.getByName(configIp);
                if (configured instanceof java.net.Inet4Address) return configured;
                log.warn("publicIp {} is not an IPv4 address (ed2k announces IPv4 only), falling back to auto-detect", configIp);
            } catch (Exception e) {
                log.warn("Invalid publicIp in config ({}), falling back to auto-detect", configIp);
            }
        }
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                for (var iface : Collections.list(interfaces)) {
                    if (iface.isLoopback() || !iface.isUp()) continue;
                    var addrs = iface.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress a = addrs.nextElement();
                        if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                            log.info("Auto-detected public IPv4: {} (interface: {})", a.getHostAddress(), iface.getName());
                            return a;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to auto-detect public IP: {}", e.getMessage());
        }
        try {
            InetAddress local = InetAddress.getLocalHost();
            if (local instanceof java.net.Inet4Address && !local.isLoopbackAddress()) {
                log.info("Using InetAddress.getLocalHost(): {}", local.getHostAddress());
                return local;
            }
        } catch (Exception ignored) {
        }
        log.warn("Could not resolve public IP, using 127.0.0.1 as fallback");
        try {
            return InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e); // never thrown for a 4-byte address
        }
    }

    public InetAddress getPublicIp() {
        return publicIp;
    }

    public EventStatistics getEventStatistics() {
        return eventStatistics;
    }

    ClientRegistry getClientRegistry() {
        return registry;
    }

    IPFilter getIPFilter() {
        return ipFilter;
    }

    FakeFileDetector getFakeFileDetector() {
        return fakeFileDetector;
    }

    DatabaseManager getDatabaseManager() {
        return db;
    }

    ServerConfig getConfig() {
        return config;
    }

    List<ServerGossip.VerifiedPeer> getVerifiedServerPeers() {
        return List.copyOf(serverGossip.verifiedPeers());
    }

    int getAdminWebPort() {
        return adminWebPanel.port();
    }

    private void setupDefaultListeners() {
        eventManager.subscribeAll(eventStatistics);
        eventManager.subscribeAll(event -> {
            if (event instanceof ClientEvent ce) {
                log.info("[CLIENT] {} - {} ({})", ce.getType(), ce.getUsername(), ce.getClientIp());
            } else if (event instanceof FileEvent fe) {
                log.info("[FILE] {} - {} ({})", fe.getType(), fe.getFileName(), fe.getFileHash());
            } else {
                log.info("[EVENT] {}: {}", event.getType(), event.getMessage());
            }
        });
    }

    /**
     * Starts the TCP server loop and the UDP responder.
     *
     * @throws IOException If the server cannot bind to the configured port.
     */
    public void start() throws IOException {
        // Bind server socket preferring IPv6 wildcard, fall back to IPv4 if not available
        ServerSocket ss = null;
        try {
            ss = new ServerSocket();
            ss.bind(new java.net.InetSocketAddress(java.net.InetAddress.getByName("::"), config.port()));
            ss.setSoTimeout(1000);
            log.info("JEmuleServer listening on port {} (bound to IPv6 wildcard)", config.port());
        } catch (IOException e) {
            // Fallback to IPv4 wildcard
            if (ss != null && !ss.isClosed()) {
                try {
                    ss.close();
                } catch (IOException ignored) {
                }
            }
            ss = new ServerSocket(config.port());
            ss.setSoTimeout(1000);
            log.info("JEmuleServer listening on port {} (bound to IPv4 wildcard)", config.port());
        }

        try {
            log.info("Virtual Threads ready for 50k+ concurrent clients");

            startServerMetLoading();
            startUdpResponder();
            startKadNode();
            startAdminInterface();

            while (running && !ss.isClosed()) {
                try {
                    Socket c = ss.accept();
                    String clientIp = c.getInetAddress().getHostAddress();
                    if (ipFilter.isBlocked(clientIp)) {
                        log.warn("[SECURITY] Blocked TCP connection from {}", clientIp);
                        c.close();
                        continue;
                    }
                    c.setTcpNoDelay(true);
                    executor.submit(new ClientHandler(c, config, registry, fileIndex, floodProtector, fakeFileDetector, eventManager, clientFactory, this));
                } catch (java.net.SocketTimeoutException e) {
                    // Just a timeout, go back to loop start and check 'running'
                } catch (IOException e) {
                    if (running) log.error("Accept error: {}", e.getMessage());
                }
            }
        } finally {
            if (ss != null && !ss.isClosed()) try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void startServerMetLoading() {
        if (config.serverMetUrl().isBlank()) return;
        executor.submit(() -> {
            try {
                int added = ServerMetLoader.load(config.serverMetUrl(), serverGossip);
                log.info("Loaded {} new server candidates from server.met; existing candidates were retained", added);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while loading server.met from {}", config.serverMetUrl());
            } catch (IOException | IllegalArgumentException e) {
                log.warn("Could not load server.met from {}: {}", config.serverMetUrl(), e.getMessage());
            }
        });
    }

    private void startUdpResponder() {
        // Bind UDP responder on both the configured TCP port and the common UDP offset (port+4)
        java.util.Set<Integer> ports = new java.util.LinkedHashSet<>();
        ports.add(config.port());
        if (config.port() <= 0xFFFF - 4) ports.add(config.port() + 4);
        if (config.port() <= 0xFFFF - 12) ports.add(config.port() + 12);

        for (int port : ports) {
            final int bindPort = port;
            executor.submit(() -> {
                while (running) {
                    java.net.DatagramSocket ds = null;
                    try {
                        // Prefer IPv6 wildcard; if unavailable, fallback to IPv4
                        try {
                            ds = new java.net.DatagramSocket(null);
                            ds.bind(new java.net.InetSocketAddress(java.net.InetAddress.getByName("::"), bindPort));
                            log.info("UDP Responder listening on port {} (IPv6 wildcard)", bindPort);
                        } catch (IOException e) {
                            if (ds != null && !ds.isClosed()) {
                                ds.close();
                            }
                            ds = new java.net.DatagramSocket(bindPort);
                            log.info("UDP Responder listening on port {} (IPv4 wildcard)", bindPort);
                        }

                        // Register bound socket so other threads can send via the same source port
                        boundUdpSockets.put(bindPort, ds);

                        ds.setSoTimeout(1000);
                        byte[] buf = new byte[4096];
                        while (running) {
                            try {
                                java.net.DatagramPacket p = new java.net.DatagramPacket(buf, buf.length);
                                ds.receive(p);
                                handleUdp(ds, p);
                            } catch (java.net.SocketTimeoutException e) {
                                // Timeout, check 'running'
                            }
                            if (bindPort == config.port() + 4) {
                                serverGossip.poll(ds, System.currentTimeMillis());
                            }
                        }
                    } catch (IOException e) {
                        if (running) {
                            log.error("UDP Error on port {} (retrying in 5s): {}", bindPort, e.getMessage());
                            try {
                                Thread.sleep(5000);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    } finally {
                        if (ds != null) {
                            boundUdpSockets.remove(bindPort, ds);
                            if (!ds.isClosed()) ds.close();
                        }
                    }
                }
            });
        }
    }

    /**
     * Starts the embedded Kademlia node. The server keeps working on its local index if the Kad UDP port
     * cannot be bound.
     */
    private void startKadNode() {
        if (kadNode == null) {
            log.info("Kademlia node disabled (kadEnabled=false)");
            return;
        }
        try {
            kadNode.start();
            if (kadNode.getConfig().forkSearchEnabled() && kadSearchBridge == null) {
                kadSearchBridge = kadNode.getSearchBridge();
            }
        } catch (IOException e) {
            log.error("Kad UDP port {} unavailable, Kademlia disabled: {}", kadNode.getUdpPort(), e.getMessage());
        }
    }

    private void startAdminInterface() {
        Thread adminThread = new Thread(admin, "AdminInterface");
        adminThread.setDaemon(true);
        adminThread.start();
        if (config.adminPort() > 0) {
            Thread.ofVirtual().name("AdminPort").start(this::serveAdminPort);
        }
        if (config.adminWebPort() > 0) {
            try {
                adminWebPanel.start(config.adminWebPort());
            } catch (IOException e) {
                log.error("Admin web panel port {} unavailable: {}", config.adminWebPort(), e.getMessage());
            }
        }
    }

    /**
     * Admin console over TCP, for a server running as a service without a terminal ("nc 127.0.0.1 &lt;adminPort&gt;").
     * Bound to the loopback address only: there is no authentication, the access is that of the local machine.
     */
    private void serveAdminPort() {
        try (ServerSocket adminSocket = new ServerSocket(config.adminPort(), 5, InetAddress.getLoopbackAddress())) {
            log.info("Admin console listening on {}:{}", adminSocket.getInetAddress().getHostAddress(), config.adminPort());
            while (running) {
                Socket s = adminSocket.accept();
                Thread.ofVirtual().name("AdminSession").start(() -> {
                    try (s; var out = new java.io.PrintStream(s.getOutputStream(), true, java.nio.charset.StandardCharsets.UTF_8)) {
                        log.info("Admin session opened from {}", s.getRemoteSocketAddress());
                        new AdminInterface(admin, out).session(new java.io.BufferedReader(
                                new java.io.InputStreamReader(s.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)), true);
                    } catch (IOException e) {
                        log.debug("Admin session closed: {}", e.getMessage());
                    }
                });
            }
        } catch (IOException e) {
            log.error("Admin port {} unavailable: {}", config.adminPort(), e.getMessage());
        }
    }

    /**
     * Destination of the answers to one UDP request; answers are obfuscated when {@code key != 0}.
     */
    private record UdpReply(java.net.DatagramSocket socket, InetAddress address, int port, int key,
                            boolean serverObfuscated) {
        private UdpReply(java.net.DatagramSocket socket, InetAddress address, int port, int key) {
            this(socket, address, port, key, false);
        }
    }

    private void handleUdp(java.net.DatagramSocket ds, java.net.DatagramPacket p) throws IOException {
        String clientIp = p.getAddress().getHostAddress();
        if (ipFilter.isBlocked(clientIp)) {
            log.warn("[SECURITY] Blocked UDP packet from {}", clientIp);
            return;
        }
        if (p.getLength() < 2) return;
        byte[] recv = java.util.Arrays.copyOfRange(p.getData(), p.getOffset(), p.getOffset() + p.getLength());
        if (log.isDebugEnabled()) {
            log.debug("UDP recv {} bytes from {}:{} - {}", recv.length, p.getAddress(), p.getPort(), hex(recv, recv.length));
        }

        int replyKey = 0;
        boolean serverObfuscated = false;
        if (recv[0] != Packet.PROTOCOL_ED2K) {
            // Obfuscated packet (CEncryptedDatagramSocket::EncryptSendServer), keyed with this client's server key
            int serverKey = org.jemule.security.UdpObfuscation.serverKeyFor(p.getAddress());
            byte[] plain = org.jemule.security.UdpObfuscation.decryptFromClient(recv, serverKey);
            if (plain != null && plain.length >= 2 && plain[0] == Packet.PROTOCOL_ED2K) {
                recv = plain;
                replyKey = serverKey;
            } else {
                byte[] serverPlain = org.jemule.security.ServerObfuscation.decode(
                        recv, org.jemule.security.UdpObfuscation.serverKeyFor(p.getAddress()));
                if (serverPlain != null && serverPlain.length >= 2 && serverPlain[0] == Packet.PROTOCOL_ED2K) {
                    recv = serverPlain;
                    serverObfuscated = true;
                } else if (recv.length >= 4 && recv.length < 4 + 16 && ds.getLocalPort() == config.port() + 12) {
                    // "Crypt ping" (CServerList::ServerStats): raw <challenge 4><random 0-15> sent to port+12;
                    // the OP_GLOBSERVSTATRES answer is obfuscated with the challenge as base key.
                    int challenge = ByteBuffer.wrap(recv, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                    UdpReply reply = new UdpReply(ds, p.getAddress(), p.getPort(), challenge);
                    sendUdp(reply, buildGlobServStatRes(challenge, p.getAddress()), "STAT (crypt ping)");
                    return;
                } else {
                    log.debug("Dropping undecodable UDP packet ({} bytes) from {}", recv.length, p.getAddress());
                    return;
                }
            }
        }
        dispatchUdp(new UdpReply(ds, p.getAddress(), p.getPort(), replyKey, serverObfuscated), recv);
    }

    private void dispatchUdp(UdpReply reply, byte[] recv) throws IOException {
        byte opcode = recv[1];
        if (opcode == (byte) 0x96) { // OP_GLOBSERVSTATREQ <challenge 4>
            if (recv.length < 6) return;
            int challenge = ByteBuffer.wrap(recv, 2, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            sendUdp(reply, buildGlobServStatRes(challenge, reply.address()), "STAT");
        } else if (opcode == (byte) 0xA2) { // OP_SERVER_DESC_REQ [<challenge 4>]
            int challenge = recv.length >= 6 ? ByteBuffer.wrap(recv, 2, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() : 0;
            sendUdp(reply, buildServerDescPacket(challenge, reply.address()), "DESC");
        } else if (serverGossip.handle(reply.socket(), reply.address(), reply.port(), recv,
                reply.serverObfuscated(), publicIp, config.port(), System.currentTimeMillis())) {
            return;
        } else if (serverSearchBridge.handle(reply.address(), reply.port(), recv)) {
            return;
        } else if (opcode == (byte) 0x98 || opcode == (byte) 0x92 || opcode == (byte) 0x90) {
            handleGlobalSearch(reply, opcode, recv);
        } else if (opcode == (byte) 0x9A) { // OP_GLOBGETSOURCES: <hash 16>[<hash 16>...] (up to 35 with SRV_UDPFLG_EXT_GETSOURCES)
            int hashCount = (recv.length - 2) / 16;
            if (hashCount < 1) return;
            log.debug("UDP Source Request from {} for {} file(s)", reply.address(), hashCount);

            // The client accepts several <hash><count>(<ID><port>)* blocks in one datagram, each further block
            // being preceded by its own <0xE3><0x9B> header (CUDPSocket::ProcessPacket, OP_GLOBFOUNDSOURCES).
            java.io.ByteArrayOutputStream datagram = new java.io.ByteArrayOutputStream();
            for (int h = 0; h < hashCount; h++) {
                byte[] hashBytes = java.util.Arrays.copyOfRange(recv, 2 + h * 16, 2 + (h + 1) * 16);
                String hash = org.jemule.network.handler.HandlerUtils.bytesToHashString(hashBytes);

                var sources = org.jemule.network.handler.SourceHandler.reachableSources(
                        fileIndex.getSources(hash, null, config.maxSourcesPerFile()));
                if (sources.isEmpty()) continue;

                // ed2k IDs are IPv4 addresses (or LowIDs): an IPv6-only client (ID 0) is never handed out.
                byte[] block = org.jemule.network.handler.SourceHandler.encodeFoundSources(hashBytes, sources);
                if (datagram.size() > 0 && datagram.size() + 2 + block.length > MAX_UDP_PAYLOAD) {
                    sendUdp(reply, datagram.toByteArray(), "SRCS");
                    datagram.reset();
                }
                datagram.write(Packet.PROTOCOL_ED2K);
                datagram.write(0x9B); // OP_GLOBFOUNDSOURCES
                datagram.writeBytes(block);
            }
            if (datagram.size() > 0) {
                sendUdp(reply, datagram.toByteArray(), "SRCS");
            }
        }
    }

    /**
     * OP_GLOBSERVSTATRES as read by CUDPSocket::ProcessPacket:
     * <challenge 4><users 4><files 4><maxusers 4><softfiles 4><hardfiles 4><UDP flags 4><lowid users 4>
     * <UDP obfuscation port 2><TCP obfuscation port 2><server UDP key 4>. The key is specific to the client's IP.
     * Obfuscated UDP is received on port+12 (where eMule also sends its crypt pings); obfuscated TCP connections are
     * detected on the main port.
     */
    private byte[] buildGlobServStatRes(int challenge, InetAddress client) {
        ByteBuffer resp = ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN);
        resp.put(Packet.PROTOCOL_ED2K);
        resp.put((byte) 0x97); // OP_GLOBSERVSTATRES
        resp.putInt(challenge);
        resp.putInt(registry.size());
        resp.putInt(fileIndex.fileCount());
        resp.putInt(config.maxUsers());
        resp.putInt(1000000); // SoftFiles (Standard Lugdunum value)
        resp.putInt(2000000); // HardFiles (Standard Lugdunum value)
        resp.putInt(Tag.SERVER_UDP_FLAGS);
        resp.putInt(registry.lowIdCount());
        resp.putShort((short) udpObfuscationPort()); // CServer::SetObfuscationPortUDP
        resp.putShort((short) config.port());        // CServer::SetObfuscationPortTCP
        resp.putInt(org.jemule.security.UdpObfuscation.serverKeyFor(client)); // server UDP key for this client
        return resp.array();
    }

    /**
     * Port receiving obfuscated UDP packets (also bound for eMule's crypt pings, sent to TCP port + 12).
     */
    private int udpObfuscationPort() {
        return config.port() + 12;
    }

    /**
     * eMule keeps at most MAX_RESULTS (100) results of a global search per server.
     */
    private static final int MAX_GLOBAL_SEARCH_RESULTS = 100;

    /**
     * Global (UDP) search sent by CSearchResultsWnd to every server of the client's list:
     * OP_GLOBSEARCHREQ (0x98) and OP_GLOBSEARCHREQ2 (0x92) carry the search tree, OP_GLOBSEARCHREQ3 (0x90)
     * prefixes it with a tag list whose CT_SERVER_UDPSEARCH_FLAGS (0x0E) announces large file support.
     * Each result is sent as an OP_GLOBSEARCHRES (0x99) block; several blocks, each with its own E3/99 header,
     * may share one datagram (CUDPSocket::ProcessPacket).
     */
    private void handleGlobalSearch(UdpReply reply, byte opcode, byte[] recv) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(recv, 2, recv.length - 2).slice().order(ByteOrder.LITTLE_ENDIAN);
        boolean largeFiles = false;
        List<FileMetadata> results;
        try {
            if (opcode == (byte) 0x90) {
                for (Tag t : Tag.readList(buf)) {
                    if (t.name().equals("\u000E") && t.isInt() && (t.unsignedValue() & 0x01) != 0) { // SRVCAP_UDP_NEWTAGS_LARGEFILES
                        largeFiles = true;
                    }
                }
            }
            SearchQuery query = SearchQuery.parse(buf.slice());
            results = fileIndex.searchComplex(query, Math.min(config.maxSearchResults(), MAX_GLOBAL_SEARCH_RESULTS));
        } catch (RuntimeException e) {
            log.debug("Malformed UDP search request from {}: {}", reply.address(), e.getMessage());
            return;
        }

        java.io.ByteArrayOutputStream datagram = new java.io.ByteArrayOutputStream();
        for (FileMetadata m : results) {
            if (!largeFiles && m.size() > 0xFFFFFFFFL) continue; // the requester cannot handle files > 4 GB
            byte[] item = org.jemule.network.handler.SearchHandler.encodeSearchResult(m);
            if (datagram.size() > 0 && datagram.size() + 2 + item.length > MAX_UDP_PAYLOAD) {
                sendUdp(reply, datagram.toByteArray(), "SEARCH");
                datagram.reset();
            }
            datagram.write(Packet.PROTOCOL_ED2K);
            datagram.write(0x99); // OP_GLOBSEARCHRES
            datagram.writeBytes(item);
        }
        if (datagram.size() > 0) {
            sendUdp(reply, datagram.toByteArray(), "SEARCH");
        }
    }

    /**
     * Keeps answers below the usual path MTU (the client reads up to 5000 bytes per datagram).
     */
    private static final int MAX_UDP_PAYLOAD = 1300;

    private void sendUdp(UdpReply reply, byte[] plain, String what) throws IOException {
        byte[] out;
        if (reply.serverObfuscated()) {
            int peerKey = serverGossip.keyFor(reply.address(), reply.port());
            out = peerKey != 0 ? org.jemule.security.ServerObfuscation.encode(plain, peerKey) : plain;
        } else {
            out = reply.key() != 0 ? org.jemule.security.UdpObfuscation.encryptForClient(plain, reply.key()) : plain;
        }
        if (log.isDebugEnabled()) {
            log.debug("UDP send ({}{}) {} bytes to {}:{} - {}", what,
                    reply.key() != 0 || reply.serverObfuscated() ? ", obfuscated" : "",
                    out.length, reply.address(), reply.port(), hex(plain, plain.length));
        }
        reply.socket().send(new java.net.DatagramPacket(out, out.length, reply.address(), reply.port()));
    }

    // Hex dump helper for UDP debugging
    private static String hex(byte[] data, int len) {
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < Math.min(len, data.length); i++) {
            sb.append(String.format("%02x ", data[i] & 0xFF));
        }
        return sb.toString().trim();
    }

    private byte[] buildServerDescPacket(int challenge, InetAddress client) {
        String sName = Main.SERVER_NAME;
        String sDesc = Main.SERVER_DESCRIPTION;

        int maxFiles = config.maxFiles();
        int maxUsers = config.maxUsers();

        int tcpFlags = Tag.SERVER_TCP_FLAGS;
        int udpFlags = Tag.SERVER_UDP_FLAGS;

        java.util.List<org.jemule.protocol.Tag> tags = new java.util.ArrayList<>();
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_HASH, "\u0011", getServerHash())); // ST_SERVERHASH (mapped to version tag ID in some contexts, but usually hash is first)
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_MAXUSERS, maxUsers));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_SOFTFILES, 1000000));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_HARDFILES, 2000000));

        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_STRING, org.jemule.protocol.Tag.NAME_SERVERNAME, sName));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_STRING, org.jemule.protocol.Tag.NAME_DESCRIPTION, sDesc));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_SERVER_VERSION, org.jemule.Main.ESERVER_VERSION_INT));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_LOWIDUSERS, registry.lowIdCount()));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_UDPFLAGS, udpFlags));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_UDP_KEY, org.jemule.security.UdpObfuscation.serverKeyFor(client)));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_UDP_KEY_IP, (int) ClientState.ipToLong(client))); // IP the key is valid for
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_TCP_OBFUSCATION_PORT, config.port()));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_UDPPORTOBFUSCATION, udpObfuscationPort()));

        ByteBuffer resp = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        resp.put(Packet.PROTOCOL_ED2K);
        resp.put((byte) 0xA3); // OP_SERVER_DESC_RES

        if (challenge != 0 && (challenge & 0xFFFF) == 0xF0FF) {
            // New format: <challenge 4><taglist>
            // Note: challenge must have 0xF0FF in the lower 16 bits to be recognized as "new format" by eMule
            resp.putInt(challenge);
            org.jemule.protocol.Tag.writeList(resp, tags);
        } else {
            // Old format: <name_len 2><name><desc_len 2><desc>
            byte[] nameBytes = sName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] descBytes = sDesc.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            resp.putShort((short) nameBytes.length);
            resp.put(nameBytes);
            resp.putShort((short) descBytes.length);
            resp.put(descBytes);
        }

        resp.flip();
        byte[] outBuf = new byte[resp.remaining()];
        resp.get(outBuf);
        return outBuf;
    }

    /**
     * Exposes the internal file index (primarily for testing).
     */
    public FileIndex getFileIndex() {
        return fileIndex;
    }

    /**
     * Stops the server, shuts down executors, and closes the database.
     */
    public void stop() {
        running = false;
        adminWebPanel.close();
        executor.shutdownNow();
        serverSearchBridge.close();
        if (ipFilterUpdater != null) ipFilterUpdater.close();
        kadSearchBridge = null;
        if (kadNode != null) {
            kadNode.close();
        }
        if (db != null) {
            try {
                db.close();
            } catch (SQLException e) {
                log.error("Error closing database: {}", e.getMessage());
            }
        }
        log.info("Server halted.");
    }
}