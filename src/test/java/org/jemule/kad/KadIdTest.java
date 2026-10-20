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

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class KadIdTest {

    @Test
    void testMd4TestVectors() {
        // RFC 1320 standard test vectors
        assertEquals("31d6cfe0d16ae931b73c59d7e0c089c0", HexFormat.of().formatHex(MD4.digest("")));
        assertEquals("bde52cb31de33e46245e05fbdbd6fb24", HexFormat.of().formatHex(MD4.digest("a")));
        assertEquals("a448017aaf21d8525fc10ae87aa6729d", HexFormat.of().formatHex(MD4.digest("abc")));
        assertEquals("d9130a8164549fe818874806e1c7014b", HexFormat.of().formatHex(MD4.digest("message digest")));
    }

    @Test
    void testCreationAndHex() {
        String hex = "0123456789abcdef0123456789abcdef";
        KadId id = KadId.fromHex(hex);
        assertEquals(hex, id.toHexString());
        assertEquals(hex, id.toString());
        assertEquals(16, id.getBytes().length);

        assertThrows(IllegalArgumentException.class, () -> KadId.fromHex("short"));
        assertThrows(IllegalArgumentException.class, () -> new KadId(new byte[15]));
    }

    @Test
    void wireFormReversesEachThirtyTwoBitWord() {
        // CUInt128 is written as four little-endian uint32, most significant word first
        KadId id = KadId.fromHex("000102030405060708090a0b0c0d0e0f");
        assertEquals("03020100070605040b0a09080f0e0d0c", HexFormat.of().formatHex(id.toWire()));
        assertEquals(id, KadId.fromWire(id.toWire()));
        assertThrows(IllegalArgumentException.class, () -> KadId.fromWire(new byte[15]));
    }

    @Test
    void testXorDistance() {
        KadId id1 = KadId.fromHex("00000000000000000000000000000001");
        KadId id2 = KadId.fromHex("00000000000000000000000000000003");

        KadId dist = id1.distance(id2);
        assertEquals("00000000000000000000000000000002", dist.toHexString());

        // d(x, x) == 0
        assertEquals(KadId.zero(), id1.distance(id1));

        // Symmetry: d(x, y) == d(y, x)
        assertEquals(id1.distance(id2), id2.distance(id1));
    }

    @Test
    void testCommonPrefixLengthAndLeadingZeros() {
        KadId id1 = KadId.fromHex("f0000000000000000000000000000000"); // 11110000...
        KadId id2 = KadId.fromHex("f8000000000000000000000000000000"); // 11111000...

        // Identical bits: 1111 -> 4 bits
        assertEquals(4, id1.commonPrefixLength(id2));

        KadId idSame = KadId.fromHex("f0000000000000000000000000000000");
        assertEquals(128, id1.commonPrefixLength(idSame));

        KadId zero = KadId.zero();
        assertEquals(128, zero.getLeadingZeros());

        KadId one = KadId.fromHex("00000000000000000000000000000001");
        assertEquals(127, one.getLeadingZeros());

        KadId topBit = KadId.fromHex("80000000000000000000000000000000");
        assertEquals(0, topBit.getLeadingZeros());
        assertTrue(topBit.getBit(0));
        assertFalse(topBit.getBit(1));
    }

    @Test
    void testCompareTo() {
        KadId idSmall = KadId.fromHex("00000000000000000000000000000001");
        KadId idLarge = KadId.fromHex("80000000000000000000000000000000");

        assertTrue(idSmall.compareTo(idLarge) < 0);
        assertTrue(idLarge.compareTo(idSmall) > 0);
        assertEquals(0, idSmall.compareTo(KadId.fromHex("00000000000000000000000000000001")));
    }

    @Test
    void testKeywordHashing() {
        KadId id1 = KadId.fromKeyword("Matrix");
        KadId id2 = KadId.fromKeyword("matrix");
        KadId id3 = KadId.fromKeyword("  MATRIX  ");

        // Normalization should yield identical hashes
        assertEquals(id1, id2);
        assertEquals(id2, id3);
        assertNotNull(id1.toHexString());
    }

    @Test
    void testRandomAndEquality() {
        KadId r1 = KadId.random();
        KadId r2 = KadId.random();
        assertNotEquals(r1, r2);

        KadId r1Copy = new KadId(r1.getBytes());
        assertEquals(r1, r1Copy);
        assertEquals(r1.hashCode(), r1Copy.hashCode());
    }
}
