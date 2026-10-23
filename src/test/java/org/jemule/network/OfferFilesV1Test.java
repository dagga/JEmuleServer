package org.jemule.network;

import org.jemule.config.OfferFilesConfig;
import org.jemule.config.ServerConfig;
import org.jemule.core.ClientRegistry;
import org.jemule.core.FileIndex;
import org.jemule.core.FileSource;
import org.jemule.core.OfferFilesSession;
import org.jemule.core.OfferFilesV1;
import org.jemule.network.handler.LoginHandler;
import org.jemule.network.handler.PublishHandler;
import org.jemule.protocol.OpCode;
import org.jemule.protocol.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.jemule.network.EmuleTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * OFFERFILES v1 (doc/offerfiles-v1.md), the contract agreed with aMule (PR #1715) and ed2k-server (issue #19): a client
 * asks at login, only then is it answered in OP_SERVERIDENT and held to the batch size and pace.
 */
class OfferFilesV1Test {
    private static final long MS = 1_000_000L;

    /** No HighID test (lowIdCheckTimeoutSeconds = 0): the login completes without connecting back. */
    private static ServerConfig config(int maxFilesPerUser, OfferFilesConfig offerFiles) {
        return new ServerConfig(14665, 2 * 1024 * 1024, 300, 50, 1000, 100000, maxFilesPerUser, 200,
                null, null, false, 50f, 10, 60, 1800, 0, "127.0.0.1", 0).withOfferFiles(offerFiles);
    }

    /** Small values, as in the ed2k-server/aMule interoperability fixtures: batch 2, 200 ms, no ceiling. */
    private static final ServerConfig V1 = config(1000, new OfferFilesConfig(true, 2, 200, 0));
    private static final ServerConfig V1_OFF = config(1000, new OfferFilesConfig(false, 2, 200, 0));

    /** Clock whose sleeps only move time forward, and remember how long they were. */
    private static final class FakeClock implements OfferFilesV1.Clock {
        long now = 1_000_000_000L;
        final List<Long> sleeps = new ArrayList<>();

        @Override public long nanoTime() { return now; }
        @Override public void sleep(long nanos) { sleeps.add(nanos); now += nanos; }
    }

    private final FakeClock clock = new FakeClock();
    private final OfferFilesV1 offerFiles = new OfferFilesV1(clock);
    private final FileIndex index = new FileIndex(null);
    private final PublishHandler publish = new PublishHandler();

    /** OP_LOGINREQUEST of aMule with experimental publication enabled: a fifth, string-named tag offerfiles_v. */
    private static byte[] loginAsking(byte type, int version) {
        byte[] base = loginRequest(new byte[16], LISTEN_PORT, "tester", EMULE_DEFAULT_SRVCAPS);
        byte[] name = Tag.NAME_OFFERFILES_VERSION.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer b = ByteBuffer.allocate(base.length + 3 + name.length + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.put(base).put(type).putShort((short) name.length).put(name);
        if (type == Tag.TYPE_INT16) b.putShort((short) version); else b.putInt(version);
        b.putInt(16 + 4 + 2, 5);
        byte[] out = new byte[b.position()];
        System.arraycopy(b.array(), 0, out, 0, out.length);
        return out;
    }

    private EmuleTestSupport.FakeContext login(ServerConfig config, byte[] request, ByteArrayOutputStream out) throws Exception {
        EmuleTestSupport.FakeContext ctx = new EmuleTestSupport.FakeContext(
                new DummySocket(InetAddress.getLoopbackAddress(), 51000), config, new ClientRegistry(), index);
        ctx.setOfferFilesV1(offerFiles);
        new LoginHandler().handleLogin(ctx, new Packet(Packet.PROTOCOL_ED2K, OpCode.LOGIN_REQUEST.value, request), out);
        return ctx;
    }

    private EmuleTestSupport.FakeContext loginAsking(ServerConfig config) throws Exception {
        return login(config, loginAsking(Tag.TYPE_INTEGER, 1), new ByteArrayOutputStream());
    }

    private static List<Packet> packets(ByteArrayOutputStream out) throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        List<Packet> packets = new ArrayList<>();
        while (in.available() > 0) packets.add(Packet.read(in, 1 << 20));
        return packets;
    }

    private static List<Tag> serverIdentTags(ByteArrayOutputStream out) throws Exception {
        List<Tag> tags = null;
        for (Packet p : packets(out)) {
            if (p.opcode() == OpCode.SERVER_IDENT.value) {
                assertNull(tags, "one OP_SERVERIDENT per connection");
                ByteBuffer b = ByteBuffer.wrap(p.data()).order(ByteOrder.LITTLE_ENDIAN);
                b.position(16 + 4 + 2);
                tags = Tag.readList(b);
            }
        }
        assertNotNull(tags, "no OP_SERVERIDENT");
        return tags;
    }

    private static List<String> serverMessages(ByteArrayOutputStream out) throws Exception {
        List<String> messages = new ArrayList<>();
        for (Packet p : packets(out)) {
            if (p.opcode() == OpCode.SERVER_MESSAGE.value) {
                messages.add(new String(p.data(), 2, p.data().length - 2, StandardCharsets.UTF_8));
            }
        }
        return messages;
    }

    private static Tag onlyTag(List<Tag> tags, String name) {
        List<Tag> found = tags.stream().filter(t -> t.name().equals(name)).toList();
        assertEquals(1, found.size(), "tag " + name + " exactly once");
        return found.get(0);
    }

    private static boolean hasOfferFilesTags(List<Tag> tags) {
        return tags.stream().anyMatch(t -> t.name().startsWith("offerfiles_") || t.name().startsWith("offer_"));
    }

    private static byte[] offerOf(int first, int count) {
        byte[][] files = new byte[count][];
        for (int i = 0; i < count; i++) {
            files[i] = completeFile(hash(String.format("%032x", first + i)), "shared file " + (first + i) + ".avi", 700_000_000L);
        }
        return offerFiles(files);
    }

    private ByteArrayOutputStream offer(EmuleTestSupport.FakeContext ctx, byte[] payload) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        publish.handlePublish(ctx, OpCode.OFFER_FILES, payload, out);
        return out;
    }

    @Test
    void aClientThatAsksGetsTheCompleteAdvertisement() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EmuleTestSupport.FakeContext ctx = login(V1, loginAsking(Tag.TYPE_INTEGER, 1), out);

        List<Tag> tags = serverIdentTags(out);
        for (String name : List.of(Tag.NAME_OFFERFILES_VERSION, Tag.NAME_OFFERFILES_BATCH_MAX, Tag.NAME_OFFERFILES_MIN_INTERVAL_MS,
                Tag.NAME_SOFTFILES, Tag.NAME_HARDFILES)) {
            assertEquals(Tag.TYPE_INTEGER, onlyTag(tags, name).type(), name + " is a uint32");
        }
        assertEquals(1, onlyTag(tags, Tag.NAME_OFFERFILES_VERSION).unsignedValue());
        assertEquals(2, onlyTag(tags, Tag.NAME_OFFERFILES_BATCH_MAX).unsignedValue());
        assertEquals(200, onlyTag(tags, Tag.NAME_OFFERFILES_MIN_INTERVAL_MS).unsignedValue());
        assertEquals(1000, onlyTag(tags, Tag.NAME_SOFTFILES).unsignedValue(), "ST_SOFTFILES is maxFilesPerUser");
        assertEquals(PublishHandler.HARD_FILES, onlyTag(tags, Tag.NAME_HARDFILES).unsignedValue());
        assertTrue(tags.stream().noneMatch(t -> t.name().equals("offer_burst")));

        OfferFilesSession session = ctx.getState().offerFilesSession();
        assertNotNull(session, "the advertised terms bind the connection");
        assertEquals(2, session.batchMax());
        assertEquals(200, session.minIntervalMs());
        assertEquals(1, offerFiles.loginsAsked());
        assertEquals(1, offerFiles.sessionsAdvertised());
    }

    @Test
    void aClientThatDoesNotAskIsLegacyEvenWithV1Enabled() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EmuleTestSupport.FakeContext ctx = login(V1, loginRequest(new byte[16], LISTEN_PORT, "tester", EMULE_DEFAULT_SRVCAPS), out);

        assertFalse(hasOfferFilesTags(serverIdentTags(out)));
        assertNull(ctx.getState().offerFilesSession());

        // More than offerfiles_batch_max, back to back: published at once, as on a legacy server
        offer(ctx, offerOf(1, 3));
        offer(ctx, offerOf(10, 3));
        assertEquals(6, index.fileCount());
        assertTrue(clock.sleeps.isEmpty());
        assertEquals(0, offerFiles.batches());
        assertEquals(0, offerFiles.loginsAsked());
        assertEquals(6, index.newFileCount(FileSource.CLIENT_PUSH));
        assertEquals(0, index.newFileCount(FileSource.CLIENT_PUSH_V1));
    }

    @Test
    void askingWhileV1IsDisabledChangesNothing() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EmuleTestSupport.FakeContext ctx = login(V1_OFF, loginAsking(Tag.TYPE_INTEGER, 1), out);

        assertFalse(hasOfferFilesTags(serverIdentTags(out)));
        assertNull(ctx.getState().offerFilesSession());
        offer(ctx, offerOf(1, 3));
        assertEquals(3, index.fileCount());
        assertEquals(1, offerFiles.loginsAsked());
        assertEquals(0, offerFiles.sessionsAdvertised());
    }

    @Test
    void aRequestOfAnotherTypeOrVersionZeroIsLegacy() throws Exception {
        ByteArrayOutputStream zero = new ByteArrayOutputStream();
        assertNull(login(V1, loginAsking(Tag.TYPE_INTEGER, 0), zero).getState().offerFilesSession());
        assertFalse(hasOfferFilesTags(serverIdentTags(zero)));

        ByteArrayOutputStream uint16 = new ByteArrayOutputStream();
        assertNull(login(V1, loginAsking(Tag.TYPE_INT16, 1), uint16).getState().offerFilesSession());
        assertFalse(hasOfferFilesTags(serverIdentTags(uint16)));
    }

    @Test
    void aHigherVersionGetsVersionOne() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        login(V1, loginAsking(Tag.TYPE_INTEGER, 2), out);
        assertEquals(1, onlyTag(serverIdentTags(out), Tag.NAME_OFFERFILES_VERSION).unsignedValue());
    }

    @Test
    void anEarlyBatchWaitsAndIsThenIndexed() throws Exception {
        EmuleTestSupport.FakeContext ctx = loginAsking(V1);

        offer(ctx, offerOf(1, 2));
        assertTrue(clock.sleeps.isEmpty(), "the first batch goes at once");
        offer(ctx, offerOf(10, 2));
        assertEquals(List.of(200 * MS), clock.sleeps, "delivered together by TCP: the second batch waits its turn");
        offer(ctx, offerOf(20, 1));
        assertEquals(List.of(200 * MS, 100 * MS), clock.sleeps, "a smaller batch costs proportionally less");

        assertEquals(5, index.fileCount(), "nothing compliant is dropped");
        assertEquals(5, index.newFileCount(FileSource.CLIENT_PUSH_V1), "the admin panel shows what v1 clients brought");
        assertEquals(0, index.newFileCount(FileSource.CLIENT_PUSH));
        assertFalse(ctx.isDisconnected());
        assertEquals(3, offerFiles.batches());
        assertEquals(5, offerFiles.records());
        assertEquals(2, offerFiles.paceWaits());
    }

    @Test
    void aBatchSentOnScheduleNeverWaits() throws Exception {
        EmuleTestSupport.FakeContext ctx = loginAsking(V1);
        for (int i = 0; i < 10; i++) {
            offer(ctx, offerOf(i * 10, 2));
            clock.now += 200 * MS;
        }
        assertTrue(clock.sleeps.isEmpty());
        assertEquals(20, index.fileCount());
    }

    @Test
    void aBatchAboveBatchMaxIsIgnoredAndTheSessionStaysUp() throws Exception {
        EmuleTestSupport.FakeContext ctx = loginAsking(V1);

        ByteArrayOutputStream out = offer(ctx, offerOf(1, 3));
        assertEquals(0, index.fileCount(), "the whole batch is ignored, not truncated");
        assertFalse(ctx.isDisconnected());
        assertEquals(List.of("WARNING : 3 shared file(s) not published: OFFERFILES v1 allows at most 2 files per packet"),
                serverMessages(out));
        assertTrue(serverMessages(offer(ctx, offerOf(10, 3))).isEmpty(), "told once per connection");
        assertEquals(2, offerFiles.oversizedBatches());
        assertTrue(clock.sleeps.isEmpty(), "an ignored batch costs no pace");

        offer(ctx, offerOf(20, 2));
        assertEquals(2, index.fileCount(), "a valid batch afterwards is indexed");
    }

    @Test
    void anOfferDeclaringHardFilesOrMoreClosesEveryConnection() throws Exception {
        byte[] huge = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(PublishHandler.HARD_FILES).array();

        EmuleTestSupport.FakeContext v1 = loginAsking(V1);
        offer(v1, huge);
        assertTrue(v1.isDisconnected());

        EmuleTestSupport.FakeContext legacy = login(V1, loginRequest(new byte[16], LISTEN_PORT, "legacy", EMULE_DEFAULT_SRVCAPS),
                new ByteArrayOutputStream());
        offer(legacy, huge);
        assertTrue(legacy.isDisconnected());
        assertEquals(2, offerFiles.hardLimitRejects());

        EmuleTestSupport.FakeContext below = loginAsking(config(1000, OfferFilesConfig.DEFAULT));
        offer(below, offerOf(1, 300));
        assertFalse(below.isDisconnected(), "a legacy client may send any count below ST_HARDFILES");
        assertEquals(300, index.fileCount());
    }

    @Test
    void theSoftBudgetCountsDistinctFilesAndReOffersAreRefreshes() throws Exception {
        EmuleTestSupport.FakeContext ctx = loginAsking(config(3, new OfferFilesConfig(true, 2, 200, 0)));

        offer(ctx, offerOf(1, 2));
        ByteArrayOutputStream out = offer(ctx, offerOf(3, 2));
        assertEquals(3, index.fileCount(), "the fourth new file is over ST_SOFTFILES");
        assertEquals(List.of("WARNING : This server accepts 3 shares per client. Some of your shares are ignored."),
                serverMessages(out));
        assertFalse(ctx.isDisconnected());

        // A re-offer of a file the client already shares (rename, completion) is accepted, a new file is not
        ByteArrayOutputStream again = offer(ctx, offerFiles(
                completeFile(hash(String.format("%032x", 1)), "renamed.avi", 700_000_000L),
                completeFile(hash(String.format("%032x", 50)), "new.avi", 700_000_000L)));
        assertEquals(3, index.fileCount());
        assertEquals(3, ctx.getState().publishedHashes().size());
        assertTrue(serverMessages(again).isEmpty(), "the soft limit message is sent once per connection");
        assertEquals(2, offerFiles.softLimitIgnored());
        assertEquals(6, offerFiles.records(), "a re-offer still counts toward the pace");
    }

    @Test
    void filteredFilesUseNoSoftBudget() throws Exception {
        EmuleTestSupport.FakeContext ctx = loginAsking(config(2, new OfferFilesConfig(true, 2, 200, 0)));
        offer(ctx, offerFiles(completeFile(hash(String.format("%032x", 1)), "", 700_000_000L),
                completeFile(hash(String.format("%032x", 2)), "valid.avi", 700_000_000L)));
        offer(ctx, offerOf(10, 1));
        assertEquals(2, index.fileCount());
        assertEquals(0, offerFiles.softLimitIgnored());
    }

    @Test
    void theTermsAdvertisedAtLoginHoldForTheWholeConnection() throws Exception {
        EmuleTestSupport.FakeContext a = loginAsking(config(3, new OfferFilesConfig(true, 2, 200, 0)));

        // The configuration changes: A keeps its batch size, pace and soft budget, a new login B gets the new terms
        ServerConfig changed = config(1000, new OfferFilesConfig(true, 50, 1000, 0));
        EmuleTestSupport.FakeContext aLive = new EmuleTestSupport.FakeContext(a.getSocket(), changed, a.getRegistry(), index);
        aLive.setState(a.getState());
        aLive.setOfferFilesV1(offerFiles);
        offer(aLive, offerOf(1, 3));
        assertEquals(0, index.fileCount(), "A is still held to batch 2");
        offer(aLive, offerOf(1, 2));
        offer(aLive, offerOf(3, 2));
        assertEquals(3, index.fileCount(), "A keeps its soft budget of 3");
        assertEquals(List.of(200 * MS), clock.sleeps, "A keeps its 200 ms pace");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EmuleTestSupport.FakeContext b = login(changed, loginAsking(Tag.TYPE_INTEGER, 1), out);
        assertEquals(50, onlyTag(serverIdentTags(out), Tag.NAME_OFFERFILES_BATCH_MAX).unsignedValue());
        assertEquals(50, b.getState().offerFilesSession().batchMax());
    }

    @Test
    void theCeilingSlowsV1PublishersButNotLegacyOnes() throws Exception {
        ServerConfig ceiling = config(1000, new OfferFilesConfig(true, 2, 200, 2));
        EmuleTestSupport.FakeContext a = loginAsking(ceiling);
        EmuleTestSupport.FakeContext b = loginAsking(ceiling);
        EmuleTestSupport.FakeContext legacy = login(ceiling, loginRequest(new byte[16], LISTEN_PORT, "legacy", EMULE_DEFAULT_SRVCAPS),
                new ByteArrayOutputStream());

        offer(a, offerOf(1, 2));
        assertTrue(clock.sleeps.isEmpty());
        offer(b, offerOf(10, 2));
        assertEquals(List.of(1000 * MS), clock.sleeps, "2 files per second for all v1 connections together");
        offer(legacy, offerOf(20, 50));
        assertEquals(1, clock.sleeps.size(), "legacy publishers are not counted against the ceiling");

        assertEquals(54, index.fileCount());
        assertEquals(1, offerFiles.ceilingWaits());
        assertEquals(0, (long) offerFiles.stats().get("ceilingWaitingNow"));
    }

    @Test
    void keepAlivesCostNothing() throws Exception {
        EmuleTestSupport.FakeContext ctx = loginAsking(V1);
        for (int i = 0; i < 20; i++) {
            offer(ctx, offerFiles());
        }
        offer(ctx, offerOf(1, 2));
        assertTrue(clock.sleeps.isEmpty());
        assertEquals(0, offerFiles.batches() - 1);
    }
}
