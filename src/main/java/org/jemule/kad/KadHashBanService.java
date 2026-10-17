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

import org.jemule.core.DatabaseManager;
import org.jemule.core.FileMetadata;
import org.jemule.security.FakeFileDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bridges Kademlia illicit file detections to persistent H2 storage and cross-component ban propagation.
 * <p>
 * When the {@link KadSearchBridge} detects an illicit file (via {@link org.jemule.security.ChildAbuseFilter}),
 * this service:
 * <ol>
 *     <li>Persists the banned hash into the H2 {@code banned_hashes} table via {@link DatabaseManager#addBannedHash}.</li>
 *     <li>Propagates the ban to {@link FakeFileDetector} so that non-Kad code paths also reject the hash.</li>
 *     <li>Logs the event for audit purposes.</li>
 * </ol>
 * At startup, previously banned hashes from H2 are loaded back into the {@link KadSearchBridge}'s in-memory ban set
 * to ensure bans survive server restarts.
 */
public class KadHashBanService implements KadSearchBridge.IllicitFileListener {
    private static final Logger log = LoggerFactory.getLogger(KadHashBanService.class);

    private final DatabaseManager databaseManager;
    private final FakeFileDetector fakeDetector;
    private final KadSearchBridge searchBridge;
    private final AtomicLong totalBanned = new AtomicLong(0);

    /**
     * Creates a new KadHashBanService.
     *
     * @param databaseManager The H2 database manager for persistent ban storage.
     * @param fakeDetector    The FakeFileDetector for cross-component ban propagation (may be null).
     * @param searchBridge    The KadSearchBridge to register as listener and load bans into.
     */
    public KadHashBanService(DatabaseManager databaseManager, FakeFileDetector fakeDetector, KadSearchBridge searchBridge) {
        this.databaseManager = Objects.requireNonNull(databaseManager, "databaseManager cannot be null");
        this.fakeDetector = fakeDetector;
        this.searchBridge = Objects.requireNonNull(searchBridge, "searchBridge cannot be null");
    }

    /**
     * Initializes the service: loads existing banned hashes from H2 into the KadSearchBridge's
     * in-memory ban set, and registers this service as an IllicitFileListener.
     *
     * @return The number of banned hashes loaded from the database.
     */
    public int initialize() {
        // Load existing bans from H2 into the bridge's in-memory set
        List<String> existingBans = databaseManager.loadBannedHashes();
        int loaded = 0;
        for (String hash : existingBans) {
            searchBridge.banHash(hash);
            loaded++;
        }
        if (loaded > 0) {
            log.info("Loaded {} banned hashes from H2 into KadSearchBridge", loaded);
        }

        // Register as listener for future detections
        searchBridge.addIllicitFileListener(this);
        log.info("KadHashBanService initialized and registered as IllicitFileListener");
        return loaded;
    }

    /**
     * Called by KadSearchBridge when an illicit file is detected during Kad search results processing.
     * Persists the hash ban in H2 and propagates to FakeFileDetector.
     */
    @Override
    public void onIllicitFile(FileMetadata file, String reason) {
        if (file == null || file.hash() == null) return;

        String upperHash = file.hash().toUpperCase(Locale.ROOT);
        String truncatedReason = reason != null && reason.length() > 255
                ? reason.substring(0, 255)
                : reason;

        // 1. Persist in H2 banned_hashes table
        try {
            databaseManager.addBannedHash(upperHash, truncatedReason);
            log.info("Kad illicit hash permanently banned in H2: {} (file: '{}', reason: '{}')",
                    upperHash, file.name(), reason);
        } catch (Exception e) {
            log.error("Failed to persist banned hash {} in H2: {}", upperHash, e.getMessage());
        }

        // 2. Propagate to FakeFileDetector for cross-component enforcement
        if (fakeDetector != null) {
            fakeDetector.addBannedHash(upperHash);
        }

        totalBanned.incrementAndGet();
    }

    /**
     * Returns the total number of hashes banned through this service since initialization.
     */
    public long getTotalBanned() {
        return totalBanned.get();
    }

    /**
     * Unregisters this service from the KadSearchBridge.
     */
    public void shutdown() {
        searchBridge.removeIllicitFileListener(this);
        log.info("KadHashBanService shut down (total bans issued: {})", totalBanned.get());
    }
}
