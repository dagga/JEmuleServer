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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #103: the day each file was last seen (files.last_seen), written once a day at most, and the daily purge of
 * the files not seen for fileRetentionDays, out of the database (rows, tags, source counts) and the index.
 */
class FileRetentionTest {
    private static final int DAY = 20_400;
    private static final int RETENTION = 180;

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

    private static FileMetadata file(int i, String name) {
        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag(Tag.TYPE_STRING, Tag.idName(SearchQuery.ID_MEDIA_CODEC), "xvid"));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 5));
        return FileMetadata.withoutSources(hash(i), name, 1_000L + i, "Video", tags);
    }

    private static ClientState client(String ip) throws Exception {
        ClientState st = new ClientState(InetAddress.getByName(ip), 50000, 1, System.currentTimeMillis(), new AtomicLong());
        st.setListenPort(4662);
        return st;
    }

    private Connection sql() throws Exception {
        return DriverManager.getConnection("jdbc:h2:" + dbPath, "sa", "");
    }

    /** The last_seen of a file in the database, null when null, -1 when the file is not there. */
    private Integer lastSeen(int i) throws Exception {
        try (Connection c = sql(); PreparedStatement ps = c.prepareStatement("SELECT last_seen FROM files WHERE hash = ?")) {
            ps.setString(1, hash(i));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return -1;
                int day = rs.getInt(1);
                return rs.wasNull() ? null : day;
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

    private long count(String sql, int i) throws Exception {
        try (Connection c = sql(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, hash(i));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private FilePurgeJob job(int window, int batch) {
        return new FilePurgeJob(db, index, () -> RETENTION, window, batch, 0);
    }

    @Test
    void aSavedFileIsSeenTodayAndAKnownFileOnceADayWhateverTheChannel() throws Exception {
        index.addFile(file(1, "first movie.avi"), FileSource.CLIENT_PUSH);
        assertEquals(DAY, lastSeen(1), "a new file is saved with today");

        // The next day, found by a Kad search: written once
        today.set(DAY + 1);
        index.addFiles(List.of(file(1, "first movie.avi")), FileSource.KAD_SEARCH);
        assertEquals(DAY + 1, lastSeen(1));

        // Seen again the same day by every channel: no write at all (the day changed behind its back stays)
        setLastSeen(1, DAY - 50);
        index.addFiles(List.of(file(1, "first movie.avi")), FileSource.SERVER_SEARCH);
        index.addFiles(List.of(file(1, "first movie.avi")), FileSource.SERVER_INDEX_UPDATE);
        index.addFiles(List.of(file(1, "first movie.avi")), FileSource.RELAY_FEED);
        FileMetadata published = new FileMetadata(hash(1), "first movie.avi", 1_001L, "Video");
        ClientState client = client("10.0.0.1");
        published.sources().put(FileIndex.sourceKey(client), client);
        index.addFile(published, FileSource.CLIENT_PUSH);
        assertEquals(DAY - 50, lastSeen(1), "once a day: the later sightings of the day are not written");

        // Published by a client the day after
        today.set(DAY + 2);
        index.addFile(published, FileSource.CLIENT_PUSH_V1);
        assertEquals(DAY + 2, lastSeen(1));
    }

    @Test
    void theDatabaseNeverMovesTheDayBackNorWritesARowAlreadyUpToDate() throws Exception {
        db.saveFile(file(1, "a.avi"));
        db.saveFile(file(2, "b.avi"));
        setLastSeen(2, DAY + 5);
        db.touchFiles(List.of(hash(1), hash(2), hash(3)));
        assertEquals(DAY, lastSeen(1));
        assertEquals(DAY + 5, lastSeen(2), "a later day is kept");
        assertEquals(-1, lastSeen(3), "a file not saved is not created");
    }

    @Test
    void theSeenFilesAreBoundedAndForgottenTheNextDay() {
        AtomicInteger day = new AtomicInteger(1);
        SeenToday seen = new SeenToday(day::get, 2);
        assertTrue(seen.mark(hash(1)));
        assertFalse(seen.mark(hash(1)), "once a day");
        assertTrue(seen.mark(hash(2)));
        assertTrue(seen.mark(hash(3)), "full: written each time rather than never");
        assertTrue(seen.mark(hash(3)));
        assertFalse(seen.contains(hash(3)));
        day.set(2);
        assertFalse(seen.contains(hash(1)), "forgotten the next day");
        assertTrue(seen.mark(hash(1)));

        assertEquals(393_216, SeenToday.capacityFor(560L * 1024 * 1024), "8 MB of slots for -Xmx560m");
        assertEquals(3 << 20, SeenToday.capacityFor(64L * 1024 * 1024 * 1024), "64 MB at most");
        assertEquals(3_072, SeenToday.capacityFor(1024));
    }

    @Test
    void thePurgeDeletesTheFilesNotSeenForTheRetentionFromTheDatabaseAndTheIndex() throws Exception {
        index.addFile(file(1, "stale movie.avi"));
        index.addFile(file(2, "zzzonlyword stale.avi"));
        index.addFile(file(3, "recent movie.avi"));
        index.addFile(file(4, "stale but shared.avi"));
        index.addFile(file(5, "stale but found again.avi"));
        index.addFile(file(6, "seen on the cutoff day.avi"));
        assertEquals(1, count("SELECT COUNT(*) FROM file_sources WHERE hash = ?", 1), "the H2 source count");
        assertEquals(1, count("SELECT COUNT(*) FROM file_tags WHERE file_hash = ?", 1));
        int wordsBefore = index.wordCount();

        // Shared by a client still connected, which published it yesterday
        today.set(DAY + 399);
        FileMetadata shared = new FileMetadata(hash(4), "stale but shared.avi", 1_004L, "Video");
        ClientState client = client("10.0.0.2");
        shared.sources().put(FileIndex.sourceKey(client), client);
        index.addFile(shared);

        today.set(DAY + 400);
        int cutoff = today.get() - RETENTION;
        for (int i : new int[]{1, 2, 4, 5}) setLastSeen(i, cutoff - 1);
        setLastSeen(3, cutoff + 10);
        setLastSeen(6, cutoff);
        // Found again today
        index.addFiles(List.of(file(5, "stale but found again.avi")), FileSource.KAD_SEARCH);
        assertEquals(today.get(), lastSeen(5));

        FilePurgeJob job = job(2, 1);
        assertTrue(job.start());
        assertTrue(job.awaitEnd(30_000));
        FilePurgeJob.Status status = job.status();
        assertEquals(FilePurgeJob.State.DONE, status.state());
        assertEquals(2, status.purged());
        assertEquals(6, status.scanned());
        assertEquals(2, status.lastRunFiles());
        assertTrue(status.lastRunAt() > 0);

        for (int i : new int[]{1, 2}) {
            assertEquals(-1, lastSeen(i), "deleted from the database");
            assertEquals(0, count("SELECT COUNT(*) FROM file_tags WHERE file_hash = ?", i), "with its tags");
            assertEquals(0, count("SELECT COUNT(*) FROM file_sources WHERE hash = ?", i), "and its source count");
            assertFalse(index.contains(hash(i)), "and from the index");
        }
        for (int i : new int[]{3, 4, 5, 6}) {
            assertTrue(index.contains(hash(i)), "kept: " + i);
            assertNotEquals(-1, lastSeen(i), "kept: " + i);
            assertEquals(1, count("SELECT COUNT(*) FROM file_tags WHERE file_hash = ?", i));
        }
        assertEquals(4, index.fileCount());
        assertTrue(index.search("stale movie", 10).isEmpty());
        assertTrue(index.search("zzzonlyword", 10).isEmpty());
        assertEquals(1, index.search("recent", 10).size());
        assertEquals(wordsBefore - 1, index.wordCount(), "the word of no other file leaves the dictionary");
        assertTrue(index.searchComplex(new SearchQuery.TermQuery("zzzonly", SearchQuery.ID_FILENAME), 10).isEmpty());

        // Recorded: no other run before a day
        assertEquals(status.lastRunAt(), db.getStat(FilePurgeJob.LAST_RUN_STAT, 0));
        assertEquals(2, db.getStat(FilePurgeJob.LAST_COUNT_STAT, 0));
        assertFalse(job.runIfDue(status.lastRunAt() + FilePurgeJob.INTERVAL_MS - 1));
        FilePurgeJob afterRestart = job(2, 1);
        assertEquals(status.lastRunAt(), afterRestart.status().lastRunAt(), "read back at the next start");
        assertFalse(afterRestart.runIfDue(status.lastRunAt() + 1000));
    }

    @Test
    void aFileSeenTodayIsKeptEvenBeforeItsDayIsWritten() throws Exception {
        index.addFile(file(1, "old one.avi"));
        index.addFile(file(2, "other old one.avi"));
        today.set(DAY + 400);
        int cutoff = today.get() - RETENTION;
        setLastSeen(1, cutoff - 1);
        setLastSeen(2, cutoff - 1);

        // The writes wait in the ingestion queue, as on a busy server
        List<Runnable> queued = new ArrayList<>();
        index.setPersistenceExecutor(queued::add);
        index.addFiles(List.of(file(1, "old one.avi")), FileSource.SERVER_SEARCH);
        assertEquals(cutoff - 1, lastSeen(1), "not written yet");

        FileIndex.Purge purge = index.purgeStale(List.of(hash(1), hash(2)), cutoff);
        assertEquals(1, purge.removedFromIndex());
        assertTrue(index.contains(hash(1)), "seen today: kept");
        assertFalse(index.contains(hash(2)));
        queued.forEach(Runnable::run);
        assertEquals(1, purge.deletedFromDatabase().join());
        assertEquals(today.get(), lastSeen(1));
        assertEquals(-1, lastSeen(2));
    }

    @Test
    void theDatabaseKeepsAFileSeenWhileThePurgeRuns() throws Exception {
        db.saveFile(file(1, "a.avi"));
        db.saveFile(file(2, "b.avi"));
        today.set(DAY + 400);
        int cutoff = today.get() - RETENTION;
        setLastSeen(1, cutoff - 1);
        setLastSeen(2, cutoff - 1);
        assertEquals(List.of(hash(1), hash(2)), db.staleFiles("", 10, cutoff).hashes());
        db.touchFiles(List.of(hash(1))); // seen between the read and the deletion
        assertEquals(1, db.deleteStaleFiles(List.of(hash(1), hash(2)), cutoff));
        assertEquals(today.get(), lastSeen(1));
        assertEquals(-1, lastSeen(2));
    }

    @Test
    void noPurgeWithoutRetentionNorWhileTheDatabaseFailsNorTwiceADay() throws Exception {
        index.addFile(file(1, "old.avi"));
        today.set(DAY + 400);
        setLastSeen(1, DAY);
        long now = System.currentTimeMillis();

        FilePurgeJob disabled = new FilePurgeJob(db, index, () -> 0, 10, 10, 0);
        assertFalse(disabled.runIfDue(now), "fileRetentionDays=0: never purged");

        db.circuitBreaker().transitionToForcedOpenState();
        FilePurgeJob job = job(10, 10);
        assertFalse(db.isAvailable());
        assertFalse(job.runIfDue(now), "postponed while the database is failing");
        db.circuitBreaker().transitionToClosedState();

        assertTrue(job.runIfDue(now));
        assertTrue(job.awaitEnd(30_000));
        assertEquals(1, job.status().purged());
        assertFalse(job.runIfDue(now + 3_600_000L), "once a day");
        assertFalse(index.contains(hash(1)));
    }

    @Test
    void aRunStopsCleanlyBetweenTwoBatchesAndIsNotRecorded() throws Exception {
        for (int i = 1; i <= 5; i++) index.addFile(file(i, "file number " + i + ".avi"));
        today.set(DAY + 400);
        for (int i = 1; i <= 5; i++) setLastSeen(i, DAY);

        // One file per batch and a long pause: closed during the first pause
        FilePurgeJob job = new FilePurgeJob(db, index, () -> RETENTION, 1, 1, 60_000);
        assertTrue(job.start());
        long deadline = System.currentTimeMillis() + 30_000;
        while (job.status().purged() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10);
        long start = System.nanoTime();
        job.close();
        assertTrue((System.nanoTime() - start) / 1_000_000 < 10_000, "the pause is cut short");
        assertEquals(FilePurgeJob.State.STOPPED, job.status().state());
        assertEquals(1, job.status().purged());
        assertEquals(0, job.status().lastRunAt(), "a stopped run is not recorded: the next check starts again");
        assertEquals(0, db.getStat(FilePurgeJob.LAST_RUN_STAT, 0));
        assertFalse(job.start(), "closed");
        assertEquals(4, index.fileCount());
    }
}
