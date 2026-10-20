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

import org.jemule.core.FileMetadata;
import org.jemule.protocol.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KadPacketTest {

    private InetAddress testIp(String ip) {
        try {
            return InetAddress.getByName(ip);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testRawPacketEncodeDecode() {
        byte[] payload = new byte[]{1, 2, 3, 4, 5};
        KadPacket pkt = new KadPacket(KadPacket.PROTOCOL_KAD2, KadOpCode.HELLO_REQ.value, payload);

        byte[] raw = pkt.encode();
        assertEquals(2 + payload.length, raw.length);
        assertEquals(KadPacket.PROTOCOL_KAD2, raw[0]);
        assertEquals(KadOpCode.HELLO_REQ.value, raw[1]);

        KadPacket decoded = KadPacket.decode(raw);
        assertEquals(KadPacket.PROTOCOL_KAD2, decoded.protocol());
        assertEquals(KadOpCode.HELLO_REQ.value, decoded.opCode());
        assertEquals(KadOpCode.HELLO_REQ, decoded.getOpCode());
        assertArrayEquals(payload, decoded.data());
    }

    @Test
    void testBootstrapReqAndRes() {
        KadPacket req = KadPacket.createBootstrapReq();
        assertEquals(KadOpCode.BOOTSTRAP_REQ, req.getOpCode());
        assertEquals(0, req.data().length);

        KadContact c1 = new KadContact(KadId.random(), testIp("192.168.1.50"), 4672, 4662, (byte) 2);
        KadContact c2 = new KadContact(KadId.random(), testIp("10.0.0.1"), 4673, 0, (byte) 2);
        List<KadContact> list = List.of(c1, c2);

        KadId responderId = KadId.random();
        KadPacket res = KadPacket.createBootstrapRes(responderId, 4662, (byte) 2, list);
        assertEquals(KadOpCode.BOOTSTRAP_RES, res.getOpCode());

        KadPacket.BootstrapRes parsedRes = KadPacket.parseBootstrapRes(res.data());
        assertNotNull(parsedRes);
        assertEquals(responderId, parsedRes.responderId());
        assertEquals(4662, parsedRes.responderTcpPort());
        assertEquals((byte) 2, parsedRes.responderVersion());
        List<KadContact> parsed = parsedRes.contacts();
        assertEquals(2, parsed.size());
        assertEquals(c1.id(), parsed.get(0).id());
        assertEquals(c1.address(), parsed.get(0).address());
        assertEquals(c1.udpPort(), parsed.get(0).udpPort());
        assertEquals(c1.tcpPort(), parsed.get(0).tcpPort());

        assertEquals(c2.id(), parsed.get(1).id());
        assertEquals(c2.address(), parsed.get(1).address());
        assertEquals(c2.udpPort(), parsed.get(1).udpPort());
    }

    @Test
    void testHelloReqAndRes() {
        KadId id = KadId.random();
        KadPacket hello = KadPacket.createHelloReq(id, 4672, (byte) 2);
        assertEquals(KadOpCode.HELLO_REQ, hello.getOpCode());

        KadPacket.HelloPayload payload = KadPacket.parseHello(hello.data());
        assertNotNull(payload);
        assertEquals(id, payload.senderId());
        assertEquals(4672, payload.tcpPort());
        assertEquals((byte) 2, payload.kadVersion());
    }

    @Test
    void testFindNodeReqAndRes() {
        KadId target = KadId.random();
        KadPacket req = KadPacket.createFindNodeReq(KadId.random(), (byte) 8, target);
        assertEquals(KadOpCode.REQ, req.getOpCode());

        KadContact c = new KadContact(KadId.random(), testIp("8.8.8.8"), 4672, 4662, (byte) 2);
        KadPacket res = KadPacket.createFindNodeRes(target, List.of(c));
        assertEquals(KadOpCode.RES, res.getOpCode());

        KadPacket.FindNodeResult parsed = KadPacket.parseFindNodeRes(res.data());
        assertNotNull(parsed);
        assertEquals(target, parsed.targetId());
        assertEquals(1, parsed.contacts().size());
        assertEquals(c.id(), parsed.contacts().get(0).id());
    }

    @Test
    void testSearchKeyReq() {
        KadId keyword = KadId.fromKeyword("ubuntu");
        KadPacket pkt = KadPacket.createSearchKeyReq(keyword, 0);
        assertEquals(KadOpCode.SEARCH_KEY_REQ, pkt.getOpCode());

        KadPacket.SearchKeyReq parsed = KadPacket.parseSearchKeyReq(pkt.data());
        assertNotNull(parsed);
        assertEquals(keyword, parsed.keywordHash());
        assertEquals(0, parsed.startPosition());
    }

    @Test
    void testSearchResWithFileMetadata() {
        KadId target = KadId.fromKeyword("linux");
        String hash = "0123456789abcdef0123456789abcdef";
        FileMetadata meta = new FileMetadata(hash, "ubuntu-24.04.iso", 4500000000L, "Archive",
                List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 42)));

        KadPacket resPkt = KadPacket.createSearchRes(target, List.of(meta));
        assertEquals(KadOpCode.SEARCH_RES, resPkt.getOpCode());

        KadPacket.SearchRes parsed = KadPacket.parseSearchRes(resPkt.data());
        assertNotNull(parsed);
        assertEquals(target, parsed.targetId());
        assertEquals(1, parsed.files().size());

        FileMetadata decodedMeta = parsed.files().get(0);
        assertEquals(hash, decodedMeta.hash());
        assertEquals("ubuntu-24.04.iso", decodedMeta.name());
        assertEquals(4500000000L, decodedMeta.size());
        assertEquals("Archive", decodedMeta.type());
    }

    @Test
    void parsesEmuleKad2SearchResponseWithSourceIdAndSingleByteTagCount() {
        KadId source = KadId.fromKeyword("source-node");
        KadId target = KadId.fromKeyword("linux");
        byte[] hash = new byte[16];
        Arrays.fill(hash, (byte) 0x42);
        String hashHex = org.jemule.network.handler.HandlerUtils.bytesToHashString(hash);

        ByteBuffer buf = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(source.toWire());
        buf.put(target.toWire());
        buf.putShort((short) 1);
        buf.put(hash); // 0x42 repeated: identical in wire and big-endian forms
        buf.put((byte) 3);
        new Tag(Tag.TYPE_STRING, Tag.NAME_FILENAME, "ubuntu-24.04.iso").writeKad(buf);
        new Tag(Tag.TYPE_INTEGER, Tag.NAME_FILESIZE, 4500).writeKad(buf);
        new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 7).writeKad(buf);

        KadPacket packet = new KadPacket(KadOpCode.SEARCH_RES, Arrays.copyOf(buf.array(), buf.position()));
        KadPacket.SearchRes parsed = KadPacket.parseSearchRes(packet.data());
        assertNotNull(parsed);
        assertEquals(target, parsed.targetId());
        assertEquals(1, parsed.files().size());
        assertEquals(hashHex, parsed.files().get(0).hash());
        assertEquals("ubuntu-24.04.iso", parsed.files().get(0).name());
        assertEquals(4500L, parsed.files().get(0).size());
    }

    @Test
    void keywordTargetIsSentAsCUInt128() {
        // eMule hashes the keyword with MD4, reads the digest big-endian (SetValueBE) and writes it as 4 LE words
        byte[] md4 = MD4.digest("linux");
        byte[] expected = new byte[16];
        for (int i = 0; i < 16; i++) {
            expected[i] = md4[(i & ~3) + 3 - (i & 3)];
        }
        KadPacket req = KadPacket.createSearchKeyReq(KadId.fromKeyword("Linux"), 0);
        assertArrayEquals(expected, Arrays.copyOf(req.data(), 16));
        assertEquals(KadId.fromKeyword("linux"), KadPacket.parseSearchKeyReq(req.data()).keywordHash());
    }

    @Test
    void searchResultFileHashIsSentAsCUInt128() {
        FileMetadata meta = FileMetadata.withoutSources("000102030405060708090A0B0C0D0E0F", "a.iso", 10L, "", List.of());
        byte[] data = KadPacket.createSearchRes(KadId.zero(), KadId.fromKeyword("iso"), List.of(meta)).data();
        assertArrayEquals(KadId.fromHex("000102030405060708090a0b0c0d0e0f").toWire(), Arrays.copyOfRange(data, 34, 50));
        assertEquals("000102030405060708090a0b0c0d0e0f",
                KadPacket.parseSearchRes(data).files().get(0).hash().toLowerCase(java.util.Locale.ROOT));
    }

    @Test
    void restrictiveSearchFlagsTheStartPositionAndCarriesTheSearchTerms() {
        byte[] terms = {0x01, 0x03, 0x00, 'i', 's', 'o'};
        KadPacket req = KadPacket.createSearchKeyReq(KadId.fromKeyword("linux"), 300, terms);
        assertEquals(300 | 0x8000, Short.toUnsignedInt(ByteBuffer.wrap(req.data(), 16, 2).order(ByteOrder.LITTLE_ENDIAN).getShort()));
        assertArrayEquals(terms, Arrays.copyOfRange(req.data(), 18, req.data().length));

        KadPacket.SearchKeyReq parsed = KadPacket.parseSearchKeyReq(req.data());
        assertEquals(300, parsed.startPosition());
        assertArrayEquals(terms, parsed.searchTerms());
        assertNull(KadPacket.parseSearchKeyReq(KadPacket.createSearchKeyReq(KadId.fromKeyword("linux"), 0).data()).searchTerms());
        assertThrows(IllegalArgumentException.class,
                () -> KadPacket.createSearchKeyReq(KadId.fromKeyword("linux"), KadPacket.MAX_START_POSITION + 1));
    }

    @Test
    void decodesPackedKadPacket() {
        KadId target = KadId.fromKeyword("ubuntu");
        FileMetadata meta = FileMetadata.withoutSources("0123456789ABCDEF0123456789ABCDEF", "ubuntu-24.04.iso",
                4_500_000_000L, "Archive", List.of());
        byte[] payload = KadPacket.createSearchRes(KadId.random(), target, List.of(meta)).data();

        java.util.zip.Deflater deflater = new java.util.zip.Deflater();
        deflater.setInput(payload);
        deflater.finish();
        byte[] compressed = new byte[payload.length + 64];
        int len = deflater.deflate(compressed);
        deflater.end();
        byte[] raw = new byte[2 + len];
        raw[0] = KadPacket.PROTOCOL_KAD2_PACKED;
        raw[1] = KadOpCode.SEARCH_RES.value;
        System.arraycopy(compressed, 0, raw, 2, len);

        KadPacket decoded = KadPacket.decode(raw);
        assertEquals(KadOpCode.SEARCH_RES, decoded.getOpCode());
        KadPacket.SearchRes parsed = KadPacket.parseSearchRes(decoded.data());
        assertNotNull(parsed);
        assertEquals(target, parsed.targetId());
        assertEquals("ubuntu-24.04.iso", parsed.files().get(0).name());

        byte[] corrupted = {KadPacket.PROTOCOL_KAD2_PACKED, KadOpCode.SEARCH_RES.value, 1, 2, 3};
        assertThrows(IllegalArgumentException.class, () -> KadPacket.decode(corrupted));
    }
}
