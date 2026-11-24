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
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The country of an address, from a DB-IP country CSV. */
class GeoIpTest {

    private static final String CSV = """
            0.0.0.0,0.255.255.255,ZZ
            1.0.0.0,1.0.0.255,AU
            1.0.1.0,1.0.3.255,CN
            1.0.4.0,1.0.7.255,CN
            5.39.0.0,5.39.127.255,FR
            10.0.0.0,10.255.255.255,ZZ
            11.0.0.0,11.255.255.255,US
            not,a line
            223.255.255.0,255.255.255.255,ZZ
            ::,1fff:ffff:ffff:ffff:ffff:ffff:ffff:ffff,ZZ
            2001:41d0::,2001:41d0:ffff:ffff:ffff:ffff:ffff:ffff,FR
            2001:41d1::,2001:41d1:ffff:ffff:ffff:ffff:ffff:ffff,DE
            2001:41d2::,ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff,ZZ
            """;

    private static GeoIp table() throws IOException {
        return GeoIp.read(new BufferedReader(new StringReader(CSV)));
    }

    @Test
    void findsTheCountryOfIpv4AndIpv6Addresses() throws Exception {
        GeoIp geo = table();
        assertEquals("AU", geo.country(InetAddress.getByName("1.0.0.7")));
        assertEquals("CN", geo.country(InetAddress.getByName("1.0.6.1")));
        assertEquals("FR", geo.country(InetAddress.getByName("5.39.64.3")));
        assertEquals("US", geo.country(InetAddress.getByName("11.1.2.3")));
        assertEquals("FR", geo.country(InetAddress.getByName("2001:41d0:8:1::5")));
        assertEquals("DE", geo.country(InetAddress.getByName("2001:41d1::1")));
        // An IPv4-mapped IPv6 address is the IPv4 address
        assertEquals("FR", geo.country(InetAddress.getByAddress(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                (byte) 0xFF, (byte) 0xFF, 5, 39, 1, 1})));
    }

    @Test
    void reservedAndUnknownRangesHaveNoCountry() throws Exception {
        GeoIp geo = table();
        assertNull(geo.country(InetAddress.getByName("10.1.2.3")));
        assertNull(geo.country(InetAddress.getByName("0.0.0.1")));
        assertNull(geo.country(InetAddress.getByName("255.255.255.255")));
        assertNull(geo.country(InetAddress.getByName("::1")));
        assertNull(geo.country(null));
    }

    @Test
    void neighbourRangesOfOneCountryAreMerged() throws Exception {
        // CN twice in a row, and the unreadable line skipped: 7 IPv4 ranges kept of 9 lines, 4 IPv6
        assertEquals(7 + 4, table().size());
    }

    @Test
    void anEmptyFileIsRefused() {
        assertThrows(IOException.class, () -> GeoIp.read(new BufferedReader(new StringReader("junk\n"))));
    }

    @Test
    void readsTheGzippedFileAsPublished(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("dbip.csv.gz");
        try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(CSV.getBytes(StandardCharsets.US_ASCII));
        }
        assertEquals("AU", GeoIpUpdater.load(file).country(InetAddress.getByName("1.0.0.1")));
        Path plain = dir.resolve("dbip.csv");
        Files.writeString(plain, CSV);
        assertEquals("US", GeoIpUpdater.load(plain).country(InetAddress.getByName("11.0.0.1")));
    }

    @Test
    void parsesDottedAddressesStrictly() {
        assertEquals(0x01020304, GeoIp.ipv4("1.2.3.4"));
        assertEquals(-1, GeoIp.ipv4("1.2.3"));
        assertEquals(-1, GeoIp.ipv4("1.2.3.256"));
        assertEquals(-1, GeoIp.ipv4("1.2.3.4.5"));
        assertEquals(-1, GeoIp.ipv4("a.b.c.d"));
    }
}
