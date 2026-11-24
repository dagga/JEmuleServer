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

import java.io.BufferedReader;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;

/**
 * The country of an IP address, from a table of address ranges (the DB-IP "IP to Country Lite" CSV:
 * {@code first,last,CC} per line, IPv4 and IPv6, covering the whole address space). Shown by the web panel only.
 * <p>
 * The table keeps the first address of each range and its country, the next range starting where it ends; neighbour
 * ranges of the same country are merged. IPv6 ranges are kept by their first 64 bits, the network part of an
 * address. About 3 MB for the 700,000 ranges of DB-IP.
 */
public final class GeoIp {
    /** Most lines read from a file: DB-IP has about 730,000. */
    static final int MAX_LINES = 4_000_000;
    /** A country code meaning "no country": a reserved or unknown range (DB-IP: ZZ). */
    private static final short NONE = 0;

    private final int[] v4Starts;   // first addresses, biased by Integer.MIN_VALUE to sort as signed ints
    private final short[] v4Codes;
    private final long[] v6Starts;  // first 64 bits, biased by Long.MIN_VALUE
    private final short[] v6Codes;

    private GeoIp(int[] v4Starts, short[] v4Codes, long[] v6Starts, short[] v6Codes) {
        this.v4Starts = v4Starts;
        this.v4Codes = v4Codes;
        this.v6Starts = v6Starts;
        this.v6Codes = v6Codes;
    }

    /** Number of ranges kept (after merging neighbours of the same country). */
    public int size() {
        return v4Starts.length + v6Starts.length;
    }

    /** The ISO 3166 code (two upper-case letters) of the address, or null when unknown, reserved or private. */
    public String country(InetAddress address) {
        if (address == null) return null;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int key = ((bytes[0] & 0xFF) << 24 | (bytes[1] & 0xFF) << 16 | (bytes[2] & 0xFF) << 8 | (bytes[3] & 0xFF))
                    ^ Integer.MIN_VALUE;
            int i = Arrays.binarySearch(v4Starts, key);
            return code(v4Codes, i < 0 ? -i - 2 : i);
        }
        if (address instanceof Inet6Address) {
            // An IPv4-mapped address (::ffff:a.b.c.d) is the IPv4 address
            if (isMapped(bytes)) {
                try {
                    return country(InetAddress.getByAddress(Arrays.copyOfRange(bytes, 12, 16)));
                } catch (java.net.UnknownHostException e) {
                    return null;
                }
            }
            int i = Arrays.binarySearch(v6Starts, high(bytes) ^ Long.MIN_VALUE);
            return code(v6Codes, i < 0 ? -i - 2 : i);
        }
        return null;
    }

    private static String code(short[] codes, int index) {
        if (index < 0 || codes[index] == NONE) return null;
        short code = codes[index];
        return new String(new char[]{(char) ((code >> 8) & 0xFF), (char) (code & 0xFF)});
    }

    private static boolean isMapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) if (bytes[i] != 0) return false;
        return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
    }

    private static long high(byte[] bytes) {
        long value = 0;
        for (int i = 0; i < 8; i++) value = value << 8 | (bytes[i] & 0xFF);
        return value;
    }

    /**
     * Reads a CSV of ranges, {@code first,last,CC} per line, in increasing order (as DB-IP publishes it). Lines that
     * cannot be read are skipped; ZZ and anything but two letters mean no country.
     *
     * @throws IOException when the file cannot be read, has too many lines or no range
     */
    public static GeoIp read(BufferedReader reader) throws IOException {
        IntList v4 = new IntList();
        LongList v6 = new LongList();
        ShortList v4c = new ShortList(), v6c = new ShortList();
        String line;
        int lines = 0;
        while ((line = reader.readLine()) != null) {
            if (++lines > MAX_LINES) throw new IOException("more than " + MAX_LINES + " lines");
            int a = line.indexOf(','), b = a < 0 ? -1 : line.indexOf(',', a + 1);
            if (b < 0) continue;
            String first = line.substring(0, a).trim();
            short code = code(line.substring(b + 1).trim());
            if (first.indexOf(':') < 0) {
                int start = ipv4(first);
                if (start == -1 && !first.equals("255.255.255.255")) continue;
                int key = start ^ Integer.MIN_VALUE;
                if (v4.size > 0 && key <= v4.last()) continue; // out of order: kept as read before
                if (v4c.size > 0 && v4c.last() == code) continue; // same country as the range before
                v4.add(key);
                v4c.add(code);
            } else {
                byte[] bytes = ipv6(first);
                if (bytes == null) continue;
                long key = high(bytes) ^ Long.MIN_VALUE;
                if (v6.size > 0 && key < v6.last()) continue;
                if (v6.size > 0 && key == v6.last()) {
                    // A range smaller than a /64 in the same /64: the first one stays
                    continue;
                }
                if (v6c.size > 0 && v6c.last() == code) continue;
                v6.add(key);
                v6c.add(code);
            }
        }
        if (v4.size + v6.size == 0) throw new IOException("no address range");
        return new GeoIp(v4.toArray(), v4c.toArray(), v6.toArray(), v6c.toArray());
    }

    private static short code(String country) {
        if (country.length() != 2) return NONE;
        char c1 = Character.toUpperCase(country.charAt(0)), c2 = Character.toUpperCase(country.charAt(1));
        if (c1 < 'A' || c1 > 'Z' || c2 < 'A' || c2 > 'Z' || (c1 == 'Z' && c2 == 'Z')) return NONE;
        return (short) (c1 << 8 | c2);
    }

    /** A dotted IPv4 address as an int, -1 when it is not one (255.255.255.255 is told apart by the caller). */
    static int ipv4(String text) {
        int value = 0, part = 0, digits = 0, dots = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.') {
                if (digits == 0 || ++dots > 3) return -1;
                value = value << 8 | part;
                part = 0;
                digits = 0;
            } else if (c >= '0' && c <= '9' && digits < 3) {
                part = part * 10 + (c - '0');
                digits++;
                if (part > 255) return -1;
            } else {
                return -1;
            }
        }
        if (dots != 3 || digits == 0) return -1;
        return value << 8 | part;
    }

    /** A literal IPv6 address (never resolved), or null when it is not one. */
    private static byte[] ipv6(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!(c == ':' || c == '.' || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return null;
            }
        }
        try {
            byte[] bytes = InetAddress.getByName(text).getAddress();
            return bytes.length == 16 ? bytes : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static final class IntList {
        int[] values = new int[1024];
        int size;

        void add(int value) {
            if (size == values.length) values = Arrays.copyOf(values, size * 2);
            values[size++] = value;
        }

        int last() {
            return values[size - 1];
        }

        int[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }

    private static final class LongList {
        long[] values = new long[1024];
        int size;

        void add(long value) {
            if (size == values.length) values = Arrays.copyOf(values, size * 2);
            values[size++] = value;
        }

        long last() {
            return values[size - 1];
        }

        long[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }

    private static final class ShortList {
        short[] values = new short[1024];
        int size;

        void add(short value) {
            if (size == values.length) values = Arrays.copyOf(values, size * 2);
            values[size++] = value;
        }

        short last() {
            return values[size - 1];
        }

        short[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }
}
