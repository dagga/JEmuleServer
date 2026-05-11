/*
 * JEmuleServer - An experimental eMule server.
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
    void testEmuleProtocolPacket() throws IOException {
        byte protocol = Packet.PROTOCOL_EMULE;
        byte opcode = (byte) 0x23; // GET_SOURCES_OBFU
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
    void testNewOpCodes() {
        assertEquals(OpCode.SOURCES_RESULT_OBFU, OpCode.fromByte(Packet.PROTOCOL_EMULE, (byte) 0x24));
        assertEquals(OpCode.COMPRESSED_PART, OpCode.fromByte(Packet.PROTOCOL_EMULE, (byte) 0x28));
    }
}
