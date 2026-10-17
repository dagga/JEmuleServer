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

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class KadMaintenanceServiceTest {

    private InetAddress testIp(String ip) {
        try {
            return InetAddress.getByName(ip);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testEvictsDeadContacts() throws Exception {
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        KadId deadId = table.generateRandomIdForBucket(2);
        KadContact deadContact = new KadContact(deadId, testIp("10.0.0.1"), 4672);
        deadContact.recordFailure();
        deadContact.recordFailure();
        deadContact.recordFailure(); // 3 failures -> dead!
        assertTrue(deadContact.isDead());

        KadId aliveId = table.generateRandomIdForBucket(3);
        KadContact aliveContact = new KadContact(aliveId, testIp("10.0.0.2"), 4672);

        table.addOrUpdate(deadContact);
        table.addOrUpdate(aliveContact);
        assertEquals(2, table.size());

        try (KadUdpService udp = new KadUdpService(0);
             KadMaintenanceService maintenance = new KadMaintenanceService(table, udp, 60000, 3600000, 900000)) {

            udp.start();
            maintenance.start();

            maintenance.performMaintenanceOnce();

            assertEquals(1, table.size());
            assertNull(table.get(deadId));
            assertNotNull(table.get(aliveId));
            assertEquals(1, maintenance.getContactsEvicted());
        }
    }

    @Test
    void testPingsIdleContacts() throws Exception {
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        try (KadUdpService serverUdp = new KadUdpService(0);
             KadUdpService peerUdp = new KadUdpService(0);
             // contactMaxIdleMs = 0 so every contact is considered idle
             KadMaintenanceService maintenance = new KadMaintenanceService(table, serverUdp, 60000, 3600000, 0)) {

            serverUdp.start();
            peerUdp.start();
            maintenance.start();

            CountDownLatch pingLatch = new CountDownLatch(1);
            peerUdp.registerListener(KadOpCode.HELLO_REQ, (packet, addr, port) -> {
                pingLatch.countDown();
                // Send back HELLO_RES
                KadPacket res = KadPacket.createHelloRes(table.generateRandomIdForBucket(1), peerUdp.getPort(), (byte) 2);
                try {
                    peerUdp.send(res, addr, port);
                } catch (Exception e) {
                    fail(e);
                }
            });

            KadContact peerContact = new KadContact(table.generateRandomIdForBucket(1), InetAddress.getLoopbackAddress(), peerUdp.getPort());
            table.addOrUpdate(peerContact);

            maintenance.performMaintenanceOnce();

            assertTrue(pingLatch.await(3, TimeUnit.SECONDS), "Peer did not receive maintenance liveness ping");
            assertTrue(maintenance.getPingsSent() > 0);
        }
    }
}
