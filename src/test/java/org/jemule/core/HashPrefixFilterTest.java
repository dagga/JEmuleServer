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

package org.jemule.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HashPrefixFilterTest {

    @Test
    void theDefaultBansTheEmuleQtBridgesOnly() {
        HashPrefixFilter filter = HashPrefixFilter.parse(HashPrefixFilter.DEFAULT);
        assertEquals(List.of("ed2b01"), filter.prefixes());
        for (String bridge : List.of("0111", "0112", "0113", "0133", "0153")) {
            assertTrue(filter.matches("ED2B" + bridge + "00008A8F033CDCEF7349E8EB"), bridge);
        }
        assertTrue(filter.matches("ed2b011200008a8f033cdcef7349e8eb"), "any case");
        assertFalse(filter.matches("ED2B021200008A8F033CDCEF7349E8EB"));
        assertFalse(filter.matches("ED2C011200008A8F033CDCEF7349E8EB"));
        assertFalse(filter.matches("08C23A51C8220000000000000000000"), "a real prefix, not banned");
        assertFalse(filter.matches(null));
    }

    @Test
    void aListIsSplitAndCheckedPrefixByPrefix() {
        HashPrefixFilter filter = HashPrefixFilter.parse(" ED2B01120000, abcdef ;ABCDEF  123456 ");
        assertEquals(List.of("ed2b01120000", "abcdef", "123456"), filter.prefixes());
        assertTrue(filter.matches("ABCDEF0123456789ABCDEF0123456789"));
        assertEquals("ed2b01120000,abcdef,123456", filter.toString());
        assertTrue(HashPrefixFilter.parse("").isEmpty());
        assertTrue(HashPrefixFilter.parse(null).isEmpty());
        assertFalse(HashPrefixFilter.parse("").matches("ED2B011200008A8F033CDCEF7349E8EB"));
    }

    @Test
    void aPrefixHoldsSixToThirtyTwoHexadecimalDigits() {
        assertThrows(IllegalArgumentException.class, () -> HashPrefixFilter.parse("ED2B0"), "too short");
        assertThrows(IllegalArgumentException.class, () -> HashPrefixFilter.parse("ED2B01120000,xyz123"));
        assertThrows(IllegalArgumentException.class, () -> HashPrefixFilter.parse("0".repeat(33)));
        assertEquals("abcdef", HashPrefixFilter.normalizePrefix(" ABCDEF "));
    }
}
