/*
 * JEmuleServer - An experimental eMule server.
 * Copyright (C) 2026 Nicolas Hernandez (herniatgmail.com)
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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class PacketTest {

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
}
