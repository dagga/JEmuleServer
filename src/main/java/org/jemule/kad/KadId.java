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

import org.jemule.network.handler.HandlerUtils;

import java.io.Serializable;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Represents a 128-bit (16-byte) Kademlia identifier (NodeID, TargetID, or Keyword Hash).
 * Immutable and comparable using the standard Kademlia unsigned big-endian ordering.
 */
public final class KadId implements Comparable<KadId>, Serializable {
    private static final long serialVersionUID = 1L;

    public static final int ID_BYTES = 16;
    public static final int ID_BITS = 128;

    private final byte[] data;

    /**
     * Constructs a KadId from a 16-byte array.
     *
     * @param data The 16 bytes representing the ID.
     */
    public KadId(byte[] data) {
        Objects.requireNonNull(data, "data cannot be null");
        if (data.length != ID_BYTES) {
            throw new IllegalArgumentException("KadId must be exactly " + ID_BYTES + " bytes, got " + data.length);
        }
        this.data = data.clone();
    }

    /**
     * Creates a KadId with all bits set to 0.
     */
    public static KadId zero() {
        return new KadId(new byte[ID_BYTES]);
    }

    /**
     * Creates a KadId with all bits set to 1.
     */
    public static KadId max() {
        byte[] b = new byte[ID_BYTES];
        Arrays.fill(b, (byte) 0xFF);
        return new KadId(b);
    }

    /**
     * Generates a random 128-bit KadId.
     */
    public static KadId random() {
        byte[] b = new byte[ID_BYTES];
        ThreadLocalRandom.current().nextBytes(b);
        return new KadId(b);
    }

    /**
     * Creates a KadId from a 32-character hexadecimal string.
     */
    public static KadId fromHex(String hex) {
        if (hex == null || hex.length() != ID_BYTES * 2) {
            throw new IllegalArgumentException("Hex string must be 32 characters, got: " + hex);
        }
        return new KadId(HexFormat.of().parseHex(hex));
    }

    /**
     * Creates a KadId from an eDonkey / eMule hash string (case-insensitive).
     */
    public static KadId fromHash(String hash) {
        return fromHex(hash);
    }

    /**
     * Creates a KadId for a keyword search target by computing its MD4 hash in lower case.
     */
    public static KadId fromKeyword(String keyword) {
        if (keyword == null) {
            keyword = "";
        }
        String normalized = keyword.toLowerCase(Locale.ROOT).trim();
        return new KadId(MD4.digest(normalized));
    }

    /**
     * Computes the XOR distance d(this, other) = this ^ other.
     */
    public KadId xor(KadId other) {
        Objects.requireNonNull(other, "other KadId cannot be null");
        byte[] result = new byte[ID_BYTES];
        for (int i = 0; i < ID_BYTES; i++) {
            result[i] = (byte) (this.data[i] ^ other.data[i]);
        }
        return new KadId(result);
    }

    /**
     * Alias for {@link #xor(KadId)}.
     */
    public KadId distance(KadId other) {
        return xor(other);
    }

    /**
     * Returns the bit at the specified index, where 0 is the most significant bit (MSB).
     *
     * @param bitIndex index between 0 and 127.
     */
    public boolean getBit(int bitIndex) {
        if (bitIndex < 0 || bitIndex >= ID_BITS) {
            throw new IndexOutOfBoundsException("Bit index out of range: " + bitIndex);
        }
        int byteIndex = bitIndex / 8;
        int bitOffset = 7 - (bitIndex % 8);
        return ((data[byteIndex] >>> bitOffset) & 1) != 0;
    }

    /**
     * Returns the number of identical leading bits between this ID and another ID (from 0 to 128).
     */
    public int commonPrefixLength(KadId other) {
        Objects.requireNonNull(other, "other KadId cannot be null");
        int count = 0;
        for (int i = 0; i < ID_BYTES; i++) {
            int xorByte = (this.data[i] ^ other.data[i]) & 0xFF;
            if (xorByte == 0) {
                count += 8;
            } else {
                count += Integer.numberOfLeadingZeros(xorByte) - 24; // 32 - 8 = 24
                break;
            }
        }
        return count;
    }

    /**
     * Returns the number of leading zero bits in this ID (from 0 to 128).
     */
    public int getLeadingZeros() {
        int count = 0;
        for (int i = 0; i < ID_BYTES; i++) {
            int b = this.data[i] & 0xFF;
            if (b == 0) {
                count += 8;
            } else {
                count += Integer.numberOfLeadingZeros(b) - 24;
                break;
            }
        }
        return count;
    }

    /**
     * Returns a copy of the 16 bytes representing this ID.
     */
    public byte[] getBytes() {
        return data.clone();
    }

    /**
     * Formats this ID as a 32-character lowercase hex string.
     */
    public String toHexString() {
        return HexFormat.of().formatHex(data);
    }

    @Override
    public int compareTo(KadId other) {
        if (this == other) return 0;
        for (int i = 0; i < ID_BYTES; i++) {
            int a = this.data[i] & 0xFF;
            int b = other.data[i] & 0xFF;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        KadId kadId = (KadId) o;
        return Arrays.equals(data, kadId.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return toHexString();
    }
}
