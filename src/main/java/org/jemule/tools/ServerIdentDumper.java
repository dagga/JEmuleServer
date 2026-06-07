package org.jemule.tools;

import org.jemule.Main;
import org.jemule.protocol.Tag;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple utility to build the SERVER_IDENT payload as the server would send it
 * and print a hex dump and a parsed representation (round-trip) to help debugging tag encoding.
 */
public class ServerIdentDumper {
    public static void main(String[] args) throws Exception {
        byte[] hash = new byte[16];
        int port = 4662; // example

        String serverName = "JEmuleServer" + Main.VERSION;
        String serverVersion = Main.ESERVER_VERSION;
        String desc = "Experimental eMule Server";

        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_NAME, serverName));
        tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_DESCRIPTION, desc));
        tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_SERVER_VERSION, serverVersion));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_EMULE_VERSION, 0x3C));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_TCP_FLAGS, 0x01 | 0x04 | 0x08 | 0x10));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SERVER_VERSION, 0x3C));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_MAX_USERS, 1000));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_MAX_FILES, 100000));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_UDP_KEY, 0x12345678));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_UDP_KEY_IP, 0x7F000001));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_TCP_OBFUSCATION_PORT, port));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_UDP_OBFUSCATION_PORT, port));

        ByteBuffer buf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(hash);

        byte[] addr = InetAddress.getLoopbackAddress().getAddress();
        if (addr.length == 4) buf.put(addr);
        else buf.put(new byte[]{127,0,0,1});

        buf.put((byte) ((port >> 8) & 0xFF));
        buf.put((byte) (port & 0xFF));

        Tag.writeList(buf, tags);
        buf.flip();
        byte[] payload = new byte[buf.remaining()];
        buf.get(payload);

        System.out.println("SERVER_IDENT payload hex:");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < payload.length; i++) {
            sb.append(String.format("%02X", payload[i]));
            if (i < payload.length - 1) sb.append(' ');
            if ((i + 1) % 16 == 0) sb.append('\n');
        }
        System.out.println(sb);

        // Now parse back tags from payload
        ByteBuffer in = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        in.position(16 + 4 + 2); // skip hash, IP, port
        List<Tag> parsed = Tag.readList(in);
        System.out.println("Parsed tags:");
        for (Tag t : parsed) {
            System.out.printf("type=0x%02X name=%s value=%s\n", t.type(), t.name(), t.value());
        }
    }
}

