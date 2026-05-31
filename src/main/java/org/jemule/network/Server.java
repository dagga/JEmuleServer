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
import org.jemule.core.event.ClientEvent;
import org.jemule.core.event.EventManager;
import org.jemule.core.event.FileEvent;
import org.jemule.security.FakeFileDetector;
import org.jemule.security.FloodProtector;
import org.jemule.security.IPFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.SQLException;
import java.util.List;
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
    private final EventManager eventManager;
    private final ClientFactory clientFactory;
    private final AdminInterface admin;
    private final int udpKey = new java.util.Random().nextInt();
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
                    executor.submit(new ClientHandler(c, config, registry, fileIndex, floodProtector, fakeFileDetector, eventManager, clientFactory));
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
        executor.submit(() -> {
            while (running) {
                java.net.DatagramSocket ds = null;
                try {
                    // Prefer IPv6 wildcard; if unavailable, fallback to IPv4
                    try {
                        ds = new java.net.DatagramSocket(null);
                        ds.bind(new java.net.InetSocketAddress(java.net.InetAddress.getByName("::"), config.port()));
                        log.info("UDP Responder listening on port {} (IPv6 wildcard)", config.port());
                    } catch (IOException e) {
                        if (ds != null && !ds.isClosed()) { ds.close(); }
                        ds = new java.net.DatagramSocket(config.port());
                        log.info("UDP Responder listening on port {} (IPv4 wildcard)", config.port());
                    }

                    ds.setSoTimeout(1000);
                    byte[] buf = new byte[1024];
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
                        log.error("UDP Error (retrying in 5s): {}", e.getMessage());
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } finally {
                    if (ds != null && !ds.isClosed()) ds.close();
                }
            }
        });
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
        if ((data[0] & 0xFF) != (Packet.PROTOCOL_ED2K & 0xFF)) return;

        // Spec says UDP packets are: [Protocol] [Opcode] [Data...]
        // [0xE3] [0x96] [Challenge 4 bytes] -> Total 6 bytes
        byte opcode = data[1];
        if (opcode == (byte) 0x96) { // OP_GLOBSERVSTATREQ
            if (p.getLength() < 6) return;
            log.debug("UDP Status Request from {}", p.getAddress());

            // Response: [Protocol] [Opcode] [Challenge 4] [UserCount 4] [FileCount 4] [MaxUsers 4] [MaxFiles 4]
            // Standard UDP response does NOT have a size field.

            ByteBuffer resp = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
            resp.put(Packet.PROTOCOL_ED2K);
            resp.put((byte) 0x97); // OP_GLOBSERVSTATRES
            resp.put(data, 2, 4); // Echo challenge
            resp.putInt(registry.size());
            resp.putInt(fileIndex.fileCount());
            resp.putInt(config.maxUsers());
            resp.putInt(config.maxFiles());

            ds.send(new java.net.DatagramPacket(resp.array(), resp.position(), p.getAddress(), p.getPort()));
        } else if (opcode == (byte) 0x95) { // OP_SERVER_DESC_REQ
            if (p.getLength() < 2) return;
            log.debug("UDP Description Request from {}", p.getAddress());

            String sName = "JEmuleServer (https://github.com/dagga/JEmuleServer/)";
            String sVersion = Main.ESERVER_VERSION;
            String sDesc = "NoPedo eMule Server";

            int maxFiles = config.maxFiles();
            int maxUsers = config.maxUsers();

            java.util.List<org.jemule.protocol.Tag> tags = new java.util.ArrayList<>();
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_STRING, org.jemule.protocol.Tag.NAME_NAME, sName));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_STRING, org.jemule.protocol.Tag.NAME_DESCRIPTION, sDesc));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_STRING, org.jemule.protocol.Tag.NAME_VERSION, sVersion));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_MAX_USERS, maxUsers));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_MAX_FILES, maxFiles));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_MAX_USERS_V2, maxUsers));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_SOFT_FILES, maxFiles));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_HARD_FILES, maxFiles));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_PREFERENCE, 0));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_EMULE_VERSION, 0x3C));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_TCP_FLAGS, 0x01 | 0x08 | 0x10 | 0x80 | 0x100 | 0x400));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_AUX_PORT, config.port()));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_LOWID_USERS, registry.lowIdCount()));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_UDP_FLAGS, 0x01 | 0x08 | 0x10 | 0x100 | 0x400));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_UDP_KEY, udpKey));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_UDP_KEY_IP,
                    ByteBuffer.wrap(ds.getLocalAddress().getAddress()).order(ByteOrder.LITTLE_ENDIAN).getInt()));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_TCP_OBFUSCATION_PORT, config.port()));
            tags.add(new org.jemule.protocol.Tag(org.jemule.protocol.Tag.TYPE_INTEGER, org.jemule.protocol.Tag.NAME_UDP_OBFUSCATION_PORT, config.port()));

            ByteBuffer resp = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN);
            resp.put(Packet.PROTOCOL_ED2K);
            resp.put((byte) 0x95);
            resp.putShort((short) config.port());
            org.jemule.protocol.Tag.writeList(resp, tags);
            resp.flip();

            byte[] outBuf = new byte[resp.remaining()];
            resp.get(outBuf);
            ds.send(new java.net.DatagramPacket(outBuf, outBuf.length, p.getAddress(), p.getPort()));
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
                dos.writeInt(ClientState.ipToInt(s.address()));
                dos.writeShort((short) s.port());
            }

            byte[] outBuf = baos.toByteArray();
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
                ds.send(new java.net.DatagramPacket(v6buf.array(), v6buf.position(), p.getAddress(), p.getPort()));
            }
        }
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
