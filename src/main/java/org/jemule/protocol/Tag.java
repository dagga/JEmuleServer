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

package org.jemule.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents an ed2k tag.
 */
/**
 * Represents an ed2k tag (TLV - Type Length Value).
 * Used for exchanging metadata in packets.
 *
 * @param type  The data type of the tag value.
 * @param name  The name of the tag (can be a special 1-byte code).
 * @param value The actual data of the tag.
 */
public record Tag(byte type, String name, Object value) {
    /** MD4 Hash type (16 bytes). */
    public static final byte TYPE_HASH = (byte) 0x01;
    /** UTF-8 String type. */
    public static final byte TYPE_STRING = (byte) 0x02;
    /** 32-bit Integer type (DWORD). */
    public static final byte TYPE_INTEGER = (byte) 0x03;
    /** 32-bit Float type. */
    public static final byte TYPE_FLOAT = (byte) 0x04;
    /** Boolean type (1 byte). */
    public static final byte TYPE_BOOL = (byte) 0x05;
    /** Alternative Boolean type (1 byte). */
    public static final byte TYPE_BOOL_ALT = (byte) 0x06;
    /** Arbitrary Blob type (4 bytes length prefix). */
    public static final byte TYPE_BLOB = (byte) 0x07;
    /** 16-bit Integer type (short). */
    public static final byte TYPE_INT16 = (byte) 0x08;
    /** 8-bit Integer type (byte). */
    public static final byte TYPE_INT8 = (byte) 0x09;

    /** Special tag name for server/client version. */
    public static final String NAME_VERSION = "\u0011";
    /** Special tag name for server/client port. */
    public static final String NAME_PORT = "\u000F";
    /** Special tag name for client nickname. */
    public static final String NAME_NICK = "\u0001";
    /** Special tag name for server name. */
    public static final String NAME_NAME = "\u0001";
    /** Special tag name for server description. */
    public static final String NAME_DESCRIPTION = "\u000B";
    /** Special tag name for TCP capabilities/flags. */
    public static final String NAME_TCP_FLAGS = "\u0090";
    /** Special tag name for auxiliary port. */
    public static final String NAME_AUX_PORT = "\u0091";
    /** Special tag name for maximum user limit. */
    public static final String NAME_MAX_USERS = "\u0092";
    /** Special tag name for maximum file limit. */
    public static final String NAME_MAX_FILES = "\u0093";
    /** Special tag name for eMule-specific version reporting (Lugdunum). */
    public static final String NAME_EMULE_VERSION = "\u00FB";
    /** Special tag name for server preference/priority (ST_PREFERENCE). */
    public static final String NAME_PREFERENCE = "\u000E";
    /** Special tag name for maximum user count (ST_MAXUSERS, legacy compat). */
    public static final String NAME_MAX_USERS_V2 = "\u0087";
    /** Special tag name for soft file limit (ST_SOFTFILES). */
    public static final String NAME_SOFT_FILES = "\u0088";
    /** Special tag name for hard file limit (ST_HARDFILES). */
    public static final String NAME_HARD_FILES = "\u0089";
    /** Special tag name for LowID user count (ST_LOWIDUSERS). */
    public static final String NAME_LOWID_USERS = "\u0094";
    /** Special tag name for UDP capabilities/flags (ST_UDPFLAGS). */
    public static final String NAME_UDP_FLAGS = "\u0095";
    /** Special tag name for UDP key (ST_UDPKEY). */
    public static final String NAME_UDP_KEY = "\u0096";
    /** Special tag name for UDP key IP (ST_UDPKEYIP). */
    public static final String NAME_UDP_KEY_IP = "\u0097";
    /** Special tag name for TCP obfuscation port (ST_TCPPORTOBFUSCATION). */
    public static final String NAME_TCP_OBFUSCATION_PORT = "\u0098";
    /** Special tag name for UDP obfuscation port (ST_UDPPORTOBFUSCATION). */
    public static final String NAME_UDP_OBFUSCATION_PORT = "\u0099";

