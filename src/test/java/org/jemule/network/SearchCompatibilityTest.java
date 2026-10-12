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
}
