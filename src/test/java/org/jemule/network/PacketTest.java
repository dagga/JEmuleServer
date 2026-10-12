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

package org.jemule.network;

import org.jemule.protocol.OpCode;
import org.jemule.protocol.Tag;
import org.jemule.security.Obfuscation;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PacketTest {

    @Test
    void testRC4() {
        byte[] key = "testkey".getBytes();
        byte[] data = "Hello, Obfuscation!".getBytes();
        byte[] original = data.clone();

        Obfuscation.RC4 rc4Send = new Obfuscation.RC4(key);
        Obfuscation.RC4 rc4Receive = new Obfuscation.RC4(key);

        rc4Send.crypt(data);
        assertFalse(Arrays.equals(original, data));

        rc4Receive.crypt(data);
        assertArrayEquals(original, data);
    }

    @Test
    void testReadWrite() throws IOException {
        byte protocol = Packet.PROTOCOL_ED2K;
        byte opcode = (byte) 0x01;
        byte[] payload = "Hello World".getBytes();
        Packet packet = new Packet(protocol, opcode, payload);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        packet.write(out, false);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        Packet readPacket = Packet.read(in, 1024);

        assertEquals(protocol, readPacket.protocol());
        assertEquals(opcode, readPacket.opcode());
        assertArrayEquals(payload, readPacket.data());
    }

    @Test
    void testHeaderLengthIncludesOpcode() throws IOException {
        byte[] payload = new byte[10];
        Packet packet = new Packet(Packet.PROTOCOL_ED2K, (byte) 0x42, payload);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        packet.write(out, false);

        byte[] result = out.toByteArray();
        ByteBuffer buf = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        buf.get(); // skip protocol
        int length = buf.getInt();

        assertEquals(payload.length + 1, length, "Length field should be payload size + 1 (for opcode)");
    }

    @Test
    void testCompression() throws IOException {
        byte protocol = Packet.PROTOCOL_ED2K;
        byte opcode = (byte) 0x42;
        // More compressible data
        byte[] payload = new byte[1000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 10);

        Packet packet = new Packet(protocol, opcode, payload);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        packet.write(out, true); // Force compression

        byte[] compressedData = out.toByteArray();
        assertTrue(compressedData.length < payload.length + Packet.HEADER_SIZE, "Compressed data should be smaller");
        assertEquals(Packet.PROTOCOL_ZLIB, compressedData[0], "Protocol should be ZLIB (0xD4)");

        ByteArrayInputStream in = new ByteArrayInputStream(compressedData);
        Packet readPacket = Packet.read(in, 1024);

        assertEquals(opcode, readPacket.opcode());
        assertArrayEquals(payload, readPacket.data());
        // Note: Packet.read sets the protocol to the one read from stream, which is PROTOCOL_ZLIB here
        assertEquals(Packet.PROTOCOL_ZLIB, readPacket.protocol());
    }

    @Test
    void testKadCompression() throws IOException {
        byte protocol = Packet.PROTOCOL_KAD;
        byte opcode = (byte) 0x21; // KADEMLIA2_REQ
        byte[] payload = new byte[1000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 10);

        Packet packet = new Packet(protocol, opcode, payload);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        packet.write(out, true); // Force compression

        byte[] compressedData = out.toByteArray();
        assertTrue(compressedData.length < payload.length + Packet.HEADER_SIZE, "Compressed data should be smaller");
        assertEquals(Packet.PROTOCOL_KAD_ZLIB, compressedData[0], "Protocol should be KAD_ZLIB (0xE5)");

        ByteArrayInputStream in = new ByteArrayInputStream(compressedData);
        Packet readPacket = Packet.read(in, 1024);

        assertEquals(opcode, readPacket.opcode());
        assertArrayEquals(payload, readPacket.data());
        assertEquals(Packet.PROTOCOL_KAD_ZLIB, readPacket.protocol());
    }

    @Test
    void testEmuleProtocolPacket() throws IOException {
        byte protocol = Packet.PROTOCOL_ED2K;
        byte opcode = (byte) 0x23; // OP_GETSOURCES_OBFU is an ed2k (0xE3) opcode
        byte[] payload = "FileHash12345678".getBytes();
        Packet packet = new Packet(protocol, opcode, payload);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        packet.write(out, false);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        Packet readPacket = Packet.read(in, 1024);

        assertEquals(protocol, readPacket.protocol());
        assertEquals(opcode, readPacket.opcode());
        assertArrayEquals(payload, readPacket.data());

        OpCode op = OpCode.fromByte(readPacket.protocol(), readPacket.opcode());
        assertEquals(OpCode.GET_SOURCES_OBFU, op);
    }

    @Test
    void testTags() {
        byte[] hash = new byte[16];
        for (int i = 0; i < 16; i++) hash[i] = (byte) i;

        List<Tag> tags = List.of(
                new Tag(Tag.TYPE_STRING, "Name", "JEmule"),
                new Tag(Tag.TYPE_INTEGER, "Version", 60),
                new Tag(Tag.TYPE_FLOAT, "Float", 1.23f),
                new Tag(Tag.TYPE_BOOL, "BoolTrue", true),
                new Tag(Tag.TYPE_BOOL, "BoolFalse", false),
                new Tag(Tag.TYPE_BLOB, "Blob", new byte[]{0x01, 0x02, 0x03}),
                new Tag(Tag.TYPE_STRING, "\u0001", "ShortName"),
                new Tag(Tag.TYPE_HASH, "Hash", hash),
                new Tag(Tag.TYPE_INT16, "Int16", (short) 1234),
                new Tag(Tag.TYPE_INT8, "Int8", (byte) 123)
        );

        ByteBuffer buf = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        Tag.writeList(buf, tags);
        buf.flip();

        List<Tag> readTags = Tag.readList(buf);
        assertEquals(tags.size(), readTags.size());

        for (int i = 0; i < tags.size(); i++) {
            Tag expected = tags.get(i);
            Tag actual = readTags.get(i);
            assertEquals(expected.type(), actual.type(), "Type mismatch for tag " + expected.name());
            assertEquals(expected.name(), actual.name());
            if (expected.type() == Tag.TYPE_BLOB || expected.type() == Tag.TYPE_HASH) {
                assertArrayEquals((byte[]) expected.value(), (byte[]) actual.value());
            } else {
                assertEquals(expected.value(), actual.value());
            }
        }
    }

    @Test
    void testTagShortNameEncoding() {
        Tag t = new Tag(Tag.TYPE_STRING, "\u0011", "Version");
        ByteBuffer buf = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        t.write(buf);
        buf.flip();

        byte typeByte = buf.get();
        assertEquals((byte) (Tag.TYPE_STRING | 0x80), typeByte, "Type should have MSB set for 1-byte names");
        byte nameByte = buf.get();
        assertEquals((byte) '\u0011', nameByte, "Name should be written as 1 byte");
    }

    @Test
    void testNewOpCodes() {
        assertEquals(OpCode.SOURCES_RESULT_OBFU, OpCode.fromByte(Packet.PROTOCOL_ED2K, (byte) 0x44));
        assertEquals(OpCode.GET_SOURCES_OBFU, OpCode.fromByte(Packet.PROTOCOL_ED2K, (byte) 0x23));
        assertEquals(OpCode.GET_SOURCES_OBFU, OpCode.fromByte(Packet.PROTOCOL_ZLIB, (byte) 0x23));
        assertEquals(OpCode.OFFER_FILES, OpCode.fromByte(Packet.PROTOCOL_ZLIB, (byte) 0x15));
        assertNull(OpCode.fromByte(Packet.PROTOCOL_EMULE, (byte) 0x23));
        assertEquals(OpCode.COMPRESSED_PART, OpCode.fromByte(Packet.PROTOCOL_EMULE, (byte) 0x40));
        
        // Conformity tests
        assertEquals((byte) 0x33, OpCode.SEARCH_RESULT.value);
        assertEquals((byte) 0x19, OpCode.GET_SOURCES.value);
        assertEquals((byte) 0x42, OpCode.FOUND_SOURCES.value);
        assertEquals((byte) 0x32, OpCode.SERVER_LIST.value);
        assertEquals((byte) 0x15, OpCode.OFFER_FILES.value);
        assertEquals((byte) 0x14, OpCode.GET_SERVER_LIST.value);
    }

    @Test
    void testTagNameConstantsMatchEmuleSpec() {
        // Verify that all server tag name constants match eMule specification IDs

        // Core eMule server tags (from server.h / server.cpp)
        assertEquals("\u0001", Tag.NAME_NAME, "ST_SERVERNAME = 0x01");
        assertEquals("\u0001", Tag.NAME_NICK, "ST_CLIENTNAME = 0x01");
        assertEquals("\u000B", Tag.NAME_DESCRIPTION, "ST_DESCRIPTION = 0x0B");
        assertEquals("\u000E", Tag.NAME_PREFERENCE, "ST_PREFERENCE = 0x0E");
        assertEquals("\u000F", Tag.NAME_PORT, "ST_PORT = 0x0F");
        assertEquals("\u0011", Tag.NAME_CLIENT_VERSION, "CT_VERSION = 0x11");

        // Extended server tags (0x80-0x99 range)
        assertEquals("\u0087", Tag.NAME_MAXUSERS, "ST_MAXUSERS = 0x87");
        assertEquals("\u0088", Tag.NAME_SOFT_FILES, "ST_SOFTFILES = 0x88");
        assertEquals("\u0089", Tag.NAME_HARD_FILES, "ST_HARDFILES = 0x89");
        assertEquals("\u0090", Tag.NAME_LASTPING, "ST_LASTPING = 0x90");
        assertEquals("\u0091", Tag.NAME_SERVER_VERSION, "ST_VERSION = 0x91");
        assertEquals("\u0087", Tag.NAME_MAX_USERS, "ST_MAXUSERS = 0x87");
        assertEquals("\u0089", Tag.NAME_MAX_FILES, "ST_MAXFILES = 0x89");
        assertEquals("\u0094", Tag.NAME_LOWIDUSERS, "ST_LOWIDUSERS = 0x94");
        assertEquals("\u0092", Tag.NAME_UDPFLAGS, "ST_UDPFLAGS = 0x92");
        assertEquals("\u0095", Tag.NAME_UDP_KEY, "ST_UDPKEY = 0x95");
        assertEquals("\u0096", Tag.NAME_UDP_KEY_IP, "ST_UDPKEYIP = 0x96");
        assertEquals("\u0097", Tag.NAME_TCP_OBFUSCATION_PORT, "ST_TCPPORTOBFUSCATION = 0x97");
        assertEquals("\u0098", Tag.NAME_UDP_OBFUSCATION_PORT, "ST_UDPPORTOBFUSCATION = 0x98");

        // Lugdunum extension
        assertEquals("\u00FB", Tag.NAME_EMULE_VERSION, "ST_EMULE_VERSION = 0xFB");
    }

    @Test
    void testTagTypeConstants() {
        assertEquals((byte) 0x01, Tag.TYPE_HASH, "TYPE_HASH = 0x01");
        assertEquals((byte) 0x02, Tag.TYPE_STRING, "TYPE_STRING = 0x02");
        assertEquals((byte) 0x03, Tag.TYPE_INTEGER, "TYPE_INTEGER = 0x03");
        assertEquals((byte) 0x04, Tag.TYPE_FLOAT, "TYPE_FLOAT = 0x04");
        assertEquals((byte) 0x05, Tag.TYPE_BOOL, "TYPE_BOOL = 0x05");
        assertEquals((byte) 0x06, Tag.TYPE_BOOLARRAY, "TAGTYPE_BOOLARRAY = 0x06");
        assertEquals((byte) 0x0A, Tag.TYPE_BSOB, "TAGTYPE_BSOB = 0x0A");
        assertEquals((byte) 0x0B, Tag.TYPE_UINT64, "TAGTYPE_UINT64 = 0x0B");
        assertEquals((byte) 0x11, Tag.TYPE_STR1, "TAGTYPE_STR1 = 0x11");
        assertEquals((byte) 0x20, Tag.TYPE_STR16, "TAGTYPE_STR16 = 0x20");
        assertEquals((byte) 0x07, Tag.TYPE_BLOB, "TYPE_BLOB = 0x07");
        assertEquals((byte) 0x08, Tag.TYPE_INT16, "TYPE_INT16 = 0x08");
        assertEquals((byte) 0x09, Tag.TYPE_INT8, "TYPE_INT8 = 0x09");
    }

    @Test
    void testTagUint64() {
        ByteBuffer buf = ByteBuffer.allocate(100).order(ByteOrder.LITTLE_ENDIAN);
        long largeValue = 0x1234567890ABCDEFL;
        Tag tag = new Tag(Tag.TYPE_UINT64, "large", largeValue);
        tag.write(buf);
        buf.flip();
        Tag read = Tag.read(buf);
        assertEquals(Tag.TYPE_UINT64, read.type());
        assertEquals("large", read.name());
        assertEquals(largeValue, read.value());
    }

    @Test
    void testTagIdUint64() {
        ByteBuffer buf = ByteBuffer.allocate(100).order(ByteOrder.LITTLE_ENDIAN);
        long largeValue = 0xDEADC0DEBAADF00DL;
        Tag tag = new Tag(Tag.TYPE_UINT64, "\u0088", largeValue); // ST_SOFTFILES as ID
        tag.write(buf);
        buf.flip();
        Tag read = Tag.read(buf);
        assertEquals(Tag.TYPE_UINT64, read.type());
        assertEquals("\u0088", read.name());
        assertEquals(largeValue, read.value());
    }

    private static byte[] zlib(byte[] data) {
        java.util.zip.Deflater d = new java.util.zip.Deflater();
        d.setInput(data);
        d.finish();
        byte[] out = new byte[data.length + 64];
        int len = d.deflate(out);
        d.end();
        return java.util.Arrays.copyOf(out, len);
    }

    private static byte[] frame(byte protocol, byte opcode, byte[] payload) {
        ByteBuffer b = ByteBuffer.allocate(6 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put(protocol).putInt(payload.length + 1).put(opcode).put(payload);
        return b.array();
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    void truncatedZlibPacketIsRejected() {
        byte[] compressed = zlib(new byte[10_000]);
        byte[] truncated = java.util.Arrays.copyOf(compressed, compressed.length / 2);
        assertThrows(IOException.class, () -> Packet.read(new ByteArrayInputStream(frame(Packet.PROTOCOL_ZLIB, (byte) 0x15, truncated)), 1 << 20));
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    void zlibBombIsRejected() {
        byte[] compressed = zlib(new byte[5_000_000]); // a few KB inflating to 5 MB
        assertThrows(IOException.class, () -> Packet.read(new ByteArrayInputStream(frame(Packet.PROTOCOL_ZLIB, (byte) 0x15, compressed)), 1 << 20));
    }
}
