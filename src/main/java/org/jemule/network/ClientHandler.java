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
import org.jemule.core.FileIndex;
import org.jemule.core.event.ClientEvent;
import org.jemule.core.event.EventManager;
import org.jemule.network.handler.ClientContext;
import org.jemule.network.handler.LoginHandler;
import org.jemule.network.handler.ObfuscationHandler;
import org.jemule.network.handler.PacketProcessor;
import org.jemule.security.FakeFileDetector;
import org.jemule.security.FloodProtector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Manages the lifecycle of a single eMule client connection.
 * <p>
 * This class handles the network I/O loop (read packets, send heartbeats,
 * detect disconnections) and delegates all protocol-specific processing
 * to dedicated handler classes in {@code org.jemule.network.handler}.
 */
public class ClientHandler implements Runnable, ClientContext {
    private static final Logger log = LoggerFactory.getLogger(ClientHandler.class);

    private final Socket socket;
    private final ServerConfig config;
    private final ClientRegistry registry;
    private final FileIndex fileIndex;
    private final FloodProtector floodProtector;
    private final FakeFileDetector fakeFileDetector;
    private final EventManager eventManager;
    private final ClientFactory clientFactory;
    private final Server server;
    private ClientState state;
    private boolean obfuscated = false;
    private OutputStream wrappedOut;

    // Handlers
    private final ObfuscationHandler obfuscationHandler = new ObfuscationHandler();
    private final LoginHandler loginHandler = new LoginHandler();
    private final PacketProcessor packetProcessor = new PacketProcessor();

    public ClientHandler(Socket socket, ServerConfig config, ClientRegistry registry, FileIndex fileIndex, FloodProtector floodProtector, FakeFileDetector fakeFileDetector, EventManager eventManager, ClientFactory clientFactory) {
        this(socket, config, registry, fileIndex, floodProtector, fakeFileDetector, eventManager, clientFactory, null);
    }

    public ClientHandler(Socket socket, ServerConfig config, ClientRegistry registry, FileIndex fileIndex, FloodProtector floodProtector, FakeFileDetector fakeFileDetector, EventManager eventManager, ClientFactory clientFactory, Server server) {
        this.socket = socket;
        this.config = config;
        this.registry = registry;
        this.fileIndex = fileIndex;
        this.floodProtector = floodProtector;
        this.fakeFileDetector = fakeFileDetector;
        this.eventManager = eventManager;
        this.clientFactory = clientFactory;
        this.server = server;
    }

    /**
     * Entry point of the client handler thread.
     * Manages the lifecycle of a single client connection, from handshake to disconnection.
     */
    @Override
    public void run() {
        try {
            socket.setSoTimeout(config.tcpKeepAliveTimeoutInSeconds() * 1000);
            String remoteAddr = maskIp(socket.getRemoteSocketAddress().toString());
            log.info("Client connected: {}", remoteAddr);
            broadcastEvent(ClientEvent.CONNECTED, remoteAddr, "anonymous", "Client connected");

            if (!floodProtector.allow(socket.getInetAddress())) {
                log.warn("Flood blocked: {}", socket.getInetAddress());
                return;
            }

            InputStream in = obfuscationHandler.negotiateObfuscation(this, socket.getInputStream(), socket.getOutputStream());
            Packet p = Packet.read(in, config.maxPacketSize());
            validateProtocol(p.protocol());

            OutputStream out = wrappedOut != null ? wrappedOut : socket.getOutputStream();
            loginHandler.handleLogin(this, p, out);

            long lastHeartbeat = System.currentTimeMillis();
            int heartbeatIntervalMs = config.heartbeatIntervalSeconds() * 1000;

            while (!socket.isClosed()) {
                try {
                    // Adjust timeout for heartbeat if enabled
                    if (heartbeatIntervalMs > 0) {
                        long now = System.currentTimeMillis();
                        long nextHeartbeat = lastHeartbeat + heartbeatIntervalMs;
                        long waitTime = nextHeartbeat - now;
                        
                        if (waitTime <= 0) {
                            LoginHandler.sendServerStatus(this, out);
                            lastHeartbeat = System.currentTimeMillis();
                            waitTime = heartbeatIntervalMs;
                        }
                        // We use a small timeout to read, allowing us to send heartbeat even if no packet received
                        socket.setSoTimeout((int) Math.min(waitTime, config.tcpKeepAliveTimeoutInSeconds() * 1000L));
                    }

                    Packet nextP = Packet.read(in, config.maxPacketSize());
                    if (floodProtector.allow(socket.getInetAddress())) {
                        packetProcessor.processPacket(this, nextP, out);
                    }
                } catch (java.net.SocketTimeoutException e) {
                    if (heartbeatIntervalMs > 0) {
                        // Just a timeout for heartbeat, loop will send it
                        continue;
                    }
                    throw e; // Real keep-alive timeout
                } catch (EOFException e) {
                    // Log EOF with more context so we can diagnose client-side SafeIO::EOF reports
                    String remoteAddr2 = maskIp(socket.getRemoteSocketAddress().toString());
                    log.warn("EOF from client {}: {}", remoteAddr2, e.getMessage());
                    break;
                }
            }
        } catch (IOException e) {
            log.error("IO Error: {}", e.getMessage());
        } finally {
            cleanup();
        }
    }

