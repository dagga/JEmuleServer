package org.jemule.protocol;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public record Tag(byte type, String name, Object value) {
    private static final Logger log = LoggerFactory.getLogger(Tag.class);

    public static final byte TYPE_HASH = (byte) 0x01;
    public static final byte TYPE_STRING = (byte) 0x02;
    public static final byte TYPE_INTEGER = (byte) 0x03;
    public static final byte TYPE_FLOAT = (byte) 0x04;
    public static final byte TYPE_BOOL = (byte) 0x05;
    public static final byte TYPE_BOOL_ALT = (byte) 0x06;
    public static final byte TYPE_BLOB = (byte) 0x07;
    public static final byte TYPE_INT16 = (byte) 0x08;
    public static final byte TYPE_INT8 = (byte) 0x09;
    public static final byte TYPE_UINT64 = (byte) 0x14;

    // Server Tags (ST_ from opcodes.h)
    public static final String NAME_NAME = "\u0001"; // ST_SERVERNAME
    public static final String NAME_SERVERNAME = NAME_NAME;
    public static final String NAME_NICK = NAME_NAME; // ST_CLIENTNAME (Used in PacketTest)
    public static final String NAME_DESCRIPTION = "\u000B"; // ST_DESCRIPTION
    public static final String NAME_PING = "\u000C"; // ST_PING
    public static final String NAME_FAIL = "\r"; // ST_FAIL (Corrected to "\r" for CR character)
    public static final String NAME_PREFERENCE = "\u000E"; // ST_PREFERENCE
    public static final String NAME_PORT = "\u000F"; // ST_PORT
    public static final String NAME_IP = "\u0010"; // ST_IP
    public static final String NAME_DYNIP = "\u0085"; // ST_DYNIP
    public static final String NAME_MAXUSERS = "\u0087"; // ST_MAXUSERS
    public static final String NAME_MAX_USERS = NAME_MAXUSERS;
    public static final String NAME_MAX_FILES = "\u0089"; // ST_HARDFILES
    public static final String NAME_SOFTFILES = "\u0088"; // ST_SOFTFILES
    public static final String NAME_SOFT_FILES = NAME_SOFTFILES; // Alias for test
    public static final String NAME_HARDFILES = NAME_MAX_FILES;
    public static final String NAME_HARD_FILES = NAME_HARDFILES; // Alias for test
    public static final String NAME_LASTPING = "\u0090"; // ST_LASTPING
    public static final String NAME_VERSION = "\u0091"; // ST_VERSION (Used for ST_VERSION)
    public static final String NAME_UDPFLAGS = "\u0092"; // ST_UDPFLAGS
    public static final String NAME_AUXPORTSLIST = "\u0093"; // ST_AUXPORTSLIST
    public static final String NAME_LOWIDUSERS = "\u0094"; // ST_LOWIDUSERS
    public static final String NAME_UDPKEY = "\u0095"; // ST_UDPKEY
    public static final String NAME_UDP_KEY = NAME_UDPKEY;
    public static final String NAME_UDPKEYIP = "\u0096"; // ST_UDPKEYIP
    public static final String NAME_UDP_KEY_IP = NAME_UDPKEYIP;
    public static final String NAME_TCPPORTOBFUSCATION = "\u0097"; // ST_TCPPORTOBFUSCATION
    public static final String NAME_TCP_OBFUSCATION_PORT = NAME_TCPPORTOBFUSCATION;
    public static final String NAME_UDPPORTOBFUSCATION = "\u0098"; // ST_UDPPORTOBFUSCATION
    public static final String NAME_UDP_OBFUSCATION_PORT = NAME_UDPPORTOBFUSCATION;

    // File Tags (FT_ from opcodes.h)
    public static final String NAME_FILENAME = "\u0001"; // FT_FILENAME (Note: same as NAME_SERVERNAME)
    public static final String NAME_FILESIZE = "\u0002"; // FT_FILESIZE
    public static final String NAME_FILESIZE_HI = "\u003A"; // FT_FILESIZE_HI
    public static final String NAME_FILETYPE = "\u0003"; // FT_FILETYPE
    public static final String NAME_FILEFORMAT = "\u0004"; // FT_FILEFORMAT
    public static final String NAME_SOURCES = "\u0015"; // FT_SOURCES

    // Client/Server Capability Tags (CT_ from opcodes.h)
    public static final String NAME_TCP_FLAGS = "\u0020"; // CT_SERVER_FLAGS
    public static final String NAME_CLIENT_VERSION = "\u0011"; // CT_VERSION
    public static final String NAME_EMULE_VERSION = "\u00FB"; // CT_EMULE_VERSION
    public static final String NAME_MAX_USERS_V2 = "\u0087"; // CT_MAX_USERS_V2 (Same as ST_MAXUSERS, but used in Server.java)
    public static final String NAME_SERVER_VERSION = "\u0091"; // CT_SERVER_VERSION (Same as ST_VERSION, but used in Server.java)


    // Capability flags (from server.h)
    public static final int TCPFLG_COMPRESSION = 0x01;
    public static final int TCPFLG_NEWTAGS = 0x08;
    public static final int TCPFLG_UNICODE = 0x10;
    public static final int TCPFLG_TYPETAGINTEGER = 0x80;
    public static final int TCPFLG_LARGEFILES = 0x100;
    public static final int TCPFLG_TCPOBFUSCATION = 0x400;

    public static final int UDPFLG_EXT_GETSOURCES = 0x01;
    public static final int UDPFLG_NEWTAGS = 0x08;
    public static final int UDPFLG_UNICODE = 0x10;
    public static final int UDPFLG_LARGEFILES = 0x100;
    public static final int UDPFLG_UDPOBFUSCATION = 0x200;
    public static final int UDPFLG_TCPOBFUSCATION = 0x400;
    public static final int UDPFLG_EXT_GETSOURCES2 = 0x20; // Added for reference


    public void write(ByteBuffer buf) {
        if (log.isDebugEnabled()) {
            log.debug("Tag.write: name='{}', type=0x{}, value='{}', buf.position={}, buf.remaining={}",
                    name, String.format("%02X", type & 0xFF), value, buf.position(), buf.remaining());
        }

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
                buf.put(hash);
            }
            case TYPE_INT16 -> buf.putShort(((Number) value).shortValue());
            case TYPE_INT8 -> buf.put(((Number) value).byteValue());
            case TYPE_UINT64 -> buf.putLong(((Number) value).longValue());
            default -> throw new IllegalArgumentException("Unknown tag type: 0x" + String.format("%02X", type));
        }
    }

    public static Tag read(ByteBuffer buf) {
        if (log.isDebugEnabled()) {
            log.debug("Tag.read: Entering, buf.position={}, buf.remaining={}", buf.position(), buf.remaining());
        }

        byte rawType = buf.get();
        boolean isId = (rawType & 0x80) != 0;
        byte type = (byte) (rawType & 0x7F);

        String name;
        if (isId) {
            name = String.valueOf((char) (buf.get() & 0xFF));
        } else {
            int nameLen = Short.toUnsignedInt(buf.getShort());
            if (log.isDebugEnabled()) {
                log.debug("Tag.read: Reading name, nameLen={}, buf.position={}, buf.remaining={}", nameLen, buf.position(), buf.remaining());
            }
            byte[] nameBytes = new byte[nameLen];
            buf.get(nameBytes);
            name = new String(nameBytes, StandardCharsets.UTF_8);
        }

        Object value;
        if (log.isDebugEnabled()) {
            log.debug("Tag.read: Name='{}', Type=0x{}, buf.position={}, buf.remaining={}", name, String.format("%02X", type & 0xFF), buf.position(), buf.remaining());
        }

        switch (type) {
            case TYPE_STRING -> {
                int valLen = Short.toUnsignedInt(buf.getShort());
                if (log.isDebugEnabled()) {
                    log.debug("Tag.read: Reading string value, valLen={}, buf.position={}, buf.remaining={}", valLen, buf.position(), buf.remaining());
                }
                byte[] valBytes = new byte[valLen];
                buf.get(valBytes);
                value = new String(valBytes, StandardCharsets.UTF_8);
            }
            case TYPE_INTEGER -> value = buf.getInt();
            case TYPE_FLOAT -> value = buf.getFloat();
            case TYPE_BOOL, TYPE_BOOL_ALT -> value = buf.get() != 0;
            case TYPE_BLOB -> {
                int blobLen = buf.getInt();
                if (log.isDebugEnabled()) {
                    log.debug("Tag.read: Reading blob value, blobLen={}, buf.position={}, buf.remaining={}", blobLen, buf.position(), buf.remaining());
                }
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
            case TYPE_UINT64 -> value = buf.getLong();
            default -> throw new IllegalArgumentException("Unknown tag type: 0x" + String.format("%02X", type));
        }
        if (log.isDebugEnabled()) {
            log.debug("Tag.read: Exiting, Tag='{}', Value='{}', buf.position={}, buf.remaining={}", name, value, buf.position(), buf.remaining());
        }
        return new Tag(type, name, value);
    }

    public static List<Tag> readList(ByteBuffer buf) {
        if (log.isDebugEnabled()) {
            log.debug("Tag.readList: Entering, buf.position={}, buf.remaining={}", buf.position(), buf.remaining());
        }
        int count = buf.getInt();
        if (log.isDebugEnabled()) {
            log.debug("Tag.readList: Reading {} tags, buf.position={}, buf.remaining={}", count, buf.position(), buf.remaining());
        }
        List<Tag> tags = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            tags.add(read(buf));
        }
        if (log.isDebugEnabled()) {
            log.debug("Tag.readList: Exiting, read {} tags, buf.position={}, buf.remaining={}", count, buf.position(), buf.remaining());
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