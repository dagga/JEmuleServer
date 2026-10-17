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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class KadUdpServiceTest {

    @Test
    void testLifecycleAndEphemeralPort() throws Exception {
        try (KadUdpService service = new KadUdpService(0)) {
            assertFalse(service.isRunning());
            service.start();
            assertTrue(service.isRunning());
            assertTrue(service.getPort() > 0);
            service.stop();
            assertFalse(service.isRunning());
        }
    }

    @Test
    void testSendAndReceiveLoopback() throws Exception {
        try (KadUdpService s1 = new KadUdpService(0);
             KadUdpService s2 = new KadUdpService(0)) {

            s1.start();
            s2.start();

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<KadPacket> received = new AtomicReference<>();
            AtomicReference<InetAddress> senderAddr = new AtomicReference<>();
            AtomicReference<Integer> senderPort = new AtomicReference<>();

            s2.registerListener(KadOpCode.HELLO_REQ, (packet, addr, port) -> {
                received.set(packet);
                senderAddr.set(addr);
                senderPort.set(port);
                latch.countDown();
            });

            KadId id = KadId.random();
            KadPacket helloPkt = KadPacket.createHelloReq(id, s1.getPort(), (byte) 2);

            InetAddress loopback = InetAddress.getLoopbackAddress();
            s1.send(helloPkt, loopback, s2.getPort());

            assertTrue(latch.await(3, TimeUnit.SECONDS), "Timeout waiting for packet in s2");

            assertNotNull(received.get());
            assertEquals(KadOpCode.HELLO_REQ, received.get().getOpCode());
            KadPacket.HelloPayload payload = KadPacket.parseHello(received.get().data());
            assertNotNull(payload);
            assertEquals(id, payload.senderId());
            assertEquals(s1.getPort(), payload.udpPort());
        }
    }

    @Test
    void testGlobalListener() throws Exception {
        try (KadUdpService s1 = new KadUdpService(0);
             KadUdpService s2 = new KadUdpService(0)) {

            s1.start();
            s2.start();

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<KadPacket> receivedGlobal = new AtomicReference<>();

            s2.registerGlobalListener((packet, addr, port) -> {
                receivedGlobal.set(packet);
                latch.countDown();
            });

            KadPacket req = KadPacket.createBootstrapReq();
            s1.send(req, InetAddress.getLoopbackAddress(), s2.getPort());

            assertTrue(latch.await(3, TimeUnit.SECONDS));
            assertNotNull(receivedGlobal.get());
            assertEquals(KadOpCode.BOOTSTRAP_REQ, receivedGlobal.get().getOpCode());
        }
    }
}
