/*
 * JEmuleServer - An experimental eMule server.
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
import org.jemule.security.FakeFileDetector;
import org.jemule.security.FloodProtector;
import org.jemule.security.IPFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
    private final ClientRegistry registry = new ClientRegistry();
    private final FileIndex fileIndex;
    private final FloodProtector floodProtector;
    private final IPFilter ipFilter;
    private final FakeFileDetector fakeFileDetector;
    private final ExecutorService executor;
    private final DatabaseManager db;
    private static final int GLOBAL_UDP_KEY = new java.util.Random().nextInt();

    // Map of UDP sockets bound by startUdpResponder so other components can send via the same source port
    private static final java.util.concurrent.ConcurrentHashMap<Integer, java.net.DatagramSocket> boundUdpSockets = new java.util.concurrent.ConcurrentHashMap<>();

    public static int getUdpKey() {
        return GLOBAL_UDP_KEY;
    }

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
    private final ClientFactory clientFactory;
    private final AdminInterface admin;
    private final InetAddress publicIp;
    private volatile boolean running = true;

    /**
     * Initializes the server with configuration and core components.
     *
     * @param config        The server configuration.
     * @param clientFactory The factory for creating client states.
     */
    public Server(ServerConfig config, ClientFactory clientFactory) {
        this.config = config;
        this.clientFactory = clientFactory;
        this.eventManager = new EventManager();
        setupDefaultListeners();
        this.floodProtector = new FloodProtector(config.floodMaxRequestsPerSecond());
        this.ipFilter = new IPFilter();
        if (config.ipFilterPath() != null) {
            this.ipFilter.loadFromFile(config.ipFilterPath());
        }
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
            if (config.fakeFileDetectionEnabled()) {
                List<String> banned = dbMgr.loadBannedHashes();
                banned.forEach(this.fakeFileDetector::addBannedHash);
                log.info("Loaded {} banned hashes from database", banned.size());
            }
        } catch (SQLException e) {
            log.error("Failed to initialize database: {}", e.getMessage());
        }
        this.db = dbMgr;
        this.fileIndex = new FileIndex(db, eventManager);
        this.admin = new AdminInterface(this, registry, fileIndex, ipFilter, fakeFileDetector);
        this.publicIp = resolvePublicIp(config.publicIp());
    }

    private static InetAddress resolvePublicIp(String configIp) {
        if (configIp != null && !configIp.isBlank()) {
            try {
                return InetAddress.getByName(configIp);
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
            if (!local.isLoopbackAddress()) {
                log.info("Using InetAddress.getLocalHost(): {}", local.getHostAddress());
                return local;
            }
        } catch (Exception ignored) {}
        log.warn("Could not resolve public IP, using 127.0.0.1 as fallback");
        return InetAddress.getLoopbackAddress();
    }

    public InetAddress getPublicIp() {
        return publicIp;
    }

    private void setupDefaultListeners() {
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
                try { ss.close(); } catch (IOException ignored) {}
            }
            ss = new ServerSocket(config.port());
            ss.setSoTimeout(1000);
            log.info("JEmuleServer listening on port {} (bound to IPv4 wildcard)", config.port());
        }

        try {
            log.info("Virtual Threads ready for 50k+ concurrent clients");

            startUdpResponder();
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
            if (ss != null && !ss.isClosed()) try { ss.close(); } catch (IOException ignored) {}
        }
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
                            if (ds != null && !ds.isClosed()) { ds.close(); }
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

    private void startAdminInterface() {
        Thread adminThread = new Thread(admin, "AdminInterface");
        adminThread.setDaemon(true);
        adminThread.start();
    }

    private void handleUdp(java.net.DatagramSocket ds, java.net.DatagramPacket p) throws IOException {
        String clientIp = p.getAddress().getHostAddress();
        if (ipFilter.isBlocked(clientIp)) {
            log.warn("[SECURITY] Blocked UDP packet from {}", clientIp);
            return;
        }
        if (p.getLength() < 2) return;
        byte[] data = p.getData();
        byte[] recv = java.util.Arrays.copyOfRange(data, 0, p.getLength());
        log.info("UDP recv {} bytes from {}:{} - {}", p.getLength(), p.getAddress(), p.getPort(), hex(recv, recv.length));
        
        // Handle potentially obfuscated UDP packet
        if ((data[0] & 0xFF) != (Packet.PROTOCOL_ED2K & 0xFF) && 
            (data[0] & 0xFF) != (Packet.PROTOCOL_EMULE & 0xFF)) {
            // For now, if we don't recognize the protocol, we check if it's on the obfuscation port
            if (ds.getLocalPort() == config.port() + 12) {
                 log.debug("Received potential obfuscated UDP packet on port {}, but UDP obfuscation is not fully implemented yet.", ds.getLocalPort());
            }
            return;
        }

        // Spec says UDP packets are: [Protocol] [Opcode] [Data...]
        // [0xE3] [0x96] [Challenge 4 bytes] -> Total 6 bytes
        byte opcode = data[1];
        if (opcode == (byte) 0x96) { // OP_GLOBSERVSTATREQ
            if (p.getLength() < 6) return;
            int challenge = ByteBuffer.wrap(data, 2, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            log.debug("UDP Status Request (0x96) from {} with challenge 0x{}", p.getAddress(), Integer.toHexString(challenge));

            // Response: [Protocol] [Opcode] [Challenge 4][UserCount 4][FileCount 4][MaxUsers 4][SoftFiles 4][HardFiles 4][UDPFlags 4][LowIDUsers 4][UDPPort 2][TCPPort 2][ServerKey 4]
            // Total data size: 4+4+4+4+4+4+4+4+2+2+4 = 40 bytes. Packet size: 1+1+40 = 42 bytes.

            ByteBuffer resp = ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN);
            resp.put(Packet.PROTOCOL_ED2K);
            resp.put((byte) 0x97); // OP_GLOBSERVSTATRES
            resp.putInt(challenge); // Echo challenge
            resp.putInt(registry.size());
            resp.putInt(fileIndex.fileCount());
            resp.putInt(config.maxUsers());
            resp.putInt(1000000); // SoftFiles (Standard Lugdunum value)
            resp.putInt(2000000); // HardFiles (Standard Lugdunum value)
        // UDP Flags (matching server.h)
        int udpFlags = Tag.UDPFLG_EXT_GETSOURCES | Tag.UDPFLG_NEWTAGS | Tag.UDPFLG_UNICODE | Tag.UDPFLG_LARGEFILES | Tag.UDPFLG_UDPOBFUSCATION | Tag.UDPFLG_TCPOBFUSCATION;
        resp.putInt(udpFlags); 
            resp.putInt(registry.lowIdCount()); // LowIDUsers
            resp.putShort((short) (config.port() + 4)); // UDPPort (standard is TCP+4)
            resp.putShort((short) config.port()); // TCPPort
            resp.putInt(getUdpKey()); // ServerKey
            resp.flip();
            byte[] outStat = resp.array();
            log.info("UDP send (STAT) {} bytes to {}:{} - {}", outStat.length, p.getAddress(), p.getPort(), hex(outStat, outStat.length));
            ds.send(new java.net.DatagramPacket(outStat, outStat.length, p.getAddress(), p.getPort()));

            // Not sending server description here to avoid race with subsequent UDP requests (e.g. GET_SOURCES)
            // If needed, client should request OP_SERVER_DESC_REQ (0xA2) which is handled below.
        } else if (opcode == (byte) 0xA2) { // OP_SERVER_DESC_REQ
            int challenge = 0;
            if (p.getLength() >= 6) {
                challenge = ByteBuffer.wrap(data, 2, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            }
            log.debug("UDP Description Request (0xA2) from {} with challenge 0x{}", p.getAddress(), Integer.toHexString(challenge));

            byte[] outDesc = buildServerDescPacket(challenge);
            log.info("UDP send (DESC) {} bytes to {}:{} - {}", outDesc.length, p.getAddress(), p.getPort(), hex(outDesc, outDesc.length));
            ds.send(new java.net.DatagramPacket(outDesc, outDesc.length, p.getAddress(), p.getPort()));
        } else if (opcode == (byte) 0x9A) { // OP_GLOBGETSOURCES
            if (p.getLength() < 18) return;
            log.debug("UDP Source Request from {}", p.getAddress());

            byte[] hashBytes = new byte[16];
            System.arraycopy(data, 2, hashBytes, 0, 16);
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) sb.append(String.format("%02x", b));
            String hash = sb.toString();

            var sources = fileIndex.getSources(hash, null, config.maxSourcesPerFile());
            if (sources.isEmpty()) return;

            // Build both IPv4 and IPv6-aware response. Legacy clients expect IPv4-only response.
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);

        // Protocol + opcode
        dos.writeByte(Packet.PROTOCOL_ED2K);
        dos.writeByte(0x9B); // OP_GLOBFOUNDSOURCES

        // Hash
        dos.write(hashBytes);

        // Separate IPv4-mappable and IPv6-only sources
        List<org.jemule.core.ClientState> ipv4List = new java.util.ArrayList<>();
        List<org.jemule.core.ClientState> ipv6List = new java.util.ArrayList<>();
        for (var s : sources) {
            byte[] addr = s.address().getAddress();
            if (addr.length == 4) ipv4List.add(s);
            else if (addr.length == 16) {
                boolean isV4Mapped = true;
                for (int i = 0; i < 10; i++)
                    if (addr[i] != 0) {
                        isV4Mapped = false;
                        break;
                    }
                if (isV4Mapped && addr[10] == (byte) 0xFF && addr[11] == (byte) 0xFF) ipv4List.add(s);
                else ipv6List.add(s);
            } else {
                ipv6List.add(s);
            }
        }

        // IPv4 section (legacy)
        dos.writeByte((byte) Math.min(ipv4List.size(), 255));
        for (var s : ipv4List) {
            dos.writeInt((int) ClientState.ipToLong(s.address()));
            dos.writeShort((short) s.port());
        }

        byte[] outBuf = baos.toByteArray();
        log.info("UDP send (SRCS) {} bytes to {}:{} - {}", outBuf.length, p.getAddress(), p.getPort(), hex(outBuf, outBuf.length));
        ds.send(new java.net.DatagramPacket(outBuf, outBuf.length, p.getAddress(), p.getPort()));

            // If there are IPv6-only sources, send a separate IPv6 response packet (new opcode 0x9C)
            if (!ipv6List.isEmpty()) {
                ByteBuffer v6buf = ByteBuffer.allocate(2 + 16 + 1 + ipv6List.size() * (16 + 2)).order(ByteOrder.LITTLE_ENDIAN);
                v6buf.put(Packet.PROTOCOL_ED2K);
                v6buf.put((byte) 0x9C); // OP_GLOBFOUNDSOURCES_V6 (extension)
                v6buf.put(hashBytes);
                v6buf.put((byte) Math.min(ipv6List.size(), 255));
                for (var s : ipv6List) {
                    byte[] addr = s.address().getAddress();
                    if (addr.length == 16) {
                        v6buf.put(addr);
                    } else if (addr.length == 4) {
                        // map to IPv4-mapped IPv6
                        v6buf.put(new byte[]{0,0,0,0,0,0,0,0,0,0,(byte)0xFF,(byte)0xFF, addr[0], addr[1], addr[2], addr[3]});
                    } else {
                        v6buf.put(new byte[16]);
                    }
                    v6buf.putShort((short) s.port());
                }
                log.info("UDP send (SRCS_V6) {} bytes to {}:{} - {}", v6buf.position(), p.getAddress(), p.getPort(), hex(v6buf.array(), v6buf.position()));
                ds.send(new java.net.DatagramPacket(v6buf.array(), v6buf.position(), p.getAddress(), p.getPort()));
            }
        }
    }

    // Hex dump helper for UDP debugging
    private static String hex(byte[] data, int len) {
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < Math.min(len, data.length); i++) {
            sb.append(String.format("%02x ", data[i] & 0xFF));
        }
        return sb.toString().trim();
    }

    private byte[] buildServerDescPacket(int challenge) {
        String sName = "JEmuleServer (https://github.com/dagga/JEmuleServer/)";
        String sDesc = "Experimental eMule Server";

        int maxFiles = config.maxFiles();
        int maxUsers = config.maxUsers();

        int tcpFlags = Tag.TCPFLG_COMPRESSION | Tag.TCPFLG_NEWTAGS | Tag.TCPFLG_UNICODE | Tag.TCPFLG_TYPETAGINTEGER | Tag.TCPFLG_LARGEFILES | Tag.TCPFLG_TCPOBFUSCATION | Tag.TCPFLG_UDPOBFUSCATION;
        int udpFlags = Tag.UDPFLG_EXT_GETSOURCES | Tag.UDPFLG_NEWTAGS | Tag.UDPFLG_UNICODE | Tag.UDPFLG_LARGEFILES | Tag.UDPFLG_UDPOBFUSCATION | Tag.UDPFLG_TCPOBFUSCATION;

        java.util.List<org.jemule.protocol.Tag> tags = new java.util.ArrayList<>();
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_STRING, org.jemule.protocol.Tag.NAME_MAXUSERS, String.valueOf(maxUsers)));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_STRING, org.jemule.protocol.Tag.NAME_SOFTFILES, "1000000"));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_STRING, org.jemule.protocol.Tag.NAME_HARDFILES, "2000000"));

        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_STRING, org.jemule.protocol.Tag.NAME_SERVERNAME, sName));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_STRING, org.jemule.protocol.Tag.NAME_DESCRIPTION, sDesc));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_TCP_FLAGS, tcpFlags));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_SERVER_VERSION, 17));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_LOWIDUSERS, registry.lowIdCount()));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_UDPFLAGS, udpFlags));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_UDP_KEY, getUdpKey()));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_UDP_KEY_IP, (int) ClientState.ipToLong(publicIp)));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_TCP_OBFUSCATION_PORT, config.port()));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_UDPPORTOBFUSCATION, config.port() + 12));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_STRING, org.jemule.protocol.Tag.NAME_AUXPORTSLIST, String.valueOf(config.port() + 12)));
        tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_UDP_OBFUSCATION_PORT, config.port() + 12));

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
        executor.shutdownNow();
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