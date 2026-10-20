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
import org.jemule.network.Packet;
import org.jemule.network.handler.HandlerUtils;
import org.jemule.protocol.FileTypes;
import org.jemule.protocol.Tag;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a UDP packet in the Kademlia protocol. The embedded node speaks the eMule/mule Kad2 framing:
 * {@code OP_KADEMLIAHEADER} (0xE4) followed by the opcode and the little-endian payload.
 */
public final class KadPacket {
    public static final byte PROTOCOL_KAD1 = (byte) 0x39;
    public static final byte PROTOCOL_KAD2 = KadOpCode.KAD2_HEADER;
    /** {@code OP_KADEMLIAPACKEDPROT}: Kad2 packet whose payload is zlib-compressed. */
    public static final byte PROTOCOL_KAD2_PACKED = (byte) 0xE5;

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
     * Deserializes a UDP packet from raw bytes. A packed packet ({@code OP_KADEMLIAPACKEDPROT}, 0xE5: eMule packs
     * every Kad packet larger than 200 bytes) is inflated into a plain Kad2 packet.
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
        if (proto == PROTOCOL_KAD2_PACKED) {
            try {
                // Same bound as eMule's CClientUDPSocket::OnReceive
                payload = Packet.decompress(payload, dataLen * 10 + 300);
            } catch (IOException e) {
                throw new IllegalArgumentException("Invalid packed Kad packet: " + e.getMessage(), e);
            }
            proto = PROTOCOL_KAD2;
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
     * Creates a KADEMLIA2_BOOTSTRAP_RES packet: the responder identity (ID, TCP port, Kad version) followed by
     * the list of contacts.
     */
    public static KadPacket createBootstrapRes(KadId localId, int localTcpPort, byte kadVersion,
                                               List<KadContact> contacts) {
        Objects.requireNonNull(localId, "localId cannot be null");
        Objects.requireNonNull(contacts, "contacts cannot be null");
        ByteBuffer buf = ByteBuffer.allocate(16 + 2 + 1 + 2 + contacts.size() * 25).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(localId.toWire());
        buf.putShort((short) localTcpPort);
        buf.put(kadVersion);
        buf.putShort((short) contacts.size());
        for (KadContact c : contacts) {
            writeContact(buf, c);
        }
        byte[] data = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, data, 0, data.length);
        return new KadPacket(KadOpCode.BOOTSTRAP_RES, data);
    }

    /** Identity announced by a KADEMLIA2_BOOTSTRAP_RES, with the contacts it carries. */
    public record BootstrapRes(KadId responderId, int responderTcpPort, byte responderVersion,
                               List<KadContact> contacts) {}

    /**
     * Parses a KADEMLIA2_BOOTSTRAP_RES packet, or returns null when it is malformed.
     */
    public static BootstrapRes parseBootstrapRes(byte[] data) {
        if (data == null || data.length < 21) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        byte[] idBytes = new byte[16];
        buf.get(idBytes);
        int tcpPort = Short.toUnsignedInt(buf.getShort());
        byte kadVersion = buf.get();
        int count = Short.toUnsignedInt(buf.getShort());
        List<KadContact> result = new ArrayList<>(Math.min(count, 100));
        for (int i = 0; i < count && buf.remaining() >= 25; i++) {
            KadContact contact = readContact(buf);
            if (contact != null) {
                result.add(contact);
            }
        }
        return new BootstrapRes(KadId.fromWire(idBytes), tcpPort, kadVersion, result);
    }

