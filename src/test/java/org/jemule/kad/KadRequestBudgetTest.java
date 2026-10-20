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

import java.io.IOException;
import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.*;

class KadRequestBudgetTest {

    @Test
    void neverExceedsTheRequestsANodeAcceptsPerMinute() throws Exception {
        InetAddress node = InetAddress.getByName("198.51.100.7");
        InetAddress other = InetAddress.getByName("198.51.100.8");
        KadRequestBudget budget = new KadRequestBudget(3);
        long t = 1_000_000;

        assertTrue(budget.tryAcquire(node, t));
        assertTrue(budget.tryAcquire(node, t));
        assertTrue(budget.tryAcquire(node, t));
        assertFalse(budget.tryAcquire(node, t), "a 4th request within the minute would be dropped by the node");
        assertTrue(budget.tryAcquire(other, t), "the limit is per node IP");

        // The node forgets one request every 20 s; the budget waits 10% longer
        assertFalse(budget.tryAcquire(node, t + 20_500));
        assertTrue(budget.tryAcquire(node, t + 22_100));
        assertFalse(budget.tryAcquire(node, t + 22_200));
        assertTrue(budget.tryAcquire(node, t + 200_000));
    }

    @Test
    void doesNotLimitNodesOnThisHost() {
        KadRequestBudget budget = new KadRequestBudget(1);
        for (int i = 0; i < 10; i++) {
            assertTrue(budget.tryAcquire(InetAddress.getLoopbackAddress(), 0));
        }
    }

    @Test
    void udpServiceRefusesARequestTheNodeWouldTakeForAFlood() throws Exception {
        InetAddress node = InetAddress.getByName("192.0.2.10"); // TEST-NET-1: nothing answers
        KadPacket search = KadPacket.createSearchKeyReq(KadId.fromKeyword("linux"), 0);
        try (KadUdpService udp = new KadUdpService(0)) {
            udp.start();
            for (int i = 0; i < 3; i++) {
                try {
                    udp.send(search, node, 4672);
                } catch (KadUdpService.FloodLimitException e) {
                    fail("the first 3 search requests are within the limit");
                } catch (IOException ignored) {
                    // no route to TEST-NET on this host: the request was still counted
                }
            }
            assertThrows(KadUdpService.FloodLimitException.class, () -> udp.send(search, node, 4672));
            // Answers are not limited
            try {
                udp.send(KadPacket.createSearchRes(KadId.fromKeyword("linux"), java.util.List.of()), node, 4672);
            } catch (KadUdpService.FloodLimitException e) {
                fail("an answer is not a request");
            } catch (IOException ignored) {
                // no route to TEST-NET on this host
            }
        }
    }
}
