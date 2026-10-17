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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KadRoutingTableTest {

    private InetAddress testIp(String ip) {
        try {
            return InetAddress.getByName(ip);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testKadContactLiveness() {
        KadId id = KadId.random();
        KadContact contact = new KadContact(id, testIp("192.168.1.10"), 4672, 4662, (byte) 2);

        assertFalse(contact.isDead());
        assertEquals(0, contact.failedPings());

        contact.recordFailure();
        contact.recordFailure();
        assertFalse(contact.isDead());

        contact.recordFailure();
        assertTrue(contact.isDead());

        contact.markAlive();
        assertFalse(contact.isDead());
        assertEquals(0, contact.failedPings());
    }

    @Test
    void testBucketCapacityAndReplacement() {
        KadBucket bucket = new KadBucket(0);

        // Add 20 contacts -> bucket should become full
        for (int i = 0; i < KadBucket.K; i++) {
            KadId id = KadId.fromHex(String.format("%02x000000000000000000000000000000", i + 1));
            assertTrue(bucket.addOrUpdate(new KadContact(id, testIp("10.0.0." + (i + 1)), 4672)));
        }

        assertEquals(KadBucket.K, bucket.size());
        assertTrue(bucket.isFull());

        // 21st contact should go to replacements (addOrUpdate returns false)
        KadId extraId = KadId.fromHex("ff000000000000000000000000000000");
        KadContact extraContact = new KadContact(extraId, testIp("10.0.0.99"), 4672);
        assertFalse(bucket.addOrUpdate(extraContact));
        assertEquals(KadBucket.K, bucket.size());

        // Removing one contact from bucket should promote the replacement
        KadId firstId = KadId.fromHex(String.format("%02x000000000000000000000000000000", 1));
        assertTrue(bucket.remove(firstId));
        assertEquals(KadBucket.K, bucket.size());
        assertNotNull(bucket.get(extraId));
    }

    @Test
    void testRoutingTableBucketsAndClosest() {
        KadId localId = KadId.fromHex("00000000000000000000000000000000");
        KadRoutingTable table = new KadRoutingTable(localId);

        // Cannot add self
        assertFalse(table.addOrUpdate(new KadContact(localId, testIp("127.0.0.1"), 4672)));
        assertEquals(0, table.size());

        // Contact differing at 1st bit (prefix length 0)
        KadId c0 = KadId.fromHex("80000000000000000000000000000000");
        table.addOrUpdate(new KadContact(c0, testIp("1.1.1.1"), 4672));
        assertEquals(0, table.getBucketIndex(c0));

        // Contact differing at 2nd bit (prefix length 1)
        KadId c1 = KadId.fromHex("40000000000000000000000000000000");
        table.addOrUpdate(new KadContact(c1, testIp("1.1.1.2"), 4672));
        assertEquals(1, table.getBucketIndex(c1));

        // Contact differing at 8th bit (prefix length 7)
        KadId c7 = KadId.fromHex("01000000000000000000000000000000");
        table.addOrUpdate(new KadContact(c7, testIp("1.1.1.3"), 4672));
        assertEquals(7, table.getBucketIndex(c7));

        assertEquals(3, table.size());

        // Find closest to target very close to c0
        KadId targetNearC0 = KadId.fromHex("80000000000000000000000000000001");
        List<KadContact> closest = table.findClosest(targetNearC0, 2);
        assertEquals(2, closest.size());
        assertEquals(c0, closest.get(0).id());

        // Test random ID generator for a specific bucket
        KadId generated = table.generateRandomIdForBucket(5);
        assertEquals(5, table.getBucketIndex(generated));
    }

    @Test
    void testMaxContactsLimitRefusesNewContactsButRefreshesKnownOnes() {
        KadRoutingTable table = new KadRoutingTable(KadId.random(), 2);
        assertEquals(2, table.maxContacts());

        KadContact first = new KadContact(table.generateRandomIdForBucket(1), testIp("10.0.0.1"), 4672);
        KadContact second = new KadContact(table.generateRandomIdForBucket(2), testIp("10.0.0.2"), 4672);
        KadContact third = new KadContact(table.generateRandomIdForBucket(3), testIp("10.0.0.3"), 4672);

        assertTrue(table.addOrUpdate(first));
        assertTrue(table.addOrUpdate(second));
        assertFalse(table.addOrUpdate(third), "the table is full");
        assertEquals(2, table.size());
        assertNull(table.get(third.id()));

        assertTrue(table.addOrUpdate(first), "a known contact is still refreshed when the table is full");
        assertEquals(2, table.size());

        assertThrows(IllegalArgumentException.class, () -> new KadRoutingTable(KadId.random(), 0));
    }
}