    /**
     * Creates a KADEMLIA2_HELLO_REQ packet.
     */
    public static KadPacket createHelloReq(KadId senderId, int senderTcpPort, byte kadVersion) {
        ByteBuffer buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(senderId.toWire());
        buf.putShort((short) senderTcpPort);
        buf.put(kadVersion);
        buf.put((byte) 0); // 0 tags
        byte[] data = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, data, 0, data.length);
        return new KadPacket(KadOpCode.HELLO_REQ, data);
    }

    /**
     * Creates a KADEMLIA2_HELLO_RES packet.
     */
    public static KadPacket createHelloRes(KadId senderId, int senderTcpPort, byte kadVersion) {
        ByteBuffer buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(senderId.toWire());
        buf.putShort((short) senderTcpPort);
        buf.put(kadVersion);
        buf.put((byte) 0); // 0 tags
        byte[] data = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, data, 0, data.length);
        return new KadPacket(KadOpCode.HELLO_RES, data);
    }

    public record HelloPayload(KadId senderId, int tcpPort, byte kadVersion) {}

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
        int tcpPort = Short.toUnsignedInt(buf.getShort());
        byte kadVersion = buf.get();
        return new HelloPayload(KadId.fromWire(idBytes), tcpPort, kadVersion);
    }

    /**
     * Creates a KADEMLIA2_REQ (Find Node) packet.
     *
     * @param checkId the ID of the contacted node, used by the receiver to discard reflected/forged requests
     * @param targetType the lookup type ({@code 0x02} find value, {@code 0x0B} find node)
     * @param targetId the key whose closest contacts are requested
     */
    public static KadPacket createFindNodeReq(KadId checkId, byte targetType, KadId targetId) {
        Objects.requireNonNull(checkId, "checkId cannot be null");
        Objects.requireNonNull(targetId, "targetId cannot be null");
        ByteBuffer buf = ByteBuffer.allocate(33).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(targetType);
        buf.put(targetId.toWire());
        buf.put(checkId.toWire()); // must equal the receiver's Kad ID
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
        buf.put(targetId.toWire());
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
        return new FindNodeResult(KadId.fromWire(targetBytes), contacts);
    }

    /**
     * A KADEMLIA2_SEARCH_KEY_REQ. {@code searchTerms} is the search expression of a restrictive search, or null.
     */
    public record SearchKeyReq(KadId keywordHash, int startPosition, byte[] searchTerms) {}

    /** Highest start position: the top bit of the field flags a restrictive search (search terms follow). */
    public static final int MAX_START_POSITION = 0x7FFF;
    private static final int SEARCH_TERMS_FLAG = 0x8000;

    /**
     * Creates a KADEMLIA2_SEARCH_KEY_REQ packet for a keyword search.
     */
    public static KadPacket createSearchKeyReq(KadId keywordHash, int startPosition) {
        return createSearchKeyReq(keywordHash, startPosition, null);
    }

    /**
     * Creates a KADEMLIA2_SEARCH_KEY_REQ <code>&lt;target 16&gt;&lt;start position 2&gt;[&lt;search terms&gt;]</code>
     * (CKademliaUDPListener::Process_KADEMLIA2_SEARCH_KEY_REQ). The node skips the first {@code startPosition}
     * matching entries and returns at most 300; with search terms, only the entries matching them are counted.
     *
     * @param searchTerms search expression in the ed2k search tree format, or null for all the keyword's entries
     */
    public static KadPacket createSearchKeyReq(KadId keywordHash, int startPosition, byte[] searchTerms) {
        Objects.requireNonNull(keywordHash, "keywordHash cannot be null");
        if (startPosition < 0 || startPosition > MAX_START_POSITION) {
            throw new IllegalArgumentException("Start position out of range: " + startPosition);
        }
        boolean restrictive = searchTerms != null && searchTerms.length > 0;
        ByteBuffer buf = ByteBuffer.allocate(18 + (restrictive ? searchTerms.length : 0)).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(keywordHash.toWire());
        buf.putShort((short) (restrictive ? startPosition | SEARCH_TERMS_FLAG : startPosition));
        if (restrictive) buf.put(searchTerms);
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
        byte[] terms = null;
        if ((startPos & SEARCH_TERMS_FLAG) != 0) {
            terms = new byte[buf.remaining()];
            buf.get(terms);
        }
        return new SearchKeyReq(KadId.fromWire(hash), startPos & MAX_START_POSITION, terms);
    }

    public record SearchRes(KadId targetId, List<FileMetadata> files) {}

    /**
     * Creates a KADEMLIA2_SEARCH_RES packet containing discovered files for the given target.
     */
    public static KadPacket createSearchRes(KadId targetId, List<FileMetadata> files) {
        return createSearchRes(KadId.zero(), targetId, files);
    }

    /**
     * Creates a KADEMLIA2_SEARCH_RES packet. The Kad2 payload starts with the sender ID, then the search target,
     * then the number of results.
     */
    public static KadPacket createSearchRes(KadId sourceId, KadId targetId, List<FileMetadata> files) {
        Objects.requireNonNull(sourceId, "sourceId cannot be null");
        Objects.requireNonNull(targetId, "targetId cannot be null");
        Objects.requireNonNull(files, "files cannot be null");
        ByteBuffer buf = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(sourceId.toWire());
        buf.put(targetId.toWire());
        buf.putShort((short) files.size());

        for (FileMetadata f : files) {
            buf.put(new KadId(HandlerUtils.hashToBytes(f.hash())).toWire()); // file hash sent as a CUInt128
            List<Tag> tags = new ArrayList<>();
            tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_FILENAME, f.name()));
            tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_FILESIZE, (int) (f.size() & 0xFFFFFFFFL)));
            if (f.size() > 0xFFFFFFFFL) {
                tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_FILESIZE_HI, (int) (f.size() >>> 32)));
            }
            if (f.type() != null && !f.type().isEmpty()) {
                tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_FILETYPE, f.type()));
            }
            tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, f.sourceCount()));
            writeKadTagList(buf, tags);
        }

        byte[] data = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, data, 0, data.length);
        return new KadPacket(KadOpCode.SEARCH_RES, data);
    }

    /**
     * Parses a KADEMLIA2_SEARCH_RES payload into FileMetadata objects.
     */
    public static SearchRes parseSearchRes(byte[] data) {
        if (data == null || data.length < 34) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(16); // Kad2 search responses carry the responder ID first
        byte[] targetBytes = new byte[16];
        buf.get(targetBytes);
        int count = Short.toUnsignedInt(buf.getShort());

        List<FileMetadata> files = new ArrayList<>(Math.min(count, 100));
        for (int i = 0; i < count && buf.remaining() >= 16; i++) {
            byte[] fileHash = new byte[16];
            buf.get(fileHash);
            String hashHex = HandlerUtils.bytesToHashString(KadId.fromWire(fileHash).getBytes());

            List<Tag> tags;
            try {
                tags = readKadTagList(buf);
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
            FileMetadata meta = FileMetadata.withoutSources(hashHex, name, size, type, tags);
            files.add(meta);
        }

        return new SearchRes(KadId.fromWire(targetBytes), files);
    }

    /** Writes a Kad tag list: the count is a single byte, then the tags (unlike the 4-byte eD2K count). */
    private static void writeKadTagList(ByteBuffer buf, List<Tag> tags) {
        buf.put((byte) tags.size());
        for (Tag tag : tags) {
            tag.writeKad(buf);
        }
    }

    /** Reads a Kad tag list whose count is a single byte. */
    private static List<Tag> readKadTagList(ByteBuffer buf) {
        if (buf.remaining() < 1) {
            return List.of();
        }
        int count = Byte.toUnsignedInt(buf.get());
        List<Tag> tags = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            tags.add(Tag.read(buf));
        }
        return tags;
    }

    // =========================================================================
    // Contact serialization helpers
    // =========================================================================

    private static void writeContact(ByteBuffer buf, KadContact c) {
        buf.put(c.id().toWire());
        byte[] addrBytes = c.address().getAddress();
        if (addrBytes.length == 4) {
            // The IPv4 address is serialized least-significant byte first, like in nodes.dat.
            buf.put(new byte[]{addrBytes[3], addrBytes[2], addrBytes[1], addrBytes[0]});
        } else {
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
            byte[] address = {ipBytes[3], ipBytes[2], ipBytes[1], ipBytes[0]};
            InetAddress addr = InetAddress.getByAddress(address);
            return new KadContact(KadId.fromWire(idBytes), addr, udpPort, tcpPort, version);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
