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

package org.jemule.core;

import org.jemule.protocol.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The source count of a file is what the networks report lately, not the most they ever reported: the first report
 * of a day replaces the one before, the online sources here raise the count of the index without being saved, and
 * the daily purge forgets the counts nobody reported for {@code sourceCountMaxAgeDays} days. The files whose hash
 * starts with a banned prefix (made up by a gateway) are refused and can be purged.
 */
class SourceCountFreshnessTest {
    private static final int DAY = 20_400;
    private static final SearchQuery MOVIE = new SearchQuery.TermQuery("movie", SearchQuery.ID_FILENAME);
    private static final String GATEWAY = "ED2B01120000";

    @TempDir
    Path tempDir;

    private String dbPath;
    private DatabaseManager db;
    private FileIndex index;
    private final AtomicInteger today = new AtomicInteger(DAY);

    @BeforeEach
    void open() throws Exception {
        dbPath = tempDir.resolve("db").toString();
        db = new DatabaseManager(dbPath);
        db.setDayClock(today::get);
        index = new FileIndex(db, null, org.jemule.security.ChildAbuseFilter.defaults(), tempDir.resolve("index"));
    }

    @AfterEach
    void close() throws Exception {
        index.close();
        db.close();
    }

    private static String hash(int i) {
        return String.format("%032X", i);
    }

