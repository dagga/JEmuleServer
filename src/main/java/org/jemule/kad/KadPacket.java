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
import org.jemule.network.handler.HandlerUtils;
import org.jemule.protocol.FileTypes;
import org.jemule.protocol.Tag;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a UDP packet in the Kademlia protocol (Kad1: 0x39, Kad2: 0x3A).
 * Handles encoding, decoding, and building standard Kad2 messages.
 */
public final class KadPacket {
    public static final byte PROTOCOL_KAD1 = (byte) 0x39;
    public static final byte PROTOCOL_KAD2 = (byte) 0x3A;

    private final byte protocol;
    private final byte opCode;
    private final byte[] data;

    public KadPacket(byte protocol, byte opCode, byte[] data) {
        this.protocol = protocol;
        this.opCode = opCode;
        this.data = (data != null) ? data.clone() : new byte[0];
    }

    public KadPacket(KadOpCode op, byte[] data) {
        this(op.protocol, op.value, data);
    }

    public byte protocol() {
        return protocol;
    }

    public byte opCode() {
        return opCode;
    }

    public byte[] data() {
        return data.clone();
    }

    public KadOpCode getOpCode() {
        return KadOpCode.fromByte(protocol, opCode);
    }

    /**
     * Serializes this packet to raw UDP bytes [protocol (1), opcode (1), data (N)].
     */
    public byte[] encode() {
        byte[] raw = new byte[2 + data.length];
        raw[0] = protocol;
        raw[1] = opCode;
        System.arraycopy(data, 0, raw, 2, data.length);
        return raw;
    }

    /**
     * Deserializes a UDP packet from raw bytes.
     */
    public static KadPacket decode(byte[] raw, int offset, int length) {
        if (raw == null || length < 2) {
            throw new IllegalArgumentException("Invalid Kad packet: length must be at least 2 bytes");
        }
        byte proto = raw[offset];
        byte op = raw[offset + 1];
        int dataLen = length - 2;
        byte[] payload = new byte[dataLen];
        if (dataLen > 0) {
            System.arraycopy(raw, offset + 2, payload, 0, dataLen);
        }
        return new KadPacket(proto, op, payload);
    }

    public static KadPacket decode(byte[] raw) {
        return decode(raw, 0, raw != null ? raw.length : 0);
    }

    // =========================================================================
    // Builders & Parsers for Standard Kad2 Messages
    // =========================================================================

    /**
     * Creates a KADEMLIA2_BOOTSTRAP_REQ packet.
     */
    public static KadPacket createBootstrapReq() {
        return new KadPacket(KadOpCode.BOOTSTRAP_REQ, new byte[0]);
    }

