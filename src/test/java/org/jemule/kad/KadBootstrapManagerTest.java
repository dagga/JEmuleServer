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
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class KadBootstrapManagerTest {

    private InetAddress testIp(String ip) {
        try {
            return InetAddress.getByName(ip);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testBootstrapLifecycleAndResponse() throws Exception {
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        try (KadUdpService serverUdp = new KadUdpService(0);
             KadUdpService peerUdp = new KadUdpService(0);
             KadBootstrapManager bootstrapMgr = new KadBootstrapManager(table, serverUdp)) {

            serverUdp.start();
            peerUdp.start();
            bootstrapMgr.start();

            assertEquals(KadBootstrapManager.Status.DISCONNECTED, bootstrapMgr.getStatus());

            // Prepare peer to reply to BOOTSTRAP_REQ with 6 contacts
            CountDownLatch peerReceivedLatch = new CountDownLatch(1);
            peerUdp.registerListener(KadOpCode.BOOTSTRAP_REQ, (packet, remoteAddr, remotePort) -> {
                peerReceivedLatch.countDown();
                List<KadContact> contacts = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    KadId cid = KadId.random();
                    contacts.add(new KadContact(cid, testIp("198.51.100." + (i + 1)), 4672, 4662, (byte) 2));
                }
                KadPacket res = KadPacket.createBootstrapRes(contacts);
                try {
                    peerUdp.send(res, remoteAddr, remotePort);
                } catch (Exception e) {
                    fail(e);
                }
            });

            // Simulate manual bootstrap to peer
            assertTrue(bootstrapMgr.bootstrap(InetAddress.getLoopbackAddress(), peerUdp.getPort()));

            assertTrue(peerReceivedLatch.await(3, TimeUnit.SECONDS), "Peer did not receive BOOTSTRAP_REQ");

            // Wait for response to be processed by serverUdp
            long start = System.currentTimeMillis();
            while (table.size() < 6 && (System.currentTimeMillis() - start) < 3000) {
                Thread.sleep(50);
            }

            assertEquals(6, table.size());
            assertEquals(KadBootstrapManager.Status.CONNECTED, bootstrapMgr.getStatus());
            assertEquals(1, bootstrapMgr.getBootstrapSuccesses());
            assertEquals(6, bootstrapMgr.getContactsDiscovered());
        }
    }

    @Test
    void testOnClientConnectedEnqueuesCandidates() throws Exception {
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        try (KadUdpService udp = new KadUdpService(0);
             KadBootstrapManager bootstrapMgr = new KadBootstrapManager(table, udp)) {

            udp.start();
            bootstrapMgr.start();

            // Client with public IP and listenPort = 4662
            InetAddress clientAddr = testIp("203.0.113.42");
            ClientState client = new ClientState(clientAddr, 54321, 0, System.currentTimeMillis(), new AtomicLong(System.currentTimeMillis()));
            client.setListenPort(4662);

            bootstrapMgr.onClientConnected(client);

            // Attempts should have increased
            assertTrue(bootstrapMgr.getBootstrapAttempts() > 0);
        }
    }
}
