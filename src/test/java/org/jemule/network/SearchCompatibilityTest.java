package org.jemule.network;

import org.jemule.config.ServerConfig;
import org.jemule.core.ClientRegistry;
import org.jemule.core.FileIndex;
import org.jemule.network.handler.PublishHandler;
import org.jemule.network.handler.SearchHandler;
import org.jemule.protocol.OpCode;
import org.jemule.protocol.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.jemule.network.EmuleTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * OP_SEARCHREQUEST built like GetSearchPacket (SearchResultsWnd.cpp) and OP_SEARCHRESULT decoded like
 * CSearchList::ProcessSearchAnswer / CSearchFile (eMule 0.50a).
 */
class SearchCompatibilityTest {

    FileIndex index;
    ClientRegistry registry;
    FakeContext client;

    @BeforeEach
    void setup() throws Exception {
        index = new FileIndex(null);
        registry = new ClientRegistry();
        client = loggedInContext("10.1.2.3", 50000, ServerConfig.DEFAULT, registry, index);
    }

    void publish(String hexHash, String name, long size, int type) throws Exception {
        byte[] file = offeredFile(hash(hexHash), 0xFBFBFBFB, 0xFBFB,
                new NewTags().str(0x01, name).num(0x02, size & 0xFFFFFFFFL).num(0x03, type));
        if (size > 0xFFFFFFFFL) {
            file = offeredFile(hash(hexHash), 0xFBFBFBFB, 0xFBFB,
                    new NewTags().str(0x01, name).num(0x02, size & 0xFFFFFFFFL).num(0x3A, size >>> 32).num(0x03, type));
        }
        new PublishHandler().handlePublish(client, OpCode.OFFER_FILES, offerFiles(file), new ByteArrayOutputStream());
    }

