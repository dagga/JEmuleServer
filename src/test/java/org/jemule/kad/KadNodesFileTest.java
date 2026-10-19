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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KadNodesFileTest {

    private static final byte[] ID = {
            0x12, 0x25, 0x74, 0x25, (byte) 0xDB, (byte) 0xA4, (byte) 0xED, (byte) 0xDB,
            (byte) 0xD0, (byte) 0x97, 0x15, 0x07, 0x57, 0x40, 0x44, (byte) 0x86};

    @Test
    void parsesVersion2() {
        List<KadContact> contacts = KadNodesFile.parse(v2(contact((byte) 8, (byte) 0xE5, (byte) 0x5E, 0x04, (byte) 0xDE, 4672)));

        assertEquals(1, contacts.size());
        KadContact contact = contacts.get(0);
        assertEquals("222.4.94.229", contact.address().getHostAddress());
        assertEquals(4672, contact.udpPort());
        assertEquals(4662, contact.tcpPort());
        assertEquals(8, contact.kadVersion());
    }

    @Test
    void parsesVersion1() {
        List<KadContact> contacts = KadNodesFile.parse(v1(contact((byte) 8, (byte) 0xE5, (byte) 0x5E, 0x04, (byte) 0xDE, 4672)));

        assertEquals(1, contacts.size());
        assertEquals("222.4.94.229", contacts.get(0).address().getHostAddress());
        assertEquals(4672, contacts.get(0).udpPort());
    }

    @Test
    void parsesVersion3BootstrapEdition() {
        ByteBuffer buf = ByteBuffer.allocate(16 + 34).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0).putInt(3).putInt(1).putInt(1);
        buf.put(contact((byte) 8, (byte) 0xE5, (byte) 0x5E, 0x04, (byte) 0xDE, 4672));
        List<KadContact> contacts = KadNodesFile.parse(buf.array());

        assertEquals(1, contacts.size());
        assertEquals("222.4.94.229", contacts.get(0).address().getHostAddress());
    }

    @Test
    void version0IsRejected() {
        ByteBuffer buf = ByteBuffer.allocate(4 + 34).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(1).put(contact((byte) 2, 1, 2, 3, 4, 4672));
        assertThrows(IllegalArgumentException.class, () -> KadNodesFile.parse(buf.array()));
    }

    @Test
    void unknownAndTruncatedFilesAreRejected() {
        ByteBuffer unknown = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        unknown.putInt(0).putInt(9).putInt(0);
        assertThrows(IllegalArgumentException.class, () -> KadNodesFile.parse(unknown.array()));
        assertThrows(IllegalArgumentException.class, () -> KadNodesFile.parse(new byte[3]));
        assertThrows(IllegalArgumentException.class, () -> KadNodesFile.parse(new byte[]{0, 0, 0, 0}));
    }

    @Test
    void ignoresKad1ContactsAndNonPublicAddresses() {
        ByteBuffer buf = ByteBuffer.allocate(12 + 34 * 3).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0).putInt(2).putInt(3);
        buf.put(contact((byte) 1, 1, 2, 3, 4, 4672));
        buf.put(contact((byte) 8, 1, 1, (byte) 168, (byte) 192, 4672));
        buf.put(contact((byte) 8, (byte) 0xE5, (byte) 0x5E, 0x04, (byte) 0xDE, 4672));

        List<KadContact> contacts = KadNodesFile.parse(buf.array());

        assertEquals(1, contacts.size());
        assertEquals("222.4.94.229", contacts.get(0).address().getHostAddress());
    }

    @Test
    void ignoresExcessCountAndPortZero() {
        ByteBuffer buf = ByteBuffer.allocate(12 + 68).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0).putInt(2).putInt(10);
        buf.put(contact((byte) 8, (byte) 0xE5, (byte) 0x5E, 0x04, (byte) 0xDE, 4672));
        buf.put(contact((byte) 8, 1, 2, 3, 4, 0));

        assertEquals(1, KadNodesFile.parse(buf.array()).size(), "only the records present are read");
    }

    @Test
    void readsFromDisk(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("nodes.dat");
        Files.write(file, v2(contact((byte) 8, (byte) 0xE5, (byte) 0x5E, 0x04, (byte) 0xDE, 4672)));

        assertEquals(1, KadNodesFile.read(file).size());
    }

    private static byte[] v1(byte[] record) {
        ByteBuffer buf = ByteBuffer.allocate(12 + 25).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0).putInt(1).putInt(1).put(record, 0, 25);
        return buf.array();
    }

    private static byte[] v2(byte[] record) {
        ByteBuffer buf = ByteBuffer.allocate(12 + record.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0).putInt(2).putInt(1).put(record);
        return buf.array();
    }

    private static byte[] contact(int version, int ip0, int ip1, int ip2, int ip3, int udpPort) {
        ByteBuffer buf = ByteBuffer.allocate(34).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(ID);
        buf.put(new byte[]{(byte) ip0, (byte) ip1, (byte) ip2, (byte) ip3});
        buf.putShort((short) udpPort);
        buf.putShort((short) 4662);
        buf.put((byte) version);
        buf.putInt(0xDDCCBBAA);
        buf.put(new byte[]{4, 3, 2, 1});
        buf.put((byte) 1);
        return buf.array();
    }
}