    /**
     * Serializes this tag into a {@link ByteBuffer}.
     *
     * @param buf The buffer to write into.
     */
    public void write(ByteBuffer buf) {
        boolean isId = name.length() == 1;
        if (isId) {
            buf.put((byte) (type | 0x80));
            buf.put((byte) name.charAt(0));
        } else {
            buf.put(type);
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            buf.putShort((short) nameBytes.length);
            buf.put(nameBytes);
        }

        // Write value
        switch (type) {
            case TYPE_STRING -> {
                byte[] valBytes = ((String) value).getBytes(StandardCharsets.UTF_8);
                buf.putShort((short) valBytes.length);
                buf.put(valBytes);
            }
            case TYPE_INTEGER -> buf.putInt(((Number) value).intValue());
            case TYPE_FLOAT -> buf.putFloat(((Number) value).floatValue());
            case TYPE_BOOL, TYPE_BOOL_ALT -> buf.put((byte) (((Boolean) value) ? 1 : 0));
            case TYPE_BLOB -> {
                byte[] blob = (byte[]) value;
                buf.putInt(blob.length);
                buf.put(blob);
            }
            case TYPE_HASH -> {
                byte[] hash = (byte[]) value;
                buf.put(hash); // 16 bytes assumed
            }
            case TYPE_INT16 -> buf.putShort(((Number) value).shortValue());
            case TYPE_INT8 -> buf.put(((Number) value).byteValue());
            default -> throw new IllegalArgumentException("Unknown tag type: 0x" + String.format("%02X", type));
        }
    }

    /**
     * Reads a single tag from a {@link ByteBuffer}.
     *
     * @param buf The buffer to read from.
     * @return A new {@link Tag} instance.
     */
    public static Tag read(ByteBuffer buf) {
        byte rawType = buf.get();
        boolean isId = (rawType & 0x80) != 0;
        byte type = (byte) (rawType & 0x7F);

        String name;
        if (isId) {
            name = String.valueOf((char) (buf.get() & 0xFF));
        } else {
            int nameLen = Short.toUnsignedInt(buf.getShort());
            byte[] nameBytes = new byte[nameLen];
            buf.get(nameBytes);
            name = new String(nameBytes, StandardCharsets.UTF_8);
        }

        Object value;
        switch (type) {
            case TYPE_STRING -> {
                int valLen = Short.toUnsignedInt(buf.getShort());
                byte[] valBytes = new byte[valLen];
                buf.get(valBytes);
                value = new String(valBytes, StandardCharsets.UTF_8);
            }
            case TYPE_INTEGER -> value = buf.getInt();
            case TYPE_FLOAT -> value = buf.getFloat();
            case TYPE_BOOL, TYPE_BOOL_ALT -> value = buf.get() != 0;
            case TYPE_BLOB -> {
                int blobLen = buf.getInt();
                byte[] blob = new byte[blobLen];
                buf.get(blob);
                value = blob;
            }
            case TYPE_HASH -> {
                byte[] hash = new byte[16];
                buf.get(hash);
                value = hash;
            }
            case TYPE_INT16 -> value = buf.getShort();
            case TYPE_INT8 -> value = buf.get();
            default -> throw new IllegalArgumentException("Unknown tag type: 0x" + String.format("%02X", type));
        }
        return new Tag(type, name, value);
    }

    /**
     * Reads a list of tags from a {@link ByteBuffer} (prefixed by a 4-byte count).
     *
     * @param buf The buffer to read from.
     * @return A list of tags.
     */
    public static List<Tag> readList(ByteBuffer buf) {
        int count = buf.getInt();
        List<Tag> tags = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            tags.add(read(buf));
        }
        return tags;
    }

    /**
     * Writes a list of tags into a {@link ByteBuffer} (prefixed by a 4-byte count).
     *
     * @param buf  The buffer to write into.
     * @param tags The list of tags to serialize.
     */
    public static void writeList(ByteBuffer buf, List<Tag> tags) {
        buf.putInt(tags.size());
        for (Tag tag : tags) {
            tag.write(buf);
        }
    }
}
