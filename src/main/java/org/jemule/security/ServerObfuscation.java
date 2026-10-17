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
 * RC4 framing used by Lugdunum-compatible server-to-server UDP gossip.
 */
public final class ServerObfuscation {
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

    /**
     * Encodes an ED2K UDP message for a peer using the key that peer advertised for our address.
     */
    public static byte[] encode(byte[] message, int peerServerKey) {
        if (message == null || message.length < 2) {
            throw new IllegalArgumentException("Server gossip message must contain a protocol and opcode");
        }
        if (peerServerKey == 0) {
            throw new IllegalArgumentException("Peer server key must be non-zero");
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

        cipher(peerServerKey, salt).crypt(plain);

        byte marker;
        do {
            marker = (byte) RANDOM.nextInt(256);
        } while (marker == (byte) 0xE3 || marker == (byte) 0xD4 || marker == (byte) 0xC5);
        byte[] packet = new byte[3 + plain.length];
        packet[0] = marker;
        packet[1] = salt[0];
        packet[2] = salt[1];
        System.arraycopy(plain, 0, packet, 3, plain.length);
        return packet;
    }

    /**
     * Decodes a server gossip datagram. Returns {@code null} when the framing or key is invalid.
     */
    public static byte[] decode(byte[] packet, int localServerKeyForSender) {
        if (packet == null || packet.length < 10 || localServerKeyForSender == 0
                || packet[0] == (byte) 0xE3 || packet[0] == (byte) 0xD4 || packet[0] == (byte) 0xC5) {
            return null;
        }

        byte[] salt = {packet[1], packet[2]};
        byte[] plain = Arrays.copyOfRange(packet, 3, packet.length);
        cipher(localServerKeyForSender, salt).crypt(plain);

        ByteBuffer frame = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN);
        if (frame.getInt() != MAGIC) return null;
        int padLength = Byte.toUnsignedInt(frame.get()) & 0x0F;
        int messageStart = 5 + padLength;
        if (messageStart > plain.length - 2) return null;
        return Arrays.copyOfRange(plain, messageStart, plain.length);
    }

    private static Obfuscation.RC4 cipher(int serverKey, byte[] salt) {
        byte[] input = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(serverKey)
                .put((byte) 0)
                .put(salt)
                .array();
        return new Obfuscation.RC4(Obfuscation.md5(input));
    }
}
