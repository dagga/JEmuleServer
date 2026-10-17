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
import java.net.InetAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Periodically maintains the Kademlia routing table by refreshing stale K-Buckets,
 * verifying contact liveness, and evicting unresponsive peers.
 */
public class KadMaintenanceService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(KadMaintenanceService.class);

    public static final long DEFAULT_CHECK_INTERVAL_MS = 60_000;     // 1 minute
    public static final long DEFAULT_BUCKET_MAX_IDLE_MS = 3_600_000;  // 1 hour
    public static final long DEFAULT_CONTACT_MAX_IDLE_MS = 900_000;   // 15 minutes

    private final KadRoutingTable routingTable;
    private final KadUdpService udpService;
    private final long checkIntervalMs;
    private final long bucketMaxIdleMs;
    private final long contactMaxIdleMs;

    private final AtomicLong refreshCount = new AtomicLong(0);
    private final AtomicLong pingsSent = new AtomicLong(0);
    private final AtomicLong contactsEvicted = new AtomicLong(0);

    private volatile boolean running = false;
    private Thread maintenanceThread;

    public KadMaintenanceService(KadRoutingTable routingTable, KadUdpService udpService,
                                 long checkIntervalMs, long bucketMaxIdleMs, long contactMaxIdleMs) {
        this.routingTable = Objects.requireNonNull(routingTable, "routingTable cannot be null");
        this.udpService = Objects.requireNonNull(udpService, "udpService cannot be null");
        this.checkIntervalMs = checkIntervalMs;
        this.bucketMaxIdleMs = bucketMaxIdleMs;
        this.contactMaxIdleMs = contactMaxIdleMs;
    }

    public KadMaintenanceService(KadRoutingTable routingTable, KadUdpService udpService) {
        this(routingTable, udpService, DEFAULT_CHECK_INTERVAL_MS, DEFAULT_BUCKET_MAX_IDLE_MS, DEFAULT_CONTACT_MAX_IDLE_MS);
    }

    /**
     * Starts the periodic maintenance loop on a dedicated Virtual Thread.
     */
    public synchronized void start() {
        if (running) return;
        running = true;

        // Register listener for HELLO_RES to mark contacts alive upon ping response
        udpService.registerListener(KadOpCode.HELLO_RES, this::handleHelloRes);

        maintenanceThread = Thread.ofVirtual().name("KadMaintenance").start(this::runLoop);
        log.info("KadMaintenanceService started (interval: {}s, bucketIdle: {}s)",
                checkIntervalMs / 1000, bucketMaxIdleMs / 1000);
    }

    /**
     * Executes a single maintenance pass. Public to allow deterministic unit testing.
     */
    public void performMaintenanceOnce() {
        long now = System.currentTimeMillis();

        // 1. Evict confirmed dead contacts and ping idle contacts
        for (KadContact contact : routingTable.getAllContacts()) {
            if (contact.isDead()) {
                if (routingTable.remove(contact.id())) {
                    contactsEvicted.incrementAndGet();
                    log.debug("Evicted dead Kad contact: {}", contact);
                }
            } else if (now - contact.lastSeen() >= contactMaxIdleMs) {
                pingContact(contact);
            }
        }

        // 2. Refresh stale K-Buckets
        for (int i = 0; i < KadRoutingTable.NUM_BUCKETS; i++) {
            KadBucket bucket = routingTable.getBucket(i);
            if (bucket.size() > 0 && (now - bucket.lastUpdated() >= bucketMaxIdleMs)) {
                refreshBucket(i);
            }
        }
    }

    private void pingContact(KadContact contact) {
        try {
            KadPacket ping = KadPacket.createHelloReq(routingTable.localId(), udpService.getPort(), (byte) 2);
            udpService.send(ping, contact.address(), contact.udpPort());
            contact.recordFailure(); // Will be cleared to 0 when HELLO_RES arrives
            pingsSent.incrementAndGet();
            log.debug("Sent maintenance liveness ping to {}", contact);
        } catch (IOException e) {
            contact.recordFailure();
            log.debug("Failed to send liveness ping to {}: {}", contact, e.getMessage());
        }
    }

    private void refreshBucket(int bucketIndex) {
        KadId randomTarget = routingTable.generateRandomIdForBucket(bucketIndex);
        List<KadContact> closest = routingTable.findClosest(randomTarget, 3);
        if (closest.isEmpty()) return;

        KadPacket findNode = KadPacket.createFindNodeReq((byte) 0, randomTarget);
        for (KadContact node : closest) {
            try {
                udpService.send(findNode, node.address(), node.udpPort());
            } catch (IOException e) {
                log.debug("Failed to send bucket refresh findNode to {}: {}", node, e.getMessage());
            }
        }
        refreshCount.incrementAndGet();
        log.debug("Triggered refresh for K-Bucket {} with target {}", bucketIndex, randomTarget);
    }

    private void handleHelloRes(KadPacket packet, InetAddress remoteAddress, int remotePort) {
        KadPacket.HelloPayload payload = KadPacket.parseHello(packet.data());
        if (payload != null) {
            KadContact contact = routingTable.get(payload.senderId());
            if (contact != null) {
                contact.markAlive();
                log.debug("Received HELLO_RES liveness response from {}", contact);
            }
        }
    }

    private void runLoop() {
        while (running) {
            try {
                Thread.sleep(checkIntervalMs);
                if (!running) break;
                performMaintenanceOnce();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Unexpected error in Kad maintenance loop: {}", e.getMessage(), e);
            }
        }
    }

    public long getRefreshCount() {
        return refreshCount.get();
    }

    public long getPingsSent() {
        return pingsSent.get();
    }

    public long getContactsEvicted() {
        return contactsEvicted.get();
    }

    @Override
    public synchronized void close() {
        running = false;
        if (maintenanceThread != null) {
            maintenanceThread.interrupt();
        }
        log.info("KadMaintenanceService stopped");
    }
}
