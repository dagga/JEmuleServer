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
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Represents a single K-Bucket in the Kademlia routing table.
 * Thread-safe and maintains up to {@value #K} contacts ordered by last activity.
 */
public final class KadBucket {
    public static final int K = 20;

    private final int depth;
    private final LinkedList<KadContact> contacts = new LinkedList<>();
    private final LinkedList<KadContact> replacements = new LinkedList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private volatile long lastUpdated = System.currentTimeMillis();

    public KadBucket(int depth) {
        this.depth = depth;
    }

    public int depth() {
        return depth;
    }

    public long lastUpdated() {
        return lastUpdated;
    }

    /**
     * Adds or updates a contact in the bucket following Kademlia rules:
     * - If already present, marks it alive and moves it to the tail.
     * - If not present and bucket has space, appends to tail.
     * - If bucket is full, keeps the contact in the replacement list.
     *
     * @return true if the contact was inserted or updated in the active bucket, false if queued in replacements.
     */
    public boolean addOrUpdate(KadContact contact) {
        Objects.requireNonNull(contact, "contact cannot be null");
        lock.lock();
        try {
            lastUpdated = System.currentTimeMillis();

            // Check if already in active contacts
            int existingIndex = -1;
            int idx = 0;
            for (KadContact c : contacts) {
                if (c.id().equals(contact.id())) {
                    existingIndex = idx;
                    break;
                }
                idx++;
            }

            if (existingIndex >= 0) {
                KadContact existing = contacts.remove(existingIndex);
                existing.markAlive();
                contacts.addLast(existing);
                return true;
            }

            if (contacts.size() < K) {
                contacts.addLast(contact);
                return true;
            }

            // Bucket full: maintain replacement cache (up to K)
            replacements.removeIf(c -> c.id().equals(contact.id()));
            if (replacements.size() >= K) {
                replacements.removeFirst();
            }
            replacements.addLast(contact);
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes a contact by ID. If active contacts drops below K, promotes a replacement if available.
     */
    public boolean remove(KadId id) {
        Objects.requireNonNull(id, "KadId cannot be null");
        lock.lock();
        try {
            boolean removed = false;
            Iterator<KadContact> it = contacts.iterator();
            while (it.hasNext()) {
                if (it.next().id().equals(id)) {
                    it.remove();
                    removed = true;
                    break;
                }
            }

            if (removed && !replacements.isEmpty() && contacts.size() < K) {
                contacts.addLast(replacements.removeFirst());
            }

            if (!removed) {
                replacements.removeIf(c -> c.id().equals(id));
            }

            if (removed) {
                lastUpdated = System.currentTimeMillis();
            }
            return removed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a snapshot of all active contacts in this bucket.
     */
    public List<KadContact> getContacts() {
        lock.lock();
        try {
            return new ArrayList<>(contacts);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Looks up an active contact by its KadId.
     */
    public KadContact get(KadId id) {
        Objects.requireNonNull(id, "KadId cannot be null");
        lock.lock();
        try {
            for (KadContact c : contacts) {
                if (c.id().equals(id)) {
                    return c;
                }
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return contacts.size();
        } finally {
            lock.unlock();
        }
    }

    public boolean isFull() {
        lock.lock();
        try {
            return contacts.size() >= K;
        } finally {
            lock.unlock();
        }
    }
}
