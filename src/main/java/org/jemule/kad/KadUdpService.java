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

package org.jemule.kad;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * High-performance UDP networking service for the Kademlia DHT.
 * Executes I/O multiplexing on Java 21 Virtual Threads and dispatches incoming packets to registered listeners.
 */
public class KadUdpService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(KadUdpService.class);
    private static final int BUFFER_SIZE = 65536;
    private static final int SOCKET_TIMEOUT_MS = 500;

    private final int requestedPort;
    private final ConcurrentHashMap<KadOpCode, List<KadPacketListener>> opcodeListeners = new ConcurrentHashMap<>();
    private final List<KadPacketListener> globalListeners = new CopyOnWriteArrayList<>();

    private volatile DatagramSocket socket;
    private volatile boolean running = false;
    private Thread receiverThread;

    public KadUdpService(int port) {
        this.requestedPort = port;
    }

    public KadUdpService() {
        this(0); // Ephemeral port
    }

    /**
     * Registers a listener for all incoming packets matching a specific opcode.
     */
    public void registerListener(KadOpCode opCode, KadPacketListener listener) {
        Objects.requireNonNull(opCode, "opCode cannot be null");
        Objects.requireNonNull(listener, "listener cannot be null");
        opcodeListeners.computeIfAbsent(opCode, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /**
     * Registers a global listener receiving all valid incoming packets.
     */
    public void registerGlobalListener(KadPacketListener listener) {
        Objects.requireNonNull(listener, "listener cannot be null");
        globalListeners.add(listener);
    }

    /**
     * Unregisters a listener for a specific opcode.
     */
    public void unregisterListener(KadOpCode opCode, KadPacketListener listener) {
        if (opCode != null && listener != null) {
            List<KadPacketListener> list = opcodeListeners.get(opCode);
            if (list != null) {
                list.remove(listener);
            }
        }
    }

    /**
     * Starts the UDP receiver service on a dedicated Virtual Thread.
     */
    public synchronized void start() throws SocketException {
        if (running) {
            return;
        }

        try {
            // Attempt to bind to IPv6 wildcard if available; fallback to default
            this.socket = new DatagramSocket(requestedPort);
        } catch (SocketException e) {
            log.error("Failed to bind Kad UDP socket to port {}: {}", requestedPort, e.getMessage());
            throw e;
        }

        this.socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        this.running = true;

        this.receiverThread = Thread.ofVirtual().name("KadUdpReceiver-" + socket.getLocalPort()).start(this::receiveLoop);
        log.info("Kad UDP service started on port {}", socket.getLocalPort());
    }

    /**
     * Sends a Kademlia UDP packet to the given remote destination.
     */
    public void send(KadPacket packet, InetAddress destination, int port) throws IOException {
        Objects.requireNonNull(packet, "packet cannot be null");
        Objects.requireNonNull(destination, "destination cannot be null");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }

        DatagramSocket s = this.socket;
        if (!running || s == null || s.isClosed()) {
            throw new IOException("Kad UDP service is not running");
        }

        byte[] raw = packet.encode();
        DatagramPacket datagram = new DatagramPacket(raw, raw.length, destination, port);
        s.send(datagram);
    }

    /**
     * Stops the UDP receiver service and releases socket resources.
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        DatagramSocket s = this.socket;
        if (s != null && !s.isClosed()) {
            s.close();
        }
        if (receiverThread != null) {
            receiverThread.interrupt();
            try {
                receiverThread.join(5000);
                if (receiverThread.isAlive()) {
                    log.warn("Kad UDP receiver thread did not stop in time");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Kad UDP service stopped");
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        DatagramSocket s = this.socket;
        return (s != null && !s.isClosed()) ? s.getLocalPort() : requestedPort;
    }

    private void receiveLoop() {
        byte[] buffer = new byte[BUFFER_SIZE];
        while (running) {
            try {
                DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                socket.receive(datagram);

                if (datagram.getLength() >= 2) {
                    processIncomingDatagram(datagram);
                }
            } catch (SocketTimeoutException e) {
                // Regular timeout to check running state
            } catch (SocketException e) {
                if (!running) {
                    break;
                }
                log.warn("Kad UDP socket error: {}", e.getMessage());
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                log.error("Unexpected error in Kad UDP receiver loop: {}", e.getMessage(), e);
            }
        }
    }

    private void processIncomingDatagram(DatagramPacket datagram) {
        KadPacket packet;
        try {
            packet = KadPacket.decode(datagram.getData(), datagram.getOffset(), datagram.getLength());
        } catch (Exception e) {
            log.debug("Malformed Kad UDP packet from {}:{}: {}", datagram.getAddress(), datagram.getPort(), e.getMessage());
            return;
        }

        InetAddress addr = datagram.getAddress();
        int port = datagram.getPort();

        // Dispatch to global listeners
        for (KadPacketListener l : globalListeners) {
            try {
                l.onPacket(packet, addr, port);
            } catch (Exception e) {
                log.warn("Error in global Kad packet listener: {}", e.getMessage(), e);
            }
        }

        // Dispatch to opcode-specific listeners
        KadOpCode opCode = packet.getOpCode();
        if (opCode != null) {
            List<KadPacketListener> list = opcodeListeners.get(opCode);
            if (list != null && !list.isEmpty()) {
                for (KadPacketListener l : list) {
                    try {
                        l.onPacket(packet, addr, port);
                    } catch (Exception e) {
                        log.warn("Error in Kad listener for opcode {}: {}", opCode, e.getMessage(), e);
                    }
                }
            }
        }
    }
}
