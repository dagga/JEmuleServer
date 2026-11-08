package org.jemule.security;

import org.jemule.core.DatabaseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServerBansTest {
    @TempDir
    Path tempDir;

    @Test
    void onlyDottedIpv4AddressesAreParsed() throws Exception {
        assertEquals(InetAddress.getByName("192.0.2.1"), ServerBans.parse(" 192.0.2.1 "));
        assertEquals("203.0.113.255", ServerBans.parse("203.0.113.255").getHostAddress());
        for (String invalid : List.of("", "192.0.2", "192.0.2.1.5", "192.0.2.256", "192.0.2.-1", "192.0..1",
                "example.org", "::1", "192.0.2.1:4661", "1234.0.2.1", "192.0.2.1 x")) {
            assertNull(ServerBans.parse(invalid), invalid);
        }
        assertNull(ServerBans.parse(null));
    }

    @Test
    void bansAreStoredLoadedAndLiftedWithTheirReasonAndDate() throws Exception {
        String dbPath = tempDir.resolve("db").toString();
        try (DatabaseManager db = new DatabaseManager(dbPath)) {
            ServerBans bans = new ServerBans();
            bans.setDatabaseManager(db);
            bans.ban(ServerBans.parse("192.0.2.10"), "fake files", 1_000_000);
            bans.ban(ServerBans.parse("198.51.100.20"), "  ", 2_000_000);
            bans.ban(ServerBans.parse("203.0.113.30"), "x".repeat(300), 3_000_000);
            assertEquals(ServerBans.MAX_REASON_LENGTH, bans.list().getFirst().reason().length());
            assertTrue(bans.isBanned(InetAddress.getByName("192.0.2.10")));
            assertFalse(bans.isBanned(InetAddress.getByName("192.0.2.11")));
            assertFalse(bans.isBanned(null));
            assertTrue(bans.unban(ServerBans.parse("203.0.113.30")));
            assertFalse(bans.unban(ServerBans.parse("203.0.113.30")));
        }

        try (DatabaseManager db = new DatabaseManager(dbPath)) {
            List<DatabaseManager.BannedServer> stored = db.loadBannedServers();
            assertEquals(List.of("198.51.100.20", "192.0.2.10"), stored.stream().map(DatabaseManager.BannedServer::ip)
                    .toList(), "the most recent first");
            assertNull(stored.getFirst().reason(), "no reason");

            ServerBans bans = new ServerBans();
            stored.forEach(bans::addLoaded);
            assertEquals(List.of(new ServerBans.Ban("198.51.100.20", "", 2_000_000),
                    new ServerBans.Ban("192.0.2.10", "fake files", 1_000_000)), bans.list());
            assertTrue(bans.isBanned(InetAddress.getByName("198.51.100.20")));

            // Banned again: the new reason and date replace the old ones
            bans.setDatabaseManager(db);
            bans.ban(ServerBans.parse("192.0.2.10"), "again", 4_000_000);
            assertEquals(new ServerBans.Ban("192.0.2.10", "again", 4_000_000), bans.list().getFirst());
            assertEquals(2, db.loadBannedServers().size());
            assertEquals("again", db.loadBannedServers().getFirst().reason());
            assertEquals(4_000_000, db.loadBannedServers().getFirst().bannedAt().getTime());
        }
    }
}
