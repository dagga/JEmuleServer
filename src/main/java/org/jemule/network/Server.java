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

import org.jemule.config.ServerConfig;
import org.jemule.core.ClientFactory;
import org.jemule.core.ClientRegistry;
import org.jemule.core.ClientState;
import org.jemule.core.DatabaseManager;
import org.jemule.core.FileIndex;
import org.jemule.core.event.ClientEvent;
import org.jemule.core.event.EventManager;
import org.jemule.core.event.FileEvent;
import org.jemule.security.FloodProtector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.SQLException;
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
    private final ExecutorService executor;
    private final DatabaseManager db;
    private final EventManager eventManager;
    private final ClientFactory clientFactory;
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
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        
        DatabaseManager dbMgr = null;
        try {
            dbMgr = new DatabaseManager(
                    config.databasePath(),
                    config.cbFailureRateThreshold(),
                    config.cbMinimumNumberOfCalls(),
                    config.cbWaitDurationInSeconds()
            );
        } catch (SQLException e) {
            log.error("Failed to initialize database: {}", e.getMessage());
        }
        this.db = dbMgr;
        this.fileIndex = new FileIndex(db, eventManager);
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
        try (ServerSocket ss = new ServerSocket(config.port())) {
            ss.setSoTimeout(1000); // Allow checking the 'running' flag periodically
            log.info("JEmuleServer listening on port {}", config.port());
            log.info("Virtual Threads ready for 50k+ concurrent clients");

            startUdpResponder();

            while (running && !ss.isClosed()) {
                try {
                    Socket c = ss.accept();
                    c.setTcpNoDelay(true);
                    executor.submit(new ClientHandler(c, config, registry, fileIndex, floodProtector, eventManager, clientFactory));
                } catch (java.net.SocketTimeoutException e) {
                    // Just a timeout, go back to loop start and check 'running'
                } catch (IOException e) {
                    if (running) log.error("Accept error: {}", e.getMessage());
                }
            }
        }
    }

    private void startUdpResponder() {
        executor.submit(() -> {
            while (running) {
                try (java.net.DatagramSocket ds = new java.net.DatagramSocket(config.port())) {
                    ds.setSoTimeout(1000);
                    log.info("UDP Responder listening on port {}", config.port());
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
                }
            }
        });
    }

    private void handleUdp(java.net.DatagramSocket ds, java.net.DatagramPacket p) throws IOException {
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

            ByteBuffer resp = ByteBuffer.allocate(2 + 16 + 1 + sources.size() * 6).order(ByteOrder.LITTLE_ENDIAN);
            resp.put(Packet.PROTOCOL_ED2K);
            resp.put((byte) 0x9B); // OP_GLOBFOUNDSOURCES
            resp.put(hashBytes);
            resp.put((byte) Math.min(sources.size(), 255));
            for (var s : sources) {
                resp.putInt(ClientState.ipToInt(s.address()));
                resp.putShort((short) s.port());
            }

            ds.send(new java.net.DatagramPacket(resp.array(), resp.position(), p.getAddress(), p.getPort()));
        }
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
