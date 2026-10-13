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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * IPv4 filter loaded from an eMule ipfilter.dat file: <code>start - end , level , description</code>.
 * <p>
 * Like eMule, a range blocks only if its access level is below {@link #FILTER_LEVEL}: ranges of level 100 or more
 * (often 127 or 255) are explicitly allowed. A line without level blocks. Overlapping and adjacent ranges are merged,
 * so that the lookup finds an address whatever the order and the nesting of the ranges in the file.
 */
public class IPFilter {
    private static final Logger log = LoggerFactory.getLogger(IPFilter.class);

    /** eMule's default IP filter level: ranges of a lower level are blocked. */
    public static final int FILTER_LEVEL = 100;

    private record IPRange(long start, long end) {}

    /** Disjoint blocked ranges sorted by start. Replaced as a whole, so that lookups never see a partial list. */
    private volatile List<IPRange> blockedRanges = List.of();

    /**
     * Loads blocked IP ranges from an ipfilter.dat file.
     *
     * @param filePath Path to the ipfilter.dat file.
     */
    public void loadFromFile(String filePath) {
        if (filePath == null) return;

        List<IPRange> ranges = new ArrayList<>();
        int allowed = 0;
        // ISO-8859-1 never fails on the descriptions, whatever their encoding; only the ASCII part is parsed
        try (BufferedReader br = Files.newBufferedReader(Path.of(filePath), StandardCharsets.ISO_8859_1)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;

                String[] parts = line.split(",", 3);
                String[] ips = parts[0].split("-");
                long start = ips.length == 2 ? ipToLong(ips[0].trim()) : -1;
                long end = ips.length == 2 ? ipToLong(ips[1].trim()) : -1;
                if (start == -1 || end == -1 || start > end) {
                    log.warn("Failed to parse ipfilter line: {}", line);
                    continue;
                }
                if (parts.length > 1 && parseLevel(parts[1]) >= FILTER_LEVEL) {
                    allowed++;
                    continue;
                }
                ranges.add(new IPRange(start, end));
            }
        } catch (IOException e) {
            log.error("Could not read IP filter file {}: {}", filePath, e.getMessage());
            return;
        }
        blockedRanges = merge(ranges);
        log.info("Loaded {} blocking IP filter ranges from {} ({} after merging overlaps, {} allowed ranges ignored)",
                ranges.size(), filePath, blockedRanges.size(), allowed);
    }

    private static int parseLevel(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0; // unreadable level: block, like a line without level
        }
    }

    private static List<IPRange> merge(List<IPRange> ranges) {
        ranges.sort(Comparator.comparingLong(IPRange::start));
        List<IPRange> merged = new ArrayList<>();
        for (IPRange r : ranges) {
            if (!merged.isEmpty() && r.start() <= merged.getLast().end() + 1) {
                IPRange last = merged.removeLast();
                merged.add(new IPRange(last.start(), Math.max(last.end(), r.end())));
            } else {
                merged.add(r);
            }
        }
        return List.copyOf(merged);
    }

    /**
     * Checks if an IP address is blocked.
     *
     * @param ip The IP address string (dotted IPv4; other addresses are never blocked).
     * @return True if blocked, false otherwise.
     */
    public boolean isBlocked(String ip) {
        List<IPRange> ranges = blockedRanges;
        if (ranges.isEmpty()) return false;

        long ipLong = ipToLong(ip);
        if (ipLong == -1) return false;

        // Last range starting at or before the address
        int lo = 0, hi = ranges.size() - 1, found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (ranges.get(mid).start() <= ipLong) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found >= 0 && ipLong <= ranges.get(found).end();
    }

    /**
     * Parses a dotted IPv4 address, zero-padded or not ("001.002.003.004"), without any DNS lookup.
     *
     * @return the address as an unsigned 32-bit value, or -1 if it is not a dotted IPv4 address
     */
    static long ipToLong(String ip) {
        if (ip == null) return -1;
        String[] octets = ip.split("\\.", -1);
        if (octets.length != 4) return -1;
        long result = 0;
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3 || !octet.chars().allMatch(Character::isDigit)) return -1;
            int value = Integer.parseInt(octet);
            if (value > 255) return -1;
            result = (result << 8) | value;
        }
        return result;
    }

    public int getFilterCount() {
        return blockedRanges.size();
    }
}
