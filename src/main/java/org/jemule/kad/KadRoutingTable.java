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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Routing table for the Kademlia DHT.
 * Organizes contacts into 128 K-Buckets indexed by common prefix length with the local NodeID.
 */
public final class KadRoutingTable {
    public static final int NUM_BUCKETS = KadId.ID_BITS; // 128

    private final KadId localId;
    private final KadBucket[] buckets;
    private final int maxContacts;

    public KadRoutingTable(KadId localId) {
        this(localId, Integer.MAX_VALUE);
    }

    /**
     * @param maxContacts Maximum number of active contacts across all buckets (kadMaxRoutingContacts).
     */
    public KadRoutingTable(KadId localId, int maxContacts) {
        this.localId = Objects.requireNonNull(localId, "localId cannot be null");
        if (maxContacts < 1) {
            throw new IllegalArgumentException("maxContacts must be at least 1");
        }
        this.maxContacts = maxContacts;
        this.buckets = new KadBucket[NUM_BUCKETS];
        for (int i = 0; i < NUM_BUCKETS; i++) {
            this.buckets[i] = new KadBucket(i);
        }
    }

    public int maxContacts() {
        return maxContacts;
    }

    public KadId localId() {
        return localId;
    }

    /**
     * Determines the bucket index for the given contact ID (0 to 127).
     *
     * @return bucket index, or -1 if the contact ID is identical to the local ID.
     */
    public int getBucketIndex(KadId contactId) {
        if (contactId == null || contactId.equals(localId)) {
            return -1;
        }
        int prefix = localId.commonPrefixLength(contactId);
        return Math.min(prefix, NUM_BUCKETS - 1);
    }

    /**
     * Inserts or updates a contact in the routing table.
     *
     * @param contact The contact to add or update.
     * @return true if added or updated in active bucket, false if rejected or queued in replacements.
     */
    public boolean addOrUpdate(KadContact contact) {
        if (contact == null) return false;
        int bucketIdx = getBucketIndex(contact.id());
        if (bucketIdx < 0) {
            return false; // Cannot add self to routing table
        }
        KadBucket bucket = buckets[bucketIdx];
        if (bucket.get(contact.id()) == null && size() >= maxContacts) {
            return false; // Table full: known contacts are still refreshed, new ones are refused
        }
        return bucket.addOrUpdate(contact);
    }

    /**
     * Removes a contact by ID.
     */
    public boolean remove(KadId contactId) {
        if (contactId == null) return false;
        int bucketIdx = getBucketIndex(contactId);
        if (bucketIdx < 0) return false;
        return buckets[bucketIdx].remove(contactId);
    }

    /**
     * Retrieves a contact by ID if present.
     */
    public KadContact get(KadId contactId) {
        if (contactId == null) return null;
        int bucketIdx = getBucketIndex(contactId);
        if (bucketIdx < 0) return null;
        return buckets[bucketIdx].get(contactId);
    }

    /**
     * Finds the {@code count} closest contacts to the specified target ID according to the XOR metric.
     *
     * @param target The target ID to search around.
     * @param count  Maximum number of contacts to return.
     * @return Sorted list of closest contacts (closest first).
     */
    public List<KadContact> findClosest(KadId target, int count) {
        Objects.requireNonNull(target, "target cannot be null");
        if (count <= 0) return List.of();

        List<KadContact> all = getAllContacts();
        all.sort(Comparator.comparing(c -> c.id().distance(target)));

        if (all.size() <= count) {
            return all;
        }
        return new ArrayList<>(all.subList(0, count));
    }

    /**
     * Returns a snapshot of all active contacts across all buckets.
     */
    public List<KadContact> getAllContacts() {
        List<KadContact> all = new ArrayList<>();
        for (KadBucket b : buckets) {
            all.addAll(b.getContacts());
        }
        return all;
    }

    /**
     * Total number of active contacts in the routing table.
     */
    public int size() {
        int total = 0;
        for (KadBucket b : buckets) {
            total += b.size();
        }
        return total;
    }

    /**
     * Returns the K-Bucket at the specified depth index (0 to 127).
     */
    public KadBucket getBucket(int index) {
        if (index < 0 || index >= NUM_BUCKETS) {
            throw new IndexOutOfBoundsException("Invalid bucket index: " + index);
        }
        return buckets[index];
    }

    /**
     * Generates a random KadId that falls into the bucket at {@code bucketIndex}.
     * Useful for performing bucket refresh lookups.
     */
    public KadId generateRandomIdForBucket(int bucketIndex) {
        if (bucketIndex < 0 || bucketIndex >= NUM_BUCKETS) {
            throw new IndexOutOfBoundsException("Invalid bucket index: " + bucketIndex);
        }

        byte[] randomBytes = new byte[KadId.ID_BYTES];
        ThreadLocalRandom.current().nextBytes(randomBytes);

        byte[] localBytes = localId.getBytes();
        int bytePos = bucketIndex / 8;
        int bitOffset = 7 - (bucketIndex % 8);

        // All full bytes before bytePos match localId exactly
        System.arraycopy(localBytes, 0, randomBytes, 0, bytePos);

        // In boundary byte:
        // Bits strictly above bitOffset match localBytes
        // Bit at bitOffset is strictly the inverse of localBytes
        // Bits strictly below bitOffset remain random
        int maskAbove = 0xFF << (bitOffset + 1);
        int invertedBit = (~localBytes[bytePos]) & (1 << bitOffset);
        int maskBelow = (1 << bitOffset) - 1;

        randomBytes[bytePos] = (byte) ((localBytes[bytePos] & maskAbove) | invertedBit | (randomBytes[bytePos] & maskBelow));

        return new KadId(randomBytes);
    }
}
