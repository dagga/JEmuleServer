package org.jemule.kad;

import org.jemule.network.handler.HandlerUtils;
import org.jemule.protocol.Tag;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** KADEMLIA2_SEARCH_SOURCE_REQ and the sources of a KADEMLIA2_SEARCH_RES, as eMule writes and reads them. */
class KadSourcePacketTest {
    private static final byte[] FILE = HandlerUtils.hashToBytes("0123456789ABCDEF0123456789ABCDEF");
    private static final byte[] USER = HandlerUtils.hashToBytes("FEDCBA9876543210FEDCBA9876543210");

    @Test
    void sourceRequestCarriesTheFileHashAndItsSize() {
        KadPacket req = KadPacket.createSearchSourceReq(new KadId(FILE), 5_000_000_000L);
        assertEquals(KadOpCode.SEARCH_SOURCE_REQ, req.getOpCode());
        ByteBuffer b = ByteBuffer.wrap(req.data()).order(ByteOrder.LITTLE_ENDIAN);
        byte[] target = new byte[16];
        b.get(target);
        assertArrayEquals(FILE, KadId.fromWire(target).getBytes(), "target = file hash, as a CUInt128");
        assertEquals(0, b.getShort(), "start position");
        assertEquals(5_000_000_000L, b.getLong());
        assertFalse(b.hasRemaining());
    }

    /** One KADEMLIA2_SEARCH_RES answer: <user hash 16><tag count 1><tags>. */
    private static void answer(ByteBuffer b, byte[] user, int type, int ip, int port, int crypt) {
        b.put(new KadId(user).toWire());
        List<Tag> tags = List.of(new Tag(Tag.TYPE_INT8, Tag.idName(0xFF), (byte) type),
                new Tag(Tag.TYPE_INTEGER, Tag.idName(0xFE), ip),
                new Tag(Tag.TYPE_INT16, Tag.idName(0xFD), (short) port),
                new Tag(Tag.TYPE_INT16, Tag.idName(0xFC), (short) 4672),
                new Tag(Tag.TYPE_INT8, Tag.idName(0xF3), (byte) crypt));
        b.put((byte) tags.size());
        tags.forEach(t -> t.writeKad(b));
    }

    @Test
    void sourceAnswersAreReadWithTheirTypeIpPortAndCryptOptions() {
        ByteBuffer b = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[16]).put(new KadId(FILE).toWire()).putShort((short) 2);
        answer(b, USER, 4, 0x5BD86E2A, 4662, 0x03);
        answer(b, FILE, 3, 0, 0, 0);

        KadPacket.SourceRes res = KadPacket.parseSourceRes(java.util.Arrays.copyOf(b.array(), b.position()));
        assertArrayEquals(FILE, res.targetId().getBytes());
        assertEquals(2, res.sources().size());
        KadPacket.KadSource direct = res.sources().get(0);
        assertArrayEquals(USER, direct.userHash());
        assertEquals(4, direct.type());
        assertTrue(direct.isDirect());
        assertEquals(0x5BD86E2A, direct.ip());
        assertEquals(4662, direct.tcpPort());
        assertEquals(0x03, direct.cryptOptions());
        assertFalse(res.sources().get(1).isDirect(), "type 3: firewalled, reached through a buddy");
    }
}