    /** A search result of Kad or of another server: the file with the count it was reported with. */
    private static FileMetadata reported(String hash, String name, int sources) {
        return FileMetadata.withoutSources(hash, name, 1_000L, "Video",
                List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, sources)));
    }

    private static FileMetadata reported(int i, int sources) {
        return reported(hash(i), "movie " + i + ".avi", sources);
    }

    private static ClientState client(int id) throws Exception {
        ClientState st = new ClientState(InetAddress.getByName("10.0.0." + id), 50000, id, System.currentTimeMillis(),
                new AtomicLong());
        st.setListenPort(4662);
        return st;
    }

    private static FileMetadata publishedBy(int i, ClientState client) {
        ConcurrentHashMap<String, ClientState> sources = new ConcurrentHashMap<>();
        sources.put(FileIndex.sourceKey(client), client);
        client.publishedHashes().add(hash(i));
        return new FileMetadata(hash(i), "movie " + i + ".avi", 1_000L, "Video", List.of(), sources);
    }

    /** The count the searches return for a file, -1 when they do not return it. */
    private int indexed(int i) {
        index.refresh();
        return index.searchComplex(MOVIE, 100).stream().filter(f -> f.hash().equals(hash(i)))
                .mapToInt(FileMetadata::sourceCount).findFirst().orElse(-1);
    }

    private Connection sql() throws Exception {
        return DriverManager.getConnection("jdbc:h2:" + dbPath, "sa", "");
    }

    /** The count of the database, -1 when it has none. */
    private int saved(int i) throws Exception {
        try (Connection c = sql();
             PreparedStatement ps = c.prepareStatement("SELECT source_count FROM file_sources WHERE hash = ?")) {
            ps.setString(1, hash(i));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private boolean inDatabase(String hash) throws Exception {
        try (Connection c = sql(); PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM files WHERE hash = ?")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private void setLastSeen(int i, int day) throws Exception {
        try (Connection c = sql(); PreparedStatement ps = c.prepareStatement("UPDATE files SET last_seen = ? WHERE hash = ?")) {
            ps.setInt(1, day);
            ps.setString(2, hash(i));
            assertEquals(1, ps.executeUpdate());
        }
    }

    @Test
    void theFirstReportOfTheDayReplacesTheCountAndTheNextOnesOnlyRaiseIt() throws Exception {
        index.addFiles(List.of(reported(1, 99)), FileSource.SERVER_SEARCH);
        index.addFiles(List.of(reported(1, 3)), FileSource.KAD_SEARCH);
        assertEquals(99, indexed(1), "the same day, the networks add up to the most any of them saw");
        assertEquals(99, saved(1));

        today.set(DAY + 1);
        index.addFiles(List.of(reported(1, 3)), FileSource.KAD_SEARCH);
        assertEquals(3, indexed(1), "the next day, the first report replaces the old count");
        assertEquals(3, saved(1), "in the database too");
        index.addFiles(List.of(reported(1, 5)), FileSource.SERVER_SEARCH);
        index.addFiles(List.of(reported(1, 2)), FileSource.SERVER_INDEX_UPDATE);
        assertEquals(5, indexed(1));
        assertEquals(5, saved(1));

        // A client publishing it reports no count: the count stays
        index.addFile(new FileMetadata(hash(1), "movie 1.avi", 1_000L, "Video"), FileSource.CLIENT_PUSH);
        assertEquals(5, saved(1));
    }

    @Test
    void theOnlineSourcesRaiseTheIndexedCountButAreNeitherSavedNorKept() throws Exception {
        index.addFiles(List.of(reported(1, 1)), FileSource.KAD_SEARCH);
        ClientState a = client(1), b = client(2), c = client(3);
        for (ClientState client : List.of(a, b, c)) index.addFile(publishedBy(1, client), FileSource.CLIENT_PUSH);
        assertEquals(3, indexed(1), "the searches sort on the online sources too");
        assertEquals(1, saved(1), "the database keeps the reported count only");

        // A report while the clients are online: kept aside, the index keeps the higher count
        today.set(DAY + 1);
        index.addFiles(List.of(reported(1, 2)), FileSource.SERVER_SEARCH);
        assertEquals(3, indexed(1));
        assertEquals(2, saved(1));

        index.removeSource(a);
        assertEquals(2, indexed(1), "two online sources, two reported");
        index.removeSource(b);
        index.removeSource(c);
        assertEquals(2, indexed(1), "back to the reported count when the clients leave");

        // A file only our clients share: back to 0
        ClientState d = client(4);
        index.addFile(publishedBy(2, d), FileSource.CLIENT_PUSH);
        assertEquals(1, indexed(2));
        assertEquals(-1, saved(2), "no count saved");
        index.removeSource(d);
        assertEquals(0, indexed(2));
    }

    @Test
    void theDailyPurgeResetsTheCountsNobodyReportedForTheirMaximumAge() throws Exception {
        for (int i = 1; i <= 5; i++) index.addFiles(List.of(reported(i, 7)), FileSource.SERVER_SEARCH);
        ClientState client = client(1);
        index.addFile(publishedBy(3, client), FileSource.CLIENT_PUSH);

        today.set(DAY + 40);
        setLastSeen(1, DAY);       // not reported for 40 days: reset
        setLastSeen(2, DAY + 20);  // 20 days: kept
        setLastSeen(3, DAY);       // reset, its online source still counting
        setLastSeen(4, DAY);
        index.addFiles(List.of(reported(4, 6)), FileSource.KAD_SEARCH); // reported today: kept
        setLastSeen(4, DAY);       // its day not written yet
        setLastSeen(5, DAY - 200); // stale: purged, not reset

        FilePurgeJob job = new FilePurgeJob(db, index, () -> 180, () -> 30, 2, 1, 0);
        assertTrue(job.start());
        assertTrue(job.awaitEnd(30_000));
        FilePurgeJob.Status status = job.status();
        assertEquals(FilePurgeJob.State.DONE, status.state());
        assertEquals(1, status.purged());
        assertEquals(2, status.countsReset());
        assertEquals(30, status.sourceCountMaxAgeDays());

        assertEquals(0, indexed(1));
        assertEquals(0, saved(1));
        assertEquals(7, indexed(2));
        assertEquals(7, saved(2));
        assertEquals(1, indexed(3), "the online source");
        assertEquals(0, saved(3));
        assertEquals(6, indexed(4));
        assertEquals(6, saved(4));
        assertEquals(-1, indexed(5), "purged");

        index.removeSource(client);
        assertEquals(0, indexed(3), "the reset count is not kept aside for the online sources");
    }

    @Test
    void theCountAgeAloneRunsThePurgeJob() throws Exception {
        index.addFiles(List.of(reported(1, 7)), FileSource.SERVER_SEARCH);
        today.set(DAY + 400);
        FilePurgeJob job = new FilePurgeJob(db, index, () -> 0, () -> 30, 10, 10, 0);
        assertTrue(job.runIfDue(System.currentTimeMillis()), "no retention, but counts to age");
        assertTrue(job.awaitEnd(30_000));
        assertEquals(0, job.status().purged(), "no file purged without retention");
        assertEquals(1, job.status().countsReset());
        assertEquals(0, saved(1));
        assertEquals(0, indexed(1), "kept, without count");

        FilePurgeJob off = new FilePurgeJob(db, index, () -> 0, () -> 0, 10, 10, 0);
        assertFalse(off.runIfDue(System.currentTimeMillis() + 2 * FilePurgeJob.INTERVAL_MS), "nothing to do");
    }

    @Test
    void theCountOfTheDatabaseIsLoadedAsSaved() throws Exception {
        index.addFiles(List.of(reported(1, 99)), FileSource.SERVER_SEARCH);
        today.set(DAY + 1);
        index.addFiles(List.of(reported(1, 4)), FileSource.SERVER_SEARCH);
        index.close();
        index = new FileIndex(db, null, org.jemule.security.ChildAbuseFilter.defaults(), tempDir.resolve("other"));
        assertEquals(4, indexed(1), "rebuilt from the database: the lowered count");
    }

    @Test
    void theFilesWithABannedHashPrefixAreRefusedByEveryChannel() throws Exception {
        index.setBannedHashPrefixes(() -> GATEWAY);
        String fake = GATEWAY + "8A8F033CDCEF7349E8EB";
        assertNull(index.addFile(reported(fake, "fake movie.mp4", 99), FileSource.SERVER_SEARCH));
        List<FileMetadata> added = index.addFiles(List.of(reported(fake.toLowerCase(), "fake movie.mp4", 99),
                reported(1, 2)), FileSource.KAD_SEARCH);
        assertEquals(List.of(hash(1)), added.stream().map(FileMetadata::hash).toList());
        assertFalse(index.contains(fake));
        assertFalse(inDatabase(fake));
        assertEquals(2, index.bannedHashRejectedCount());
        assertTrue(index.isBannedHash(fake.toLowerCase()));
        assertFalse(index.isBannedHash(hash(1)));

        index.setBannedHashPrefixes(() -> "");
        assertNotNull(index.addFile(reported(fake, "fake movie.mp4", 99), FileSource.SERVER_SEARCH),
                "no prefix banned: accepted");
        index.setBannedHashPrefixes(() -> "not hex");
        assertFalse(index.isBannedHash(fake), "an invalid setting bans nothing");
    }

    @Test
    void theFilesOfAHashPrefixArePurgedFromTheIndexAndTheDatabase() throws Exception {
        for (int i = 1; i <= 5; i++) {
            index.addFile(reported(GATEWAY + String.format("%020X", i), "gateway movie " + i + ".mp4", 99),
                    FileSource.SERVER_SEARCH);
        }
        index.addFile(reported(1, 3), FileSource.SERVER_SEARCH);
        ClientState client = client(1);
        ConcurrentHashMap<String, ClientState> sources = new ConcurrentHashMap<>();
        sources.put(FileIndex.sourceKey(client), client);
        index.addFile(new FileMetadata(GATEWAY + String.format("%020X", 1), "gateway movie 1.mp4", 1_000L, "Video",
                List.of(), sources), FileSource.CLIENT_PUSH);

        assertThrows(IllegalArgumentException.class, () -> index.removeFilesWithHashPrefix("ED2B0"), "too short");
        assertThrows(IllegalArgumentException.class, () -> index.removeFilesWithHashPrefix("ED2B01XX"));
        assertEquals(5, index.removeFilesWithHashPrefix(GATEWAY.toLowerCase()));
        assertEquals(1, index.fileCount());
        assertEquals(List.of(hash(1)), index.searchComplex(MOVIE, 100).stream().map(FileMetadata::hash).toList());
        assertEquals(List.of(), index.search("gateway", 10), "the words of their names are forgotten");
        assertEquals(List.of(), index.getSources(GATEWAY + String.format("%020X", 1), null, 10));
        for (int i = 1; i <= 5; i++) assertFalse(inDatabase(GATEWAY + String.format("%020X", i)));
        assertTrue(inDatabase(hash(1)));
        assertEquals(0, index.removeFilesWithHashPrefix(GATEWAY), "none left");
    }
}
