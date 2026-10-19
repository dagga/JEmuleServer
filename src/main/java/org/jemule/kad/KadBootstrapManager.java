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

import org.jemule.core.ClientState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages Kademlia bootstrapping by automatically discovering and contacting
 * eMule clients connected to the server.
 */
public class KadBootstrapManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(KadBootstrapManager.class);

    public enum Status {
        DISCONNECTED,
        BOOTSTRAPPING,
        CONNECTED
    }

    public static final int CONNECTED_THRESHOLD = 5;

    /** Maximum number of pending bootstrap targets kept in the queue. */
    private static final int MAX_QUEUE = 100;

    private final KadRoutingTable routingTable;
    private final KadUdpService udpService;
    private final ConcurrentLinkedQueue<BootstrapTarget> bootstrapQueue = new ConcurrentLinkedQueue<>();

    private final AtomicLong bootstrapAttempts = new AtomicLong(0);
    private final AtomicLong bootstrapSuccesses = new AtomicLong(0);
    private final AtomicLong contactsDiscovered = new AtomicLong(0);
    private final AtomicInteger pendingRequests = new AtomicInteger(0);

    private volatile Status status = Status.DISCONNECTED;
    private volatile boolean running = false;

    public record BootstrapTarget(InetAddress address, int udpPort) {}

    public KadBootstrapManager(KadRoutingTable routingTable, KadUdpService udpService) {
        this.routingTable = Objects.requireNonNull(routingTable, "routingTable cannot be null");
        this.udpService = Objects.requireNonNull(udpService, "udpService cannot be null");
    }

    /**
     * Initializes the bootstrap manager and registers packet listeners.
     */
    public synchronized void start() {
        if (running) return;
        running = true;
        udpService.registerListener(KadOpCode.BOOTSTRAP_RES, this::handleBootstrapRes);
        udpService.registerListener(KadOpCode.HELLO_RES, this::handleHelloRes);
        log.info("KadBootstrapManager started");
    }

    /**
     * Called when an eMule client connects to JEmuleServer.
     * Evaluates if the client is eligible for bootstrap (HighID and non-local IP).
     */
    public void onClientConnected(ClientState client) {
        if (!running || client == null) return;

        InetAddress addr = client.address();
        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()) {
            return;
        }

        // eMule clients with HighID have an open TCP port; Kad UDP port is typically listenPort + 10 or 4672
        int tcpPort = client.listenPort();
        if (tcpPort > 0 && tcpPort <= 65535 - 10) {
            int candidateKadPort = tcpPort + 10;
            enqueueTarget(addr, candidateKadPort);
        }
        // Also queue default standard Kad port (4672) if different
        if (tcpPort != 4662) {
            enqueueTarget(addr, 4672);
        }

        processQueue();
    }

    /**
     * Manually triggers a bootstrap request to a specific node address and port.
     */
    public boolean bootstrap(InetAddress address, int udpPort) {
        if (!running || address == null || udpPort <= 0 || udpPort > 65535) {
            return false;
        }
        enqueueTarget(address, udpPort);
        processQueue();
        return true;
    }

    /**
     * Queues bootstrap requests for a downloaded contact list (nodes.dat). Only the addresses are used: the
     * routing table is filled by the answers, whose contact IDs are in wire order.
     *
     * @return the number of targets queued
     */
    public int bootstrapFromNodes(List<KadContact> contacts) {
        if (!running || contacts == null) return 0;
        int queued = 0;
        for (KadContact contact : contacts) {
            if (bootstrapQueue.size() >= MAX_QUEUE) break;
            enqueueTarget(contact.address(), contact.udpPort());
            queued++;
        }
        processQueue();
        return queued;
    }

    private void enqueueTarget(InetAddress address, int udpPort) {
        if (bootstrapQueue.size() < MAX_QUEUE) {
            bootstrapQueue.offer(new BootstrapTarget(address, udpPort));
        }
    }

    /**
     * Processes pending bootstrap requests in the queue.
     */
    public void processQueue() {
        if (!running) return;

        if (status == Status.DISCONNECTED && routingTable.size() < CONNECTED_THRESHOLD) {
            status = Status.BOOTSTRAPPING;
        }

        while (!bootstrapQueue.isEmpty() && pendingRequests.get() < 15) {
            BootstrapTarget target = bootstrapQueue.poll();
            if (target == null) break;

            try {
                KadPacket req = KadPacket.createBootstrapReq();
                udpService.send(req, target.address(), target.udpPort());
                bootstrapAttempts.incrementAndGet();
                pendingRequests.incrementAndGet();
                log.debug("Sent KADEMLIA2_BOOTSTRAP_REQ to {}:{}", target.address().getHostAddress(), target.udpPort());
            } catch (IOException e) {
                log.debug("Failed to send bootstrap packet to {}:{} - {}", target.address().getHostAddress(), target.udpPort(), e.getMessage());
            }
        }
    }

    private void handleBootstrapRes(KadPacket packet, InetAddress remoteAddress, int remotePort) {
        pendingRequests.updateAndGet(v -> Math.max(0, v - 1));
        KadPacket.BootstrapRes res = KadPacket.parseBootstrapRes(packet.data());
        if (res == null) {
            log.debug("Malformed KADEMLIA2_BOOTSTRAP_RES from {}:{}", remoteAddress.getHostAddress(), remotePort);
            processQueue();
            return;
        }

        // The responder itself is a valid Kad contact (its ID and TCP port are carried in the header).
        routingTable.addOrUpdate(new KadContact(res.responderId(), remoteAddress, remotePort,
                res.responderTcpPort(), res.responderVersion()));

        List<KadContact> contacts = res.contacts();
        if (contacts.isEmpty()) {
            log.debug("Received empty KADEMLIA2_BOOTSTRAP_RES from {}:{}", remoteAddress.getHostAddress(), remotePort);
            processQueue();
            return;
        }

        bootstrapSuccesses.incrementAndGet();
        contactsDiscovered.addAndGet(contacts.size());

        for (KadContact contact : contacts) {
            routingTable.addOrUpdate(contact);
        }

        // Update overall connection status
        if (routingTable.size() >= CONNECTED_THRESHOLD) {
            if (status != Status.CONNECTED) {
                log.info("Kademlia network CONNECTED: {} active contacts discovered", routingTable.size());
            }
            status = Status.CONNECTED;
        }

        log.debug("Bootstrap successful from {}:{}: added {} contacts (total routing table: {})",
                remoteAddress.getHostAddress(), remotePort, contacts.size(), routingTable.size());
        processQueue();
    }

    private void handleHelloRes(KadPacket packet, InetAddress remoteAddress, int remotePort) {
        KadPacket.HelloPayload payload = KadPacket.parseHello(packet.data());
        if (payload != null) {
            KadContact contact = new KadContact(payload.senderId(), remoteAddress, remotePort,
                    payload.tcpPort(), payload.kadVersion());
            routingTable.addOrUpdate(contact);
            if (routingTable.size() >= CONNECTED_THRESHOLD) {
                status = Status.CONNECTED;
            }
        }
    }

    public Status getStatus() {
        return status;
    }

    public long getBootstrapAttempts() {
        return bootstrapAttempts.get();
    }

    public long getBootstrapSuccesses() {
        return bootstrapSuccesses.get();
    }

    public long getContactsDiscovered() {
        return contactsDiscovered.get();
    }

    public int getQueueSize() {
        return bootstrapQueue.size();
    }

    @Override
    public synchronized void close() {
        running = false;
        bootstrapQueue.clear();
        status = Status.DISCONNECTED;
        log.info("KadBootstrapManager stopped");
    }
}
