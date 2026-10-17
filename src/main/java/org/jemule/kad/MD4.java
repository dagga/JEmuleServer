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

import java.nio.charset.StandardCharsets;

/**
 * Standard implementation of the MD4 message-digest algorithm (RFC 1320).
 * Used in the eDonkey2000 and Kademlia protocols for file hashes, user hashes,
 * and keyword search hashes.
 */
public final class MD4 {

    private MD4() {
    }

    /**
     * Computes the 16-byte MD4 digest of the given UTF-8 string.
     */
    public static byte[] digest(String text) {
        if (text == null) {
            return digest(new byte[0]);
        }
        return digest(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Computes the 16-byte MD4 digest of the given byte array.
     */
    public static byte[] digest(byte[] input) {
        if (input == null) {
            input = new byte[0];
        }

        int a = 0x67452301;
        int b = 0xefcdab89;
        int c = 0x98badcfe;
        int d = 0x10325476;

        int numBytes = input.length;
        long numBits = (long) numBytes * 8;

        // Calculate padded length: message + 1 byte (0x80) + padding + 8 bytes length
        int mod = numBytes % 64;
        int paddingBytes = (mod < 56) ? (56 - mod) : (120 - mod);
        int totalLength = numBytes + paddingBytes + 8;

        byte[] padded = new byte[totalLength];
        System.arraycopy(input, 0, padded, 0, numBytes);
        padded[numBytes] = (byte) 0x80;

        // Append 64-bit length in little-endian
        for (int i = 0; i < 8; i++) {
            padded[totalLength - 8 + i] = (byte) ((numBits >>> (i * 8)) & 0xFF);
        }

        int[] x = new int[16];
        for (int offset = 0; offset < totalLength; offset += 64) {
            for (int j = 0; j < 16; j++) {
                int index = offset + j * 4;
                x[j] = (padded[index] & 0xFF)
                        | ((padded[index + 1] & 0xFF) << 8)
                        | ((padded[index + 2] & 0xFF) << 16)
                        | ((padded[index + 3] & 0xFF) << 24);
            }

            int aa = a;
            int bb = b;
            int cc = c;
            int dd = d;

            // Round 1
            a = ff(a, b, c, d, x[0], 3);
            d = ff(d, a, b, c, x[1], 7);
            c = ff(c, d, a, b, x[2], 11);
            b = ff(b, c, d, a, x[3], 19);
            a = ff(a, b, c, d, x[4], 3);
            d = ff(d, a, b, c, x[5], 7);
            c = ff(c, d, a, b, x[6], 11);
            b = ff(b, c, d, a, x[7], 19);
            a = ff(a, b, c, d, x[8], 3);
            d = ff(d, a, b, c, x[9], 7);
            c = ff(c, d, a, b, x[10], 11);
            b = ff(b, c, d, a, x[11], 19);
            a = ff(a, b, c, d, x[12], 3);
            d = ff(d, a, b, c, x[13], 7);
            c = ff(c, d, a, b, x[14], 11);
            b = ff(b, c, d, a, x[15], 19);

            // Round 2
            a = gg(a, b, c, d, x[0], 3);
            d = gg(d, a, b, c, x[4], 5);
            c = gg(c, d, a, b, x[8], 9);
            b = gg(b, c, d, a, x[12], 13);
            a = gg(a, b, c, d, x[1], 3);
            d = gg(d, a, b, c, x[5], 5);
            c = gg(c, d, a, b, x[9], 9);
            b = gg(b, c, d, a, x[13], 13);
            a = gg(a, b, c, d, x[2], 3);
            d = gg(d, a, b, c, x[6], 5);
            c = gg(c, d, a, b, x[10], 9);
            b = gg(b, c, d, a, x[14], 13);
            a = gg(a, b, c, d, x[3], 3);
            d = gg(d, a, b, c, x[7], 5);
            c = gg(c, d, a, b, x[11], 9);
            b = gg(b, c, d, a, x[15], 13);

            // Round 3
            a = hh(a, b, c, d, x[0], 3);
            d = hh(d, a, b, c, x[8], 9);
            c = hh(c, d, a, b, x[4], 11);
            b = hh(b, c, d, a, x[12], 15);
            a = hh(a, b, c, d, x[2], 3);
            d = hh(d, a, b, c, x[10], 9);
            c = hh(c, d, a, b, x[6], 11);
            b = hh(b, c, d, a, x[14], 15);
            a = hh(a, b, c, d, x[1], 3);
            d = hh(d, a, b, c, x[9], 9);
            c = hh(c, d, a, b, x[5], 11);
            b = hh(b, c, d, a, x[13], 15);
            a = hh(a, b, c, d, x[3], 3);
            d = hh(d, a, b, c, x[11], 9);
            c = hh(c, d, a, b, x[7], 11);
            b = hh(b, c, d, a, x[15], 15);

            a += aa;
            b += bb;
            c += cc;
            d += dd;
        }

        byte[] result = new byte[16];
        writeIntLittleEndian(result, 0, a);
        writeIntLittleEndian(result, 4, b);
        writeIntLittleEndian(result, 8, c);
        writeIntLittleEndian(result, 12, d);
        return result;
    }

    private static int ff(int a, int b, int c, int d, int x, int s) {
        int t = a + ((b & c) | (~b & d)) + x;
        return Integer.rotateLeft(t, s);
    }

    private static int gg(int a, int b, int c, int d, int x, int s) {
        int t = a + ((b & c) | (b & d) | (c & d)) + x + 0x5a827999;
        return Integer.rotateLeft(t, s);
    }

    private static int hh(int a, int b, int c, int d, int x, int s) {
        int t = a + (b ^ c ^ d) + x + 0x6ed9eba1;
        return Integer.rotateLeft(t, s);
    }

    private static void writeIntLittleEndian(byte[] dest, int offset, int value) {
        dest[offset] = (byte) (value & 0xFF);
        dest[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        dest[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        dest[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }
}
