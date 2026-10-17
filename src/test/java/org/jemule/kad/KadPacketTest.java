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

        KadPacket res = KadPacket.createBootstrapRes(list);
        assertEquals(KadOpCode.BOOTSTRAP_RES, res.getOpCode());

        List<KadContact> parsed = KadPacket.parseBootstrapRes(res.data());
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
        assertEquals(4672, payload.udpPort());
        assertEquals((byte) 2, payload.kadVersion());
    }

    @Test
    void testFindNodeReqAndRes() {
        KadId target = KadId.random();
        KadPacket req = KadPacket.createFindNodeReq((byte) 0, target);
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
}
