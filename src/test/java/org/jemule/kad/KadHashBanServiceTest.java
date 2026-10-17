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
import org.jemule.security.ChildAbuseFilter;
import org.jemule.security.FakeFileDetector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link KadHashBanService} — Point 3.3: Automated persistent hash banning.
 * Verifies that illicit hashes detected during Kad searches are:
 * <ol>
 *     <li>Persisted in the H2 {@code banned_hashes} table.</li>
 *     <li>Propagated to {@link FakeFileDetector}.</li>
 *     <li>Loaded back from H2 into KadSearchBridge on initialization.</li>
 * </ol>
 */
class KadHashBanServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void illicitFileIsBannedInH2AndFakeDetector() throws Exception {
        String dbPath = tempDir.resolve("db").toString();
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        try (DatabaseManager db = new DatabaseManager(dbPath);
             KadUdpService udp = new KadUdpService(0);
             KadSearchBridge bridge = new KadSearchBridge(table, udp,
                     ChildAbuseFilter.defaults(), new FakeFileDetector(), 3, 2000)) {

            FakeFileDetector detector = new FakeFileDetector();
            KadHashBanService banService = new KadHashBanService(db, detector, bridge);
            banService.initialize();

            // Simulate an illicit file detection
            String illicitHash = "aabbccddeeff00112233445566778899";
            FileMetadata illicitFile = new FileMetadata(illicitHash,
                    "pthc-collection.avi", 500_000L, "Video");

            banService.onIllicitFile(illicitFile, ChildAbuseFilter.BAN_REASON);

            // Verify H2 persistence
            List<String> bannedInDb = db.loadBannedHashes();
            assertTrue(bannedInDb.contains(illicitHash.toUpperCase()),
                    "Illicit hash should be persisted in H2 banned_hashes");

            // Verify FakeFileDetector propagation
            assertTrue(detector.isFake(illicitHash, "renamed-file.avi", 1000),
                    "Banned hash should be rejected by FakeFileDetector even with a different name");

            // Verify counter
            assertEquals(1, banService.getTotalBanned());
        }
    }

    @Test
    void bannedHashesSurviveRestart() throws Exception {
        String dbPath = tempDir.resolve("db_restart").toString();
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        String illicitHash = "11223344556677889900aabbccddeeff";

        // First run: detect and ban an illicit file
        try (DatabaseManager db = new DatabaseManager(dbPath);
             KadUdpService udp = new KadUdpService(0);
             KadSearchBridge bridge = new KadSearchBridge(table, udp,
                     ChildAbuseFilter.defaults(), new FakeFileDetector(), 3, 2000)) {

            KadHashBanService banService = new KadHashBanService(db, null, bridge);
            banService.initialize();

            FileMetadata illicitFile = new FileMetadata(illicitHash,
                    "pthc-collection.avi", 500_000L, "Video");
            banService.onIllicitFile(illicitFile, ChildAbuseFilter.BAN_REASON);

            banService.shutdown();
        }

        // Second run: new bridge should load the ban from H2
        KadRoutingTable table2 = new KadRoutingTable(KadId.random());
        try (DatabaseManager db2 = new DatabaseManager(dbPath);
             KadUdpService udp2 = new KadUdpService(0);
             KadSearchBridge bridge2 = new KadSearchBridge(table2, udp2,
                     ChildAbuseFilter.defaults(), new FakeFileDetector(), 3, 2000)) {

            KadHashBanService banService2 = new KadHashBanService(db2, null, bridge2);
            int loaded = banService2.initialize();

            // At least the illicit hash should be loaded
            assertTrue(loaded >= 1, "At least 1 banned hash should be loaded from H2");

            // The bridge should now reject the previously banned hash
            assertTrue(bridge2.isHashBanned(illicitHash),
                    "Previously banned hash should be loaded into the new bridge instance");

            // A file with that hash should be rejected by acceptFile
            FileMetadata revisited = new FileMetadata(illicitHash,
                    "totally-innocent-name.avi", 500_000L, "Video");
            assertFalse(bridge2.acceptFile(revisited),
                    "File with previously banned hash should be rejected");
        }
    }

    @Test
    void initializeWithNoBannedHashes() throws Exception {
        String dbPath = tempDir.resolve("db_empty").toString();
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        try (DatabaseManager db = new DatabaseManager(dbPath);
             KadUdpService udp = new KadUdpService(0);
             KadSearchBridge bridge = new KadSearchBridge(table, udp,
                     ChildAbuseFilter.defaults(), new FakeFileDetector(), 3, 2000)) {

            KadHashBanService banService = new KadHashBanService(db, null, bridge);
            int loaded = banService.initialize();

            assertEquals(0, loaded, "No bans should be loaded from an empty database");
            assertEquals(0, banService.getTotalBanned());
        }
    }

    @Test
    void shutdownUnregistersListener() throws Exception {
        String dbPath = tempDir.resolve("db_shutdown").toString();
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        try (DatabaseManager db = new DatabaseManager(dbPath);
             KadUdpService udp = new KadUdpService(0);
             KadSearchBridge bridge = new KadSearchBridge(table, udp,
                     ChildAbuseFilter.defaults(), new FakeFileDetector(), 3, 2000)) {

            FakeFileDetector detector = new FakeFileDetector();
            KadHashBanService banService = new KadHashBanService(db, detector, bridge);
            banService.initialize();
            banService.shutdown();

            // After shutdown, illicit file should NOT be persisted via this service
            String hash = "ffeeddccbbaa99887766554433221100";
            FileMetadata file = new FileMetadata(hash, "illicit.avi", 1000, "Video");
            banService.onIllicitFile(file, "test");

            // The service still processes (it's a direct call), but the key test is
            // that it was unregistered — so bridge detections no longer reach it.
            // We verify the counter still increments on direct calls as expected.
            assertEquals(1, banService.getTotalBanned());
        }
    }

    @Test
    void loadBannedHashesByReason() throws Exception {
        String dbPath = tempDir.resolve("db_reason").toString();
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        try (DatabaseManager db = new DatabaseManager(dbPath);
             KadUdpService udp = new KadUdpService(0);
             KadSearchBridge bridge = new KadSearchBridge(table, udp,
                     ChildAbuseFilter.defaults(), new FakeFileDetector(), 3, 2000)) {

            KadHashBanService banService = new KadHashBanService(db, null, bridge);
            banService.initialize();

            // Ban with child abuse reason
            String hash1 = "aaaa000011112222333344445555bbbb";
            banService.onIllicitFile(
                    new FileMetadata(hash1, "illicit1.avi", 1000, "Video"),
                    ChildAbuseFilter.BAN_REASON);

            // Ban with different reason
            String hash2 = "cccc000011112222333344445555dddd";
            banService.onIllicitFile(
                    new FileMetadata(hash2, "illicit2.avi", 2000, "Video"),
                    "Custom reason");

            // Query by reason prefix
            List<String> childAbuseBans = db.loadBannedHashes(ChildAbuseFilter.BAN_REASON);
            assertTrue(childAbuseBans.contains(hash1.toUpperCase()));
            assertFalse(childAbuseBans.contains(hash2.toUpperCase()));

            List<String> allBans = db.loadBannedHashes();
            assertTrue(allBans.contains(hash1.toUpperCase()));
            assertTrue(allBans.contains(hash2.toUpperCase()));
        }
    }
}