    SearchAnswer search(byte[] tree) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new SearchHandler().handleSearch(client, tree, out);
        Packet p = Packet.read(new ByteArrayInputStream(out.toByteArray()), 1 << 20);
        assertEquals(Packet.PROTOCOL_ED2K, p.protocol());
        assertEquals(OpCode.SEARCH_RESULT.value, p.opcode());
        return readSearchAnswer(p.data());
    }

    static List<String> names(SearchAnswer a) {
        return a.results().stream().map(r -> (String) r.tag(Tag.NAME_FILENAME)).sorted().toList();
    }

    @Test
    void resultCountIsLittleEndian() throws Exception {
        publish("11111111111111111111111111111111", "holiday video.avi", 700_000_000L, 2);
        SearchAnswer a = search(new SearchTree().keyword("holiday").toBytes());
        assertEquals(1, a.results().size());
        SearchResult r = a.results().getFirst();
        assertEquals("11111111111111111111111111111111", r.hash());
        assertEquals("holiday video.avi", r.tag(Tag.NAME_FILENAME));
        assertEquals("Video", r.tag(Tag.NAME_FILETYPE));
        assertEquals(1, ((Number) r.tag(Tag.NAME_SOURCES)).intValue());
    }

    @Test
    void nonAsciiKeywordsOnlyMatchTheirFiles() throws Exception {
        publish("11111111111111111111111111111111", "Vacances d'été 2025.avi", 700_000_000L, 2);
        publish("22222222222222222222222222222222", "Привет мир.mp3", 5_000_000L, 1);
        publish("33333333333333333333333333333333", "日本語ドラマ 01.mkv", 400_000_000L, 2);
        publish("44444444444444444444444444444444", "unrelated file.txt", 1000L, 5);

        assertEquals(List.of("Vacances d'été 2025.avi"), names(search(new SearchTree().keyword("été").toBytes())));
        assertEquals(List.of("Привет мир.mp3"), names(search(new SearchTree().keyword("ПРИВЕТ").toBytes())));
        assertEquals(List.of("日本語ドラマ 01.mkv"), names(search(new SearchTree().keyword("日本語").toBytes())));
        assertTrue(search(new SearchTree().keyword("中文").toBytes()).results().isEmpty());
    }

    @Test
    void sixtyFourBitSizeParameterIsSupported() throws Exception {
        publish("11111111111111111111111111111111", "big movie.mkv", 6_000_000_000L, 2);
        publish("22222222222222222222222222222222", "small movie.mkv", 700_000_000L, 2);
        // "movie" AND size > 5 GB : with LARGEFILES the client writes the value as uint64 (type 8)
        byte[] tree = new SearchTree().and().keyword("movie").num(0x02, 1, 5_000_000_000L).toBytes();
        assertEquals(List.of("big movie.mkv"), names(search(tree)));
    }

    @Test
    void malformedRequestGetsAnEmptyAnswer() throws Exception {
        publish("11111111111111111111111111111111", "holiday video.avi", 700_000_000L, 2);
        SearchAnswer a = search(new byte[]{0x01, 0x10}); // truncated keyword
        assertTrue(a.results().isEmpty(), "no guess on raw bytes");
    }

    @Test
    void stringMetaTagWithStringNameIsReadWithoutMisalignment() throws Exception {
        publish("11111111111111111111111111111111", "holiday video.avi", 700_000_000L, 2);
        // AND(meta "bitrate"="x" with a string tag name, keyword "holiday")
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(64).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 0).put((byte) 0);
        b.put((byte) 2).putShort((short) 1).put((byte) 'x').putShort((short) 7).put("bitrate".getBytes());
        b.put((byte) 1).putShort((short) 7).put("holiday".getBytes());
        assertEquals(List.of("holiday video.avi"), names(search(java.util.Arrays.copyOf(b.array(), b.position()))));
    }

    @Test
    void sizeBoundsKeepTheClientOperatorsStrict() throws Exception {
        publish("11111111111111111111111111111111", "size 999.bin", 999, 4);
        publish("22222222222222222222222222222222", "size 1000.bin", 1000, 4);
        publish("33333333333333333333333333333333", "size 2000.bin", 2000, 4);
        publish("44444444444444444444444444444444", "size 2001.bin", 2001, 4);
        // User asks for 1000 <= size <= 2000: GetSearchPacket sends size > 999 AND size < 2001
        byte[] tree = new SearchTree().and().and().keyword("size")
                .num(0x02, 1, 999).num(0x02, 2, 2001).toBytes();
        assertEquals(List.of("size 1000.bin", "size 2000.bin"), names(search(tree)));
    }

    @Test
    void availabilityAndCompleteSourcesFilters() throws Exception {
        publish("11111111111111111111111111111111", "popular song.mp3", 5_000_000, 1);
        publish("22222222222222222222222222222222", "rare song.mp3", 5_000_000, 1);
        FakeContext other = loggedInContext("10.4.5.6", 50002, ServerConfig.DEFAULT, registry, index);
        // second source of the popular song, but only a part file (ID 0xFCFCFCFC)
        new PublishHandler().handlePublish(other, OpCode.OFFER_FILES, offerFiles(offeredFile(hash("11111111111111111111111111111111"),
                0xFCFCFCFC, 0xFCFC, new NewTags().str(0x01, "popular song.mp3").num(0x02, 5_000_000).num(0x03, 1))), new ByteArrayOutputStream());

        // availability >= 2 is sent as FT_SOURCES > 1
        assertEquals(List.of("popular song.mp3"), names(search(new SearchTree().and().keyword("song").num(0x15, 1, 1).toBytes())));
        // complete sources >= 2 is sent as FT_COMPLETE_SOURCES > 1: only one complete source
        assertTrue(search(new SearchTree().and().keyword("song").num(0x30, 1, 1).toBytes()).results().isEmpty());

        SearchResult popular = search(new SearchTree().keyword("popular").toBytes()).results().getFirst();
        assertEquals(2, ((Number) popular.tag(Tag.NAME_SOURCES)).intValue());
        assertEquals(1, ((Number) popular.tag(Tag.NAME_COMPLETE_SOURCES)).intValue());
    }

    @Test
    void mediaFilters() throws Exception {
        byte[] withMedia = offeredFile(hash("11111111111111111111111111111111"), 0xFBFBFBFB, 0xFBFB, new NewTags()
                .str(0x01, "concert.avi").num(0x02, 700_000_000).num(0x03, 2)
                .num(0xD3, 3600).num(0xD4, 1200).str(0xD5, "xvid"));
        new PublishHandler().handlePublish(client, OpCode.OFFER_FILES, offerFiles(withMedia), new ByteArrayOutputStream());
        publish("22222222222222222222222222222222", "concert bootleg.avi", 700_000_000, 2);

        assertEquals(List.of("concert.avi"), names(search(new SearchTree().and().keyword("concert").meta(0xD5, "xvid").toBytes())));
        assertEquals(List.of("concert.avi"), names(search(new SearchTree().and().keyword("concert").num(0xD4, 1, 999).toBytes())));
        assertEquals(List.of("concert.avi"), names(search(new SearchTree().and().keyword("concert").num(0xD3, 1, 1799).toBytes())));
        assertEquals(List.of("concert.avi", "concert bootleg.avi").stream().sorted().toList(),
                names(search(new SearchTree().and().keyword("concert").meta(0x03, "Video").toBytes())));
    }
}
