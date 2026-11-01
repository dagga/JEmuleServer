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

package org.jemule.security;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * RC4 framing of obfuscated server-to-server UDP, as Lugdunum sends it: eMule's client-server UDP obfuscation
 * (CEncryptedDatagramSocket), whose RC4 key is {@code MD5(<key 4><direction 1><salt 2>)}. The direction byte is
 * {@link #REQUEST} for a datagram sent to a server's obfuscated port (TCP+14, as an eMule client does) and
 * {@link #ANSWER} for the answer (as a server answers a client, and for the answer to a crypt ping on TCP+12).
 */
public final class ServerObfuscation {
    /** MAGICVALUE_UDP_CLIENTSERVER: a request sent to a server. */
    public static final byte REQUEST = (byte) 0x6B;
    /** MAGICVALUE_UDP_SERVERCLIENT: the answer of a server. */
    public static final byte ANSWER = (byte) 0xA5;
    private static final int MAGIC = 0x13EF24D5;
    private static final int MAX_PADDING = 12;
    private static final int ZERO_KEY_FALLBACK = 0x5D78B234;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ServerObfuscation() {}

    /**
     * Derives the per-peer key advertised in server status replies.
     */
    public static int serverKeyFor(byte[] secret, InetAddress peer) {
        if (secret == null || secret.length != 16) {
            throw new IllegalArgumentException("Server obfuscation secret must contain exactly 16 bytes");
        }
        if (!(peer instanceof Inet4Address)) {
            throw new IllegalArgumentException("Server-to-server obfuscation currently supports IPv4 peers only");
        }
        byte[] digest = Obfuscation.md5(secret, peer.getAddress());
        ByteBuffer words = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN);
        int key = words.getInt() ^ words.getInt() ^ words.getInt() ^ words.getInt();
        return key == 0 ? ZERO_KEY_FALLBACK : key;
    }

    /** The direction byte of the answer to a datagram obfuscated with {@code direction}. */
    public static byte reverse(byte direction) {
        return direction == REQUEST ? ANSWER : REQUEST;
    }

    /**
     * Encodes an ED2K UDP message with {@code key}: the key the receiving server advertised for our address (a
     * request), or the key of the request being answered.
     */
    public static byte[] encode(byte[] message, int key, byte direction) {
        if (message == null || message.length < 2) {
            throw new IllegalArgumentException("Server gossip message must contain a protocol and opcode");
        }
        if (key == 0) {
            throw new IllegalArgumentException("Server key must be non-zero");
        }

        byte[] salt = new byte[2];
        RANDOM.nextBytes(salt);
        int padLength = RANDOM.nextInt(MAX_PADDING + 1);
        byte[] plain = ByteBuffer.allocate(5 + padLength + message.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(MAGIC)
                .put((byte) padLength)
                .put(new byte[padLength])
                .put(message)
                .array();

        cipher(key, direction, salt).crypt(plain);

        byte[] packet = new byte[3 + plain.length];
        packet[0] = marker();
        packet[1] = salt[0];
        packet[2] = salt[1];
        System.arraycopy(plain, 0, packet, 3, plain.length);
        return packet;
    }

    /**
     * Decodes a server datagram. Returns {@code null} when the framing, key or direction is not the one used.
     */
    public static byte[] decode(byte[] packet, int key, byte direction) {
        if (packet == null || packet.length < 10 || key == 0 || isPlainMarker(packet[0])) {
            return null;
        }

        byte[] salt = {packet[1], packet[2]};
        byte[] plain = Arrays.copyOfRange(packet, 3, packet.length);
        cipher(key, direction, salt).crypt(plain);

        ByteBuffer frame = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN);
        if (frame.getInt() != MAGIC) return null;
        int padLength = Byte.toUnsignedInt(frame.get()) & 0x0F;
        int messageStart = 5 + padLength;
        if (messageStart > plain.length - 2) return null;
        return Arrays.copyOfRange(plain, messageStart, plain.length);
    }

    /**
     * A crypt ping (CServerList::ServerStats, and Lugdunum's ping to a server's TCP+12): {@code <random part 4>}
     * followed by up to 14 random bytes, sent in clear. The answer is obfuscated with the random part.
     *
     * @param randomPart non-zero, its low byte not a plain-packet marker
     */
    public static byte[] cryptPing(int randomPart) {
        byte[] ping = new byte[4 + RANDOM.nextInt(15)];
        RANDOM.nextBytes(ping);
        ByteBuffer.wrap(ping).order(ByteOrder.LITTLE_ENDIAN).putInt(randomPart);
        return ping;
    }

    /** A random part for {@link #cryptPing}: non-zero, and not read as a plain packet. */
    public static int cryptPingRandomPart() {
        int randomPart;
        do {
            randomPart = RANDOM.nextInt();
        } while (randomPart == 0 || isPlainMarker((byte) randomPart));
        return randomPart;
    }

    private static byte marker() {
        byte marker;
        do {
            marker = (byte) RANDOM.nextInt(256);
        } while (isPlainMarker(marker));
        return marker;
    }

    private static boolean isPlainMarker(byte value) {
        return value == (byte) 0xE3 || value == (byte) 0xD4 || value == (byte) 0xC5;
    }

    private static Obfuscation.RC4 cipher(int key, byte direction, byte[] salt) {
        byte[] input = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(key)
                .put(direction)
                .put(salt)
                .array();
        return new Obfuscation.RC4(Obfuscation.md5(input));
    }
}
