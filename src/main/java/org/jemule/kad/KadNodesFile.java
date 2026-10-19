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

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for the {@code nodes.dat} contact lists used by eMule and aMule.
 * <p>
 * The file version determines the record layout: version 1 and the version 3 bootstrap edition use 25-byte
 * records, version 2 adds the 8-byte KadUDPKey and the verification flag (34 bytes). The obsolete version 0
 * cannot tell the Kad protocol version of its contacts and is rejected. All integers are little-endian and
 * the IPv4 address bytes are stored in reverse order. Contacts older than Kad2 (per-contact version {@code <= 1})
 * are ignored.
 *
 * @see <a href="https://amule-org.github.io/docs/developer/file-formats/nodes-dat">aMule nodes.dat format</a>
 */
public final class KadNodesFile {
    /** Safety limit applied by eMule and aMule to the downloaded contact lists. */
    public static final int MAX_CONTACTS = 5000;

    private static final int CONTACT_V1_BYTES = 25;
    private static final int CONTACT_V2_BYTES = 34;
    private static final int BOOTSTRAP_EDITION = 1;

    private KadNodesFile() {
    }

    /** Reads and parses a nodes.dat file. */
    public static List<KadContact> read(Path file) throws IOException {
        return parse(Files.readAllBytes(file));
    }

    /**
     * Parses a nodes.dat payload.
     *
     * @throws IllegalArgumentException when the header, the version or the record count is invalid
     */
    public static List<KadContact> parse(byte[] data) {
        if (data == null || data.length < 4) {
            throw new IllegalArgumentException("nodes.dat is too short");
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int first = buf.getInt();
        int version;
        int count;
        int offset;
        int recordSize;
        if (first == 0) {
            if (data.length < 8) throw new IllegalArgumentException("nodes.dat header is truncated");
            version = buf.getInt();
            if (version == 3) {
                if (data.length < 16) throw new IllegalArgumentException("nodes.dat bootstrap header is truncated");
                int edition = buf.getInt();
                count = buf.getInt();
                offset = 16;
                recordSize = CONTACT_V1_BYTES;
                if (edition != BOOTSTRAP_EDITION) {
                    throw new IllegalArgumentException("Unsupported nodes.dat bootstrap edition " + edition);
                }
            } else {
                count = buf.getInt();
                offset = 12;
                recordSize = version >= 2 ? CONTACT_V2_BYTES : CONTACT_V1_BYTES;
            }
        } else {
            throw new IllegalArgumentException("Unsupported nodes.dat version 0 (too old)");
        }

        if (version != 1 && version != 2 && version != 3) {
            throw new IllegalArgumentException("Unsupported nodes.dat version " + version);
        }
        if (count < 0) {
            throw new IllegalArgumentException("Invalid nodes.dat contact count " + count);
        }

        int available = Math.max(0, (data.length - offset) / recordSize);
        int contacts = Math.min(Math.min(count, available), MAX_CONTACTS);
        List<KadContact> result = new ArrayList<>(contacts);
        buf.position(offset);
        for (int i = 0; i < contacts; i++) {
            KadContact contact = readContact(buf, version >= 2);
            if (contact != null) result.add(contact);
        }
        return List.copyOf(result);
    }

    private static KadContact readContact(ByteBuffer buf, boolean wideRecord) {
        byte[] idBytes = new byte[16];
        buf.get(idBytes);
        byte[] ipBytes = new byte[4];
        buf.get(ipBytes);
        int udpPort = Short.toUnsignedInt(buf.getShort());
        int tcpPort = Short.toUnsignedInt(buf.getShort());
        int version = Byte.toUnsignedInt(buf.get());
        if (wideRecord) {
            buf.position(buf.position() + 8); // KadUDPKey
            buf.get();                        // Verified
        }

        // The IPv4 address is stored least-significant byte first.
        byte[] address = {ipBytes[3], ipBytes[2], ipBytes[1], ipBytes[0]};
        if (version <= 1 || !isPublic(address) || udpPort == 0) return null;
        try {
            InetAddress addr = InetAddress.getByAddress(address);
            return new KadContact(new KadId(idBytes), addr, udpPort, tcpPort, (byte) version);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** Rejects addresses that can never be reached on the public Kad network. */
    private static boolean isPublic(byte[] ip) {
        int a = ip[0] & 0xFF;
        int b = ip[1] & 0xFF;
        if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
        if (a == 169 && b == 254) return false;
        if (a == 172 && b >= 16 && b <= 31) return false;
        if (a == 192 && b == 168) return false;
        if (a == 100 && b >= 64 && b <= 127) return false;
        return true;
    }
}
