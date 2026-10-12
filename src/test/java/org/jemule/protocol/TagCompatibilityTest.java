package org.jemule.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tag decoding checked against byte sequences produced exactly like eMule 0.50a does
 * (CTag::WriteNewEd2kTag / CTag::WriteTagToFile in packets.cpp).
 */
class TagCompatibilityTest {

    private static ByteBuffer le(String hex) {
        return ByteBuffer.wrap(HexFormat.of().parseHex(hex.replace(" ", ""))).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    void compactStringTypesAreDecoded() {
        // 0x94 = STR4 (0x14) | 0x80, name ID 0xD5 (FT_MEDIA_CODEC), value "xvid"
        Tag t = Tag.read(le("94 D5 78 76 69 64"));
        assertEquals(Tag.TYPE_STRING, t.type());
        assertEquals("Õ", t.name());
        assertEquals("xvid", t.value());

        // 0x91 = STR1 | 0x80, 0xA0 = STR16 | 0x80
        assertEquals("a", Tag.read(le("91 01 61")).value());
        assertEquals("0123456789abcdef", Tag.read(le("A0 01 30313233343536373839616263646566")).value());
    }

    @Test
    void uint64TagUsesType0x0B() {
        Tag t = Tag.read(le("8B 02 0100000001000000"));
        assertEquals(Tag.TYPE_UINT64, t.type());
        assertEquals(0x100000001L, t.value());
    }

    @Test
    void boolArrayAndBsobAreSkippedCorrectly() {
        // BOOLARRAY: 9 bits -> (9/8)+1 = 2 bytes; then a uint8 tag must still be readable
        ByteBuffer buf = le("02000000 86 01 0900 FF01 89 15 07");
        List<Tag> tags = Tag.readList(buf);
        assertEquals(2, tags.size());
        assertEquals(Tag.TYPE_BOOLARRAY, tags.get(0).type());
        assertEquals("\u0015", tags.get(1).name());
        assertFalse(buf.hasRemaining());

        Tag bsob = Tag.read(le("8A 01 03 AABBCC"));
        assertArrayEquals(new byte[]{(byte) 0xAA, (byte) 0xBB, (byte) 0xCC}, (byte[]) bsob.value());
    }

    @Test
    void oldFormatOneByteNameIsANumericId() {
        // WriteTagToFile(CT_EMULE_VERSION=0xFB, uint32): <type 03><namelen 0100><name FB><value>
        Tag t = Tag.read(le("03 0100 FB 00808000"));
        assertEquals("û", t.name());
        assertEquals(Tag.TYPE_INTEGER, t.type());
    }

    @Test
    void offeredFileTagListFromNewTagsClientIsFullyRead() {
        // Tag list as written by CreateOfferedFilePacket for a NEWTAGS server:
        // FT_FILENAME (STRING, 21 bytes), FT_FILESIZE (UINT32), FT_FILETYPE (UINT8 = 2 video), FT_MEDIA_CODEC (STR4 "xvid")
        String name = HexFormat.of().formatHex("my holiday video.avi!".getBytes());
        ByteBuffer buf = le("04000000"
                + "82 01 1500 " + name
                + "83 02 00E1F505"
                + "89 03 02"
                + "94 D5 78766964");
        List<Tag> tags = Tag.readList(buf);
        assertEquals(4, tags.size());
        assertEquals("my holiday video.avi!", tags.get(0).value());
        assertEquals("xvid", tags.get(3).value());
        assertFalse(buf.hasRemaining());
    }

    @Test
    void integerTagsAreUnsigned() {
        assertEquals(3_000_000_000L, Tag.read(le("83 02 005ED0B2")).unsignedValue()); // UINT32 3 GB
        assertEquals(40_000L, Tag.read(le("88 02 409C")).unsignedValue());          // UINT16 0x9C40
        assertEquals(200L, Tag.read(le("89 02 C8")).unsignedValue());               // UINT8 0xC8
        assertTrue(Tag.read(le("89 02 C8")).isInt());
        assertFalse(new Tag(Tag.TYPE_STRING, "\u0001", "x").isInt());
    }
}