    private void validateProtocol(byte protocol) throws IOException {
        if (protocol != Packet.PROTOCOL_ED2K && protocol != Packet.PROTOCOL_EMULE && protocol != Packet.PROTOCOL_ZLIB) {
            throw new IOException("Unsupported protocol: " + String.format("0x%02X", protocol));
        }
    }

    private void broadcastEvent(String type, String addr, String id, String msg) {
        if (eventManager != null) {
            eventManager.broadcast(new ClientEvent(type, addr, id, msg));
        }
    }

    private void cleanup() {
        String remoteAddr = socket.getRemoteSocketAddress().toString();
        if (state != null) registry.remove(state);
        try {
            socket.close();
        } catch (IOException ignored) {}
        log.info("Disconnected: {}", remoteAddr);
        broadcastEvent(ClientEvent.DISCONNECTED, remoteAddr, 
            state != null ? String.valueOf(state.clientId()) : "anonymous", "Client disconnected");
    }

    /**
     * Masks the last part of an IP address for GDPR compliance.
     */
    private String maskIp(String addr) {
        if (addr == null) return "unknown";
        // Handles both "/1.2.3.4:port" and "1.2.3.4"
        String ipPart = addr;
        if (ipPart.startsWith("/")) ipPart = ipPart.substring(1);
        int colonIdx = ipPart.indexOf(':');
        String portPart = "";
        if (colonIdx != -1) {
            portPart = ipPart.substring(colonIdx);
            ipPart = ipPart.substring(0, colonIdx);
        }

        String[] parts = ipPart.split("\\.");
        if (parts.length == 4) {
            return parts[0] + "." + parts[1] + "." + parts[2] + ".xxx" + portPart;
        }
        return addr; // Return as is for IPv6 or unknown format for now
    }

    // ---- ClientContext interface implementation ----

    @Override public Socket getSocket() { return socket; }
    @Override public ServerConfig getConfig() { return config; }
    @Override public ClientRegistry getRegistry() { return registry; }
    @Override public FileIndex getFileIndex() { return fileIndex; }
    @Override public FloodProtector getFloodProtector() { return floodProtector; }
    @Override public FakeFileDetector getFakeFileDetector() { return fakeFileDetector; }
    @Override public EventManager getEventManager() { return eventManager; }
    @Override public ClientFactory getClientFactory() { return clientFactory; }
    @Override public Server getServer() { return server; }
    @Override public ClientState getState() { return state; }
    @Override public void setState(ClientState s) { this.state = s; }
    @Override public OutputStream getWrappedOut() { return wrappedOut; }
    @Override public void setWrappedOut(OutputStream out) { this.wrappedOut = out; }
    @Override public void setObfuscated(boolean o) { this.obfuscated = o; }
    @Override public void disconnect() throws IOException { socket.close(); }
}
