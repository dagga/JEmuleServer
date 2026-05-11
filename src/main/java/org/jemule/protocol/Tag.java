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

package org.jemule.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents an ed2k tag.
 */
public record Tag(byte type, String name, Object value) {
    public static final byte TYPE_HASH = (byte) 0x01;
    public static final byte TYPE_STRING = (byte) 0x02;
    public static final byte TYPE_INTEGER = (byte) 0x03; // DWORD (4 bytes)
    public static final byte TYPE_FLOAT = (byte) 0x04;
    public static final byte TYPE_BOOL = (byte) 0x05;
    public static final byte TYPE_BOOL_ALT = (byte) 0x06;
    public static final byte TYPE_BLOB = (byte) 0x07;
    public static final byte TYPE_INT16 = (byte) 0x08;
    public static final byte TYPE_INT8 = (byte) 0x09;

    public static final String NAME_VERSION = "\u0011";
    public static final String NAME_PORT = "\u000F";
    public static final String NAME_NICK = "\u0001";
    public static final String NAME_NAME = "\u0001";
    public static final String NAME_DESCRIPTION = "\u000B";
    public static final String NAME_TCP_FLAGS = "\u0090";
    public static final String NAME_AUX_PORT = "\u0091";
    public static final String NAME_MAX_USERS = "\u0092";
    public static final String NAME_MAX_FILES = "\u0093";
    public static final String NAME_EMULE_VERSION = "\u00FB";

    public void write(ByteBuffer buf) {
        buf.put(type);
        
        // Write name
        byte[] nameBytes;
        if (name.length() == 1) {
            buf.putShort((short) 1);
            buf.put((byte) name.charAt(0));
        } else {
            nameBytes = name.getBytes(StandardCharsets.UTF_8);
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

    public static Tag read(ByteBuffer buf) {
        byte type = buf.get();
        int nameLen = Short.toUnsignedInt(buf.getShort());
        String name;
        if (nameLen == 1) {
            name = String.valueOf((char) (buf.get() & 0xFF));
        } else {
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

    public static List<Tag> readList(ByteBuffer buf) {
        int count = buf.getInt();
        List<Tag> tags = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            tags.add(read(buf));
        }
        return tags;
    }

    public static void writeList(ByteBuffer buf, List<Tag> tags) {
        buf.putInt(tags.size());
        for (Tag tag : tags) {
            tag.write(buf);
        }
    }
}