    /**
     * Creates a KADEMLIA2_BOOTSTRAP_RES packet containing a list of contacts.
     */
    public static KadPacket createBootstrapRes(List<KadContact> contacts) {
        Objects.requireNonNull(contacts, "contacts cannot be null");
        ByteBuffer buf = ByteBuffer.allocate(2 + contacts.size() * 25).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) contacts.size());
        for (KadContact c : contacts) {
            writeContact(buf, c);
        }
        byte[] data = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, data, 0, data.length);
        return new KadPacket(KadOpCode.BOOTSTRAP_RES, data);
    }

    /**
     * Parses a KADEMLIA2_BOOTSTRAP_RES packet into a list of contacts.
     */
    public static List<KadContact> parseBootstrapRes(byte[] data) {
        if (data == null || data.length < 2) {
            return List.of();
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int count = Short.toUnsignedInt(buf.getShort());
        List<KadContact> result = new ArrayList<>(Math.min(count, 100));
        for (int i = 0; i < count && buf.remaining() >= 25; i++) {
            KadContact contact = readContact(buf);
            if (contact != null) {
                result.add(contact);
            }
        }
        return result;
    }

    /**
     * Creates a KADEMLIA2_HELLO_REQ packet.
     */
    public static KadPacket createHelloReq(KadId senderId, int senderUdpPort, byte kadVersion) {
        ByteBuffer buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(senderId.getBytes());
        buf.putShort((short) senderUdpPort);
        buf.put(kadVersion);
        buf.put((byte) 0); // 0 tags
        byte[] data = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, data, 0, data.length);
        return new KadPacket(KadOpCode.HELLO_REQ, data);
    }

    /**
     * Creates a KADEMLIA2_HELLO_RES packet.
     */
    public static KadPacket createHelloRes(KadId senderId, int senderUdpPort, byte kadVersion) {
        ByteBuffer buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(senderId.getBytes());
        buf.putShort((short) senderUdpPort);
        buf.put(kadVersion);
        buf.put((byte) 0); // 0 tags
        byte[] data = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, data, 0, data.length);
        return new KadPacket(KadOpCode.HELLO_RES, data);
    }

    public record HelloPayload(KadId senderId, int udpPort, byte kadVersion) {}

    /**
     * Parses a KADEMLIA2_HELLO_REQ or KADEMLIA2_HELLO_RES payload.
     */
    public static HelloPayload parseHello(byte[] data) {
        if (data == null || data.length < 19) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        byte[] idBytes = new byte[16];
        buf.get(idBytes);
        int udpPort = Short.toUnsignedInt(buf.getShort());
        byte kadVersion = buf.get();
        return new HelloPayload(new KadId(idBytes), udpPort, kadVersion);
    }

    /**
     * Creates a KADEMLIA2_REQ (Find Node) packet.
     */
    public static KadPacket createFindNodeReq(byte targetType, KadId targetId) {
        ByteBuffer buf = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(targetType);
        buf.put(targetId.getBytes());
        return new KadPacket(KadOpCode.REQ, buf.array());
    }

    public record FindNodeResult(KadId targetId, List<KadContact> contacts) {}

    /**
     * Creates a KADEMLIA2_RES packet returning contacts close to the queried target.
     */
    public static KadPacket createFindNodeRes(KadId targetId, List<KadContact> contacts) {
        Objects.requireNonNull(targetId, "targetId cannot be null");
        Objects.requireNonNull(contacts, "contacts cannot be null");
        ByteBuffer buf = ByteBuffer.allocate(17 + contacts.size() * 25).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(targetId.getBytes());
        buf.put((byte) contacts.size());
        for (KadContact c : contacts) {
            writeContact(buf, c);
        }
        byte[] data = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, data, 0, data.length);
        return new KadPacket(KadOpCode.RES, data);
    }

    /**
     * Parses a KADEMLIA2_RES payload.
     */
    public static FindNodeResult parseFindNodeRes(byte[] data) {
        if (data == null || data.length < 17) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        byte[] targetBytes = new byte[16];
        buf.get(targetBytes);
        int count = Byte.toUnsignedInt(buf.get());
        List<KadContact> contacts = new ArrayList<>(Math.min(count, 50));
        for (int i = 0; i < count && buf.remaining() >= 25; i++) {
            KadContact contact = readContact(buf);
            if (contact != null) {
                contacts.add(contact);
            }
        }
        return new FindNodeResult(new KadId(targetBytes), contacts);
    }

    public record SearchKeyReq(KadId keywordHash, int startPosition) {}

    /**
     * Creates a KADEMLIA2_SEARCH_KEY_REQ packet for a keyword search.
     */
    public static KadPacket createSearchKeyReq(KadId keywordHash, int startPosition) {
        Objects.requireNonNull(keywordHash, "keywordHash cannot be null");
        ByteBuffer buf = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(keywordHash.getBytes());
        buf.putShort((short) startPosition);
        return new KadPacket(KadOpCode.SEARCH_KEY_REQ, buf.array());
    }

    /**
     * Parses a KADEMLIA2_SEARCH_KEY_REQ packet.
     */
    public static SearchKeyReq parseSearchKeyReq(byte[] data) {
        if (data == null || data.length < 16) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        byte[] hash = new byte[16];
        buf.get(hash);
        int startPos = buf.remaining() >= 2 ? Short.toUnsignedInt(buf.getShort()) : 0;
        return new SearchKeyReq(new KadId(hash), startPos);
    }

    public record SearchRes(KadId targetId, List<FileMetadata> files) {}

    /**
     * Creates a KADEMLIA2_SEARCH_RES packet containing discovered files for the given target.
     */
    public static KadPacket createSearchRes(KadId targetId, List<FileMetadata> files) {
        Objects.requireNonNull(targetId, "targetId cannot be null");
        Objects.requireNonNull(files, "files cannot be null");
        ByteBuffer buf = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(targetId.getBytes());
        buf.putShort((short) files.size());

        for (FileMetadata f : files) {
            buf.put(HandlerUtils.hashToBytes(f.hash()));
            List<Tag> tags = new ArrayList<>();
            tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_FILENAME, f.name()));
            tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_FILESIZE, (int) (f.size() & 0xFFFFFFFFL)));
            if (f.size() > 0xFFFFFFFFL) {
                tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_FILESIZE_HI, (int) (f.size() >>> 32)));
            }
            if (f.type() != null && !f.type().isEmpty()) {
                tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_FILETYPE, f.type()));
            }
            tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, Math.max(1, f.sources().size())));
            Tag.writeList(buf, tags);
        }

        byte[] data = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, data, 0, data.length);
        return new KadPacket(KadOpCode.SEARCH_RES, data);
    }

    /**
     * Parses a KADEMLIA2_SEARCH_RES payload into FileMetadata objects.
     */
    public static SearchRes parseSearchRes(byte[] data) {
        if (data == null || data.length < 18) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        byte[] targetBytes = new byte[16];
        buf.get(targetBytes);
        int count = Short.toUnsignedInt(buf.getShort());

        List<FileMetadata> files = new ArrayList<>(Math.min(count, 100));
        for (int i = 0; i < count && buf.remaining() >= 16; i++) {
            byte[] fileHash = new byte[16];
            buf.get(fileHash);
            String hashHex = HandlerUtils.bytesToHashString(fileHash);

            List<Tag> tags;
            try {
                tags = Tag.readList(buf);
            } catch (Exception e) {
                break;
            }

            String name = "";
            long sizeLo = 0;
            long sizeHi = 0;
            String type = "";
            int sources = 0;

            for (Tag t : tags) {
                if (Tag.NAME_FILENAME.equals(t.name())) {
                    name = String.valueOf(t.value());
                } else if (Tag.NAME_FILESIZE.equals(t.name()) && t.isInt()) {
                    sizeLo = t.unsignedValue();
                } else if (Tag.NAME_FILESIZE_HI.equals(t.name()) && t.isInt()) {
                    sizeHi = t.unsignedValue();
                } else if (Tag.NAME_FILETYPE.equals(t.name())) {
                    type = FileTypes.fromTag(t);
                } else if (Tag.NAME_SOURCES.equals(t.name()) && t.isInt()) {
                    sources = (int) t.unsignedValue();
                }
            }

            long size = sizeHi != 0 ? (sizeHi << 32) | (sizeLo & 0xFFFFFFFFL) : sizeLo;
            FileMetadata meta = new FileMetadata(hashHex, name, size, type, tags);
            files.add(meta);
        }

        return new SearchRes(new KadId(targetBytes), files);
    }

    // =========================================================================
    // Contact serialization helpers
    // =========================================================================

    private static void writeContact(ByteBuffer buf, KadContact c) {
        buf.put(c.id().getBytes());
        byte[] addrBytes = c.address().getAddress();
        if (addrBytes.length == 4) {
            buf.put(addrBytes);
        } else {
            // IPv4 fallback for non-IPv4
            buf.putInt(0);
        }
        buf.putShort((short) c.udpPort());
        buf.putShort((short) c.tcpPort());
        buf.put(c.kadVersion());
    }

    private static KadContact readContact(ByteBuffer buf) {
        byte[] idBytes = new byte[16];
        buf.get(idBytes);
        byte[] ipBytes = new byte[4];
        buf.get(ipBytes);
        int udpPort = Short.toUnsignedInt(buf.getShort());
        int tcpPort = Short.toUnsignedInt(buf.getShort());
        byte version = buf.get();

        try {
            InetAddress addr = InetAddress.getByAddress(ipBytes);
            return new KadContact(new KadId(idBytes), addr, udpPort, tcpPort, version);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
